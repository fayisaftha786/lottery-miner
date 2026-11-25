package com.lottery.miner

import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/** Bounded real SHA-256d benchmarks; no pool connection and no shares are submitted. */
object Sha256dBenchmark {
    data class Result(
        val hashes: Long,
        val elapsedMillis: Long,
        val hashesPerSecond: Double,
        val hardwareSha: Boolean
    )

    data class WorkerResult(
        val workers: Int,
        val hashes: Long,
        val elapsedMillis: Long,
        val hashesPerSecond: Double,
        val medianHashesPerSecond: Double,
        val minimumHashesPerSecond: Double,
        val maximumHashesPerSecond: Double,
        val sampleCount: Int
    )

    data class ScalingResult(
        val results: List<WorkerResult>,
        val hardwareSha: Boolean
    ) {
        /** Highest measured median throughput. */
        val peak: WorkerResult
            get() = results.maxBy { it.medianHashesPerSecond }

        /**
         * Lowest worker count retaining at least 98.5% of peak median throughput.
         * This is a conservative throughput recommendation, not a measured
         * energy-efficiency recommendation.
         */
        val recommended: WorkerResult
            get() {
                val workerRates = results.associate { it.workers to it.medianHashesPerSecond }
                val workers = BenchmarkMath.recommendWorkers(workerRates)
                return results.first { it.workers == workers }
            }

        /** Kept for compatibility with older UI callers. */
        val best: WorkerResult get() = peak
    }

    private data class ScanReading(
        val hashes: Long,
        val elapsedMillis: Long,
        val hashesPerSecond: Double
    )

    /** Poll a system thermal API at most once per interval across all benchmark workers. */
    private class ThrottledSafetyCheck(private val check: () -> Boolean) {
        private val lock = Any()
        @Volatile private var nextCheckAtNanos = 0L
        @Volatile private var lastSafe = true

        fun isSafe(): Boolean {
            val now = System.nanoTime()
            if (now >= nextCheckAtNanos) {
                synchronized(lock) {
                    val checkedAt = System.nanoTime()
                    if (checkedAt >= nextCheckAtNanos) {
                        lastSafe = check()
                        nextCheckAtNanos = checkedAt + SAFETY_CHECK_INTERVAL_NANOS
                    }
                }
            }
            return lastSafe
        }
    }

    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    /** Single-thread reference benchmark retained for existing callers. */
    fun run(durationMillis: Long = 5_000L): Result {
        require(durationMillis in 1_000L..5_000L) { "Benchmark duration must be 1–5 seconds" }
        check(running.compareAndSet(false, true)) { "A benchmark is already running" }
        try {
            val hw = Native.initCore()
            check(hw >= 0) { "Native SHA-256 self-test failed" }
            val reading = scanForDuration(workers = 1, durationMillis = durationMillis, shouldContinue = { true })
            return Result(reading.hashes, reading.elapsedMillis, reading.hashesPerSecond, hw == 1)
        } finally {
            running.set(false)
        }
    }

    /**
     * Compares candidate worker counts with:
     * - a warm-up run for each candidate,
     * - randomized candidate order in each measured round,
     * - repeated measurements and median throughput,
     * - a short cool-down between trials.
     *
     * Timing starts only after all worker threads are ready, so thread creation
     * time is excluded. Nonce ranges are disjoint: worker i starts at i*chunk
     * and advances by workers*chunk, with uint32 wraparound.
     */
    fun runScaling(
        maxWorkers: Int,
        durationMillis: Long = 900L,
        repeatCount: Int = 2,
        shouldContinue: () -> Boolean = { true }
    ): ScalingResult {
        require(maxWorkers in 1..8) { "Worker count must be 1–8" }
        require(durationMillis in 500L..2_000L) { "Each benchmark must be 0.5–2 seconds" }
        require(repeatCount in 2..4) { "Use 2–4 measured repetitions" }
        check(running.compareAndSet(false, true)) { "A benchmark is already running" }
        try {
            val hw = Native.initCore()
            check(hw >= 0) { "Native SHA-256 self-test failed" }

            val candidates = listOf(1, 2, 4, maxWorkers)
                .filter { it <= maxWorkers }
                .distinct()
                .sorted()
            val samples = candidates.associateWith { mutableListOf<ScanReading>() }
            val warmed = HashSet<Int>()

            repeat(repeatCount) {
                val order = candidates.shuffled(Random(System.nanoTime()))
                for (workers in order) {
                    if (warmed.add(workers)) {
                        // Warm caches/JIT/CPU scheduling, but exclude this reading.
                        scanForDuration(workers, WARMUP_MILLIS, shouldContinue)
                    }
                    samples.getValue(workers).add(scanForDuration(workers, durationMillis, shouldContinue))
                    Thread.sleep(COOLDOWN_MILLIS)
                }
            }

            val results = candidates.map { workers ->
                val readings = samples.getValue(workers)
                val rates = readings.map { it.hashesPerSecond }
                val totalHashes = readings.sumOf { it.hashes }
                val totalElapsed = readings.sumOf { it.elapsedMillis }.coerceAtLeast(1L)
                WorkerResult(
                    workers = workers,
                    hashes = totalHashes,
                    elapsedMillis = totalElapsed,
                    hashesPerSecond = totalHashes * 1000.0 / totalElapsed,
                    medianHashesPerSecond = BenchmarkMath.median(rates),
                    minimumHashesPerSecond = rates.minOrNull() ?: 0.0,
                    maximumHashesPerSecond = rates.maxOrNull() ?: 0.0,
                    sampleCount = readings.size
                )
            }
            check(results.all { it.hashes > 0L && it.sampleCount == repeatCount }) {
                "One or more worker configurations produced incomplete benchmark data"
            }
            return ScalingResult(results, hw == 1)
        } finally {
            running.set(false)
        }
    }

