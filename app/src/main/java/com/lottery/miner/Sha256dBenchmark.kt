package com.lottery.miner

import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

/** Measures the real SHA256d scanner against a real authorized pool job. Never submits shares. */
object Sha256dBenchmark {
    data class Result(
        val hashes: Long,
        val elapsedMillis: Long,
        val hashesPerSecond: Double,
        val hardwareSha: Boolean,
        val poolHost: String,
        val poolPort: Int,
        val jobId: String,
        val poolDifficulty: Double,
        val workFetchedAtMillis: Long
    )

    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    data class DualComparison(
        val singleMedianHps: Double,
        val dualMedianHps: Double,
        val speedupPercent: Double,
        val rounds: Int,
        val roundDurationMillis: Long,
        val hardwareSha: Boolean,
        val dualSelfTestPassed: Boolean,
        val poolHost: String,
        val poolPort: Int,
        val jobId: String,
        val poolDifficulty: Double,
        val workFetchedAtMillis: Long
    )

    private const val CHUNK_SIZE = 32_768

    private fun validateWork(work: PoolBenchmarkWork) {
        require(work.header.size == 80) { "Pool job header must be exactly 80 bytes" }
        require(work.shareTarget.size == 32) { "Pool share target must be exactly 32 bytes" }
        require(work.jobId.isNotBlank()) { "Pool job id is missing" }
        require(work.poolDifficulty.isFinite() && work.poolDifficulty > 0.0) { "Pool difficulty is invalid" }
        require(work.poolHost.isNotBlank() && work.poolPort in 1..65535) { "Pool endpoint is invalid" }
        require(work.fetchedAtMillis > 0L) { "Pool work timestamp is invalid" }
    }

    /**
     * Hashes actual 80-byte block-header work derived from an authorized Stratum
     * mining.notify, using its current extranonce and share target. Found shares
     * are deliberately discarded: this is a diagnostic benchmark and never
     * issues mining.submit. It must not be presented as a pool share/payout.
     */
    internal fun run(work: PoolBenchmarkWork, durationMillis: Long = 5_000L): Result {
        require(durationMillis in 1_000L..5_000L) { "Benchmark duration must be 1–5 seconds" }
        validateWork(work)
        check(running.compareAndSet(false, true)) { "A benchmark is already running" }
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE)
            val hw = Native.initCore()
            check(hw >= 0) { "Native SHA-256 self-test failed" }

            val header = work.header.copyOf()
            val target = work.shareTarget.copyOf()
            val found = IntArray(8)
            val best = DoubleArray(3)
            var nonce = 0L
            var hashes = 0L
            val start = System.nanoTime()
            val deadline = start + durationMillis * 1_000_000L

            while (System.nanoTime() < deadline) {
                val count = minOf(CHUNK_SIZE.toLong(), 0x1_0000_0000L - nonce).toInt()
                check(count > 0) { "Nonce range exhausted unexpectedly" }
                val scanned = Native.scan(header, nonce.toInt(), count, target, found, best)
                check(scanned >= 0 && scanned <= found.size) { "Native benchmark scan failed: $scanned" }
                // Target hits/overflow are real results but are intentionally not submitted.
                hashes += count.toLong()
                nonce += count.toLong()
                if (nonce >= 0x1_0000_0000L) nonce = 0L
            }

            val elapsed = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
            check(hashes > 0L) { "Benchmark completed no hashes" }
            return Result(
                hashes = hashes,
                elapsedMillis = elapsed,
                hashesPerSecond = hashes * 1000.0 / elapsed,
                hardwareSha = hw == 1,
                poolHost = work.poolHost,
                poolPort = work.poolPort,
                jobId = work.jobId,
                poolDifficulty = work.poolDifficulty,
                workFetchedAtMillis = work.fetchedAtMillis
            )
        } finally {
            running.set(false)
        }
    }

    /**
     * Short alternating A/B comparison using the exact same real pool job and
     * share target for both scanners. It is not a sustained thermal or power test.
     */
    internal fun compareDual(work: PoolBenchmarkWork, roundDurationMillis: Long = 1_000L): DualComparison {
        require(roundDurationMillis in 500L..2_000L) { "A/B round must be 0.5–2 seconds" }
        validateWork(work)
        check(running.compareAndSet(false, true)) { "A benchmark is already running" }
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE)
            val hw = Native.initCore()
            check(hw >= 0) { "Native SHA-256 self-test failed" }
            check(Native.dualSelfTestOk()) {
                "Dual-lane SHA-256 self-test failed or ARM SHA2 hardware is unavailable"
            }

            val header = work.header.copyOf()
            val target = work.shareTarget.copyOf()
            val found = IntArray(8)
            val best = DoubleArray(3)

            fun measure(dual: Boolean, durationMillis: Long): Double {
                var nonce = 0L
                var hashes = 0L
                val start = System.nanoTime()
                val deadline = start + durationMillis * 1_000_000L
                while (System.nanoTime() < deadline) {
                    val count = minOf(CHUNK_SIZE.toLong(), 0x1_0000_0000L - nonce).toInt()
                    check(count > 0) { "Nonce range exhausted unexpectedly" }
                    val scanned = if (dual) {
                        Native.scanDual(header, nonce.toInt(), count, target, found, best)
                    } else {
                        Native.scan(header, nonce.toInt(), count, target, found, best)
                    }
                    check(scanned >= 0 && scanned <= found.size) { "Scanner failed: $scanned" }
                    // Both scanners receive the same share target. Any found shares are discarded.
                    hashes += count.toLong()
                    nonce += count.toLong()
                    if (nonce >= 0x1_0000_0000L) nonce = 0L
                }
                val elapsedNanos = (System.nanoTime() - start).coerceAtLeast(1L)
                check(hashes > 0L) { "Benchmark completed no hashes" }
                return hashes * 1_000_000_000.0 / elapsedNanos.toDouble()
            }

            measure(false, 250L)
            measure(true, 250L)
            val single = ArrayList<Double>(3)
            val dual = ArrayList<Double>(3)
            repeat(3) { round ->
                if (round % 2 == 0) {
                    single.add(measure(false, roundDurationMillis))
                    dual.add(measure(true, roundDurationMillis))
                } else {
                    dual.add(measure(true, roundDurationMillis))
                    single.add(measure(false, roundDurationMillis))
                }
            }
            fun median(values: List<Double>): Double = values.sorted()[values.size / 2]
            val singleMedian = median(single)
            val dualMedian = median(dual)
            return DualComparison(
                singleMedianHps = singleMedian,
                dualMedianHps = dualMedian,
                speedupPercent = (dualMedian / singleMedian - 1.0) * 100.0,
                rounds = 3,
                roundDurationMillis = roundDurationMillis,
                hardwareSha = hw == 1,
                dualSelfTestPassed = true,
                poolHost = work.poolHost,
                poolPort = work.poolPort,
                jobId = work.jobId,
                poolDifficulty = work.poolDifficulty,
                workFetchedAtMillis = work.fetchedAtMillis
            )
        } finally {
            running.set(false)
        }
    }
}
