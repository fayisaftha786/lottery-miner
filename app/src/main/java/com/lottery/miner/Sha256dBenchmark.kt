package com.lottery.miner

import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
        val hashesPerSecond: Double
    )

    data class ScalingResult(
        val results: List<WorkerResult>,
        val hardwareSha: Boolean
    ) {
        val best: WorkerResult
            get() = results.maxBy { it.hashesPerSecond }
    }

    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    /** Single-thread reference benchmark retained for callers that need one rate. */
    fun run(durationMillis: Long = 5_000L): Result {
        require(durationMillis in 1_000L..5_000L) { "Benchmark duration must be 1–5 seconds" }
        check(running.compareAndSet(false, true)) { "A benchmark is already running" }
        try {
            val hw = Native.initCore()
            check(hw >= 0) { "Native SHA-256 self-test failed" }
            val hashes = scanForDuration(workers = 1, durationMillis = durationMillis)
            val elapsed = lastElapsedMillis.coerceAtLeast(1L)
            return Result(hashes, elapsed, hashes * 1000.0 / elapsed, hw == 1)
        } finally {
            running.set(false)
        }
    }

    /**
     * Measures 1, 2, 4 and the device's maximum supported worker count sequentially.
     * Each run is short and isolated; this recommends throughput only, not battery
     * efficiency or a sustained thermal-safe setting.
     */
    fun runScaling(maxWorkers: Int, durationMillis: Long = 1_200L): ScalingResult {
        require(maxWorkers in 1..8) { "Worker count must be 1–8" }
        require(durationMillis in 500L..2_000L) { "Each benchmark must be 0.5–2 seconds" }
        check(running.compareAndSet(false, true)) { "A benchmark is already running" }
        try {
            val hw = Native.initCore()
            check(hw >= 0) { "Native SHA-256 self-test failed" }
            val candidates = listOf(1, 2, 4, maxWorkers).filter { it <= maxWorkers }.distinct().sorted()
            val readings = candidates.map { workers ->
                val hashes = scanForDuration(workers, durationMillis)
                val elapsed = lastElapsedMillis.coerceAtLeast(1L)
                WorkerResult(workers, hashes, elapsed, hashes * 1000.0 / elapsed)
            }
            return ScalingResult(readings, hw == 1)
        } finally {
            running.set(false)
        }
    }

    @Volatile private var lastElapsedMillis = 1L

    private fun scanForDuration(workers: Int, durationMillis: Long): Long {
        val header = ByteArray(80)
        for (i in header.indices) header[i] = ((i * 29 + 7) and 0xff).toByte()
        val target = ByteArray(32) // zero target: no practical share can be found
        val hashes = AtomicLong(0L)
        val failure = AtomicReference<Throwable?>(null)
        val startGate = CountDownLatch(1)
        val threads = (0 until workers).map { index ->
            Thread({
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE)
                    val found = IntArray(8)
                    val best = DoubleArray(3)
                    val chunk = 32_768
                    var nonce = index.toLong() * chunk
                    startGate.await()
                    while (System.nanoTime() < deadlineNanos) {
                        val result = Native.scan(header, nonce.toInt(), chunk, target, found, best)
                        check(result in 0..found.size) { "Native benchmark scan failed: $result" }
                        check(best[2] == 0.0) { "Unexpected benchmark target hit/overflow" }
                        hashes.addAndGet(chunk.toLong())
                        nonce += workers.toLong() * chunk
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                }
            }, "sha256d-bench-$index")
        }
        val start = System.nanoTime()
        deadlineNanos = start + durationMillis * 1_000_000L
        threads.forEach { it.start() }
        startGate.countDown()
        threads.forEach { it.join() }
        val elapsedNanos = System.nanoTime() - start
        lastElapsedMillis = (elapsedNanos / 1_000_000L).coerceAtLeast(1L)
        failure.get()?.let { throw IllegalStateException("SHA-256d scaling benchmark failed", it) }
        return hashes.get().also { check(it > 0L) { "Benchmark completed no work" } }
    }

    @Volatile private var deadlineNanos = 0L
}
