package com.lottery.miner

import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

/** Runs a bounded real native SHA-256d scan against an impossible zero target; submits nothing. */
object Sha256dBenchmark {
    data class Result(val hashes: Long, val elapsedMillis: Long, val hashesPerSecond: Double, val hardwareSha: Boolean)

    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()


    data class DualComparison(
        val singleMedianHps: Double,
        val dualMedianHps: Double,
        val speedupPercent: Double,
        val rounds: Int,
        val roundDurationMillis: Long,
        val hardwareSha: Boolean,
        val dualSelfTestPassed: Boolean
    )

    /**
     * A/B benchmark of the existing single-stream scanner and experimental
     * dual-stream scanner. Mining must be stopped. Each round hashes the same
     * deterministic header against an impossible zero target and submits nothing.
     * Order alternates to reduce warm-cache/order bias; this is not a sustained
     * thermal test and must not be treated as production hashrate.
     */
    fun compareDual(roundDurationMillis: Long = 1_000L): DualComparison {
        require(roundDurationMillis in 500L..2_000L) { "A/B round must be 0.5–2 seconds" }
        check(running.compareAndSet(false, true)) { "A benchmark is already running" }
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE)
            val hw = Native.initCore()
            check(hw >= 0) { "Native SHA-256 self-test failed" }
            check(Native.dualSelfTestOk()) {
                "Dual-lane SHA-256 self-test failed or ARM SHA2 hardware is unavailable"
            }

            val header = ByteArray(80) { i -> ((i * 29 + 7) and 0xff).toByte() }
            val target = ByteArray(32)
            val found = IntArray(8)
            val best = DoubleArray(3)

            fun measure(dual: Boolean, durationMillis: Long): Double {
                var nonce = 0
                var hashes = 0L
                val start = System.nanoTime()
                val deadline = start + durationMillis * 1_000_000L
                while (System.nanoTime() < deadline) {
                    val count = 32_768
                    val result = if (dual) {
                        Native.scanDual(header, nonce, count, target, found, best)
                    } else {
                        Native.scan(header, nonce, count, target, found, best)
                    }
                    check(result >= 0 && result <= found.size) { "Scanner failed: $result" }
                    check(best[2] == 0.0) { "Unexpected zero-target hit/overflow" }
                    hashes += count.toLong()
                    if (nonce >= Int.MAX_VALUE - count) nonce = 0 else nonce += count
                }
                val elapsedNanos = (System.nanoTime() - start).coerceAtLeast(1L)
                check(hashes > 0L) { "Benchmark completed no hashes" }
                return hashes * 1_000_000_000.0 / elapsedNanos.toDouble()
            }

            // Equal warm-up prevents the first measured engine from owning all cold-start cost.
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
                dualSelfTestPassed = true
            )
        } finally {
            running.set(false)
        }
    }

    fun run(durationMillis: Long = 5_000L): Result {
        require(durationMillis in 1_000L..5_000L) { "Benchmark duration must be 1–5 seconds" }
        check(running.compareAndSet(false, true)) { "A benchmark is already running" }
        try {
            // Keep the benchmark below foreground mining workers in scheduler priority.
            Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE)
            val hw = Native.initCore()
            check(hw >= 0) { "Native SHA-256 self-test failed" }

            // Deterministic synthetic header. Zero target means no practical hit;
            // this test never opens a pool connection or submits a wallet share.
            val header = ByteArray(80)
            for (i in header.indices) header[i] = ((i * 29 + 7) and 0xff).toByte()
            val target = ByteArray(32)
            val found = IntArray(8)
            val best = DoubleArray(3)
            val chunk = 32_768
            var nonce = 0
            var hashes = 0L
            val start = System.nanoTime()
            val deadline = start + durationMillis * 1_000_000L

            while (System.nanoTime() < deadline) {
                val count = minOf(chunk, Int.MAX_VALUE - nonce)
                if (count <= 0) break
                val result = Native.scan(header, nonce, count, target, found, best)
                check(result >= 0 && result <= found.size) { "Native benchmark scan failed: $result" }
                check(best[2] == 0.0) { "Unexpected benchmark target hit/overflow" }
                hashes += count.toLong()
                nonce += count
                if (nonce > Int.MAX_VALUE - chunk) nonce = 0
            }
            val elapsed = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
            check(hashes > 0L) { "Benchmark did not complete any work" }
            return Result(
                hashes = hashes,
                elapsedMillis = elapsed,
                hashesPerSecond = hashes * 1000.0 / elapsed,
                hardwareSha = hw == 1
            )
        } finally {
            running.set(false)
        }
    }
}