    private fun scanForDuration(
        workers: Int,
        durationMillis: Long,
        shouldContinue: () -> Boolean
    ): ScanReading {
        require(workers in 1..8)
        require(durationMillis in 100L..5_000L)

        val header = ByteArray(80)
        for (i in header.indices) header[i] = ((i * 29 + 7) and 0xff).toByte()
        // An all-zero target prevents benchmark hashes becoming submitted pool shares.
        val target = ByteArray(32)
        val hashes = AtomicLong(0L)
        val failure = AtomicReference<Throwable?>(null)
        val stop = AtomicBoolean(false)
        val thermalAbort = AtomicBoolean(false)
        val safetyCheck = ThrottledSafetyCheck(shouldContinue)
        val deadlineNanos = AtomicLong(0L)
        val ready = CountDownLatch(workers)
        val startGate = CountDownLatch(1)
        val chunk = BENCHMARK_CHUNK
        val threads = (0 until workers).map { index ->
            Thread({
                var announcedReady = false
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE)
                    val found = IntArray(8)
                    val best = DoubleArray(3)
                    var nonce = index.toLong() * chunk.toLong()
                    ready.countDown()
                    announcedReady = true
                    startGate.await()
                    while (!stop.get() && System.nanoTime() < deadlineNanos.get()) {
                        if (!safetyCheck.isSafe()) {
                            thermalAbort.set(true)
                            stop.set(true)
                            break
                        }
                        val result = Native.scan(header, nonce.toInt(), chunk, target, found, best)
                        check(result in 0..found.size) { "Native benchmark scan failed: $result" }
                        check(best[2] == 0.0) { "Unexpected benchmark target hit/overflow" }
                        hashes.addAndGet(chunk.toLong())
                        nonce = (nonce + workers.toLong() * chunk.toLong()) and UINT32_MASK
                    }
                } catch (error: Throwable) {
                    if (!announcedReady) ready.countDown()
                    failure.compareAndSet(null, error)
                    stop.set(true)
                }
            }, "sha256d-bench-$index")
        }

        try {
            // Start inside the guarded block: if the OS refuses to create one
            // worker thread, release already-started workers and join them below.
            threads.forEach { it.start() }
            check(ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Benchmark workers did not become ready"
            }
            val startedAt = System.nanoTime()
            deadlineNanos.set(startedAt + durationMillis * 1_000_000L)
            startGate.countDown()
            val joinDeadline = startedAt + (durationMillis + JOIN_GRACE_MILLIS) * 1_000_000L
            for (thread in threads) {
                val remaining = joinDeadline - System.nanoTime()
                if (remaining > 0L) thread.join(maxOf(1L, remaining / 1_000_000L))
            }
            val stuck = threads.filter { it.isAlive }
            if (stuck.isNotEmpty()) {
                stop.set(true)
                stuck.forEach { it.interrupt() }
                stuck.forEach { it.join(STUCK_THREAD_JOIN_MILLIS) }
                throw IllegalStateException("Benchmark worker failed to stop after its time limit")
            }
            val elapsedMillis = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
            if (thermalAbort.get()) {
                throw IllegalStateException("Device thermal status reached MODERATE or higher; benchmark stopped safely")
            }
            failure.get()?.let { throw IllegalStateException("SHA-256d benchmark worker failed", it) }
            val count = hashes.get()
            check(count > 0L) { "Benchmark completed no work" }
            val rate = count * 1000.0 / elapsedMillis
            check(rate.isFinite() && rate > 0.0) { "Benchmark produced an invalid hashrate" }
            return ScanReading(count, elapsedMillis, rate)
        } catch (error: InterruptedException) {
            stop.set(true)
            startGate.countDown()
            threads.forEach { it.interrupt() }
            threads.forEach { runCatching { it.join(STUCK_THREAD_JOIN_MILLIS) } }
            Thread.currentThread().interrupt()
            throw IllegalStateException("Benchmark interrupted", error)
        } catch (error: Throwable) {
            stop.set(true)
            startGate.countDown()
            threads.forEach { if (it.isAlive) it.interrupt() }
            threads.forEach { runCatching { it.join(STUCK_THREAD_JOIN_MILLIS) } }
            throw error
        }
    }

    private const val BENCHMARK_CHUNK = 32_768
    private const val WARMUP_MILLIS = 250L
    private const val COOLDOWN_MILLIS = 150L
    private const val READY_TIMEOUT_SECONDS = 5L
    private const val JOIN_GRACE_MILLIS = 3_000L
    private const val STUCK_THREAD_JOIN_MILLIS = 1_000L
    private const val SAFETY_CHECK_INTERVAL_NANOS = 500_000_000L
    private const val UINT32_MASK = 0xffff_ffffL
}
