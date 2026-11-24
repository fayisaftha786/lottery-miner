package com.lottery.miner

import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

/** Runs a bounded real native SHA-256d scan against an impossible zero target; submits nothing. */
object Sha256dBenchmark {
    data class Result(val hashes: Long, val elapsedMillis: Long, val hashesPerSecond: Double, val hardwareSha: Boolean)

    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    fun run(durationMillis: Long = 5_000L): Result {
        require(durationMillis in 1_000L..5_000L) { "Benchmark duration must be 1–5 seconds" }
        check(running.compareAndSet(false, true)) { "A benchmark is already running" }
        val threadId = Process.myTid()
        val originalPriority = Process.getThreadPriority(threadId)
        try {
            // Keep the benchmark below foreground mining workers in scheduler priority.
            // Restore the caller's priority in finally so this worker thread does not
            // retain a changed scheduler priority if the executor later reuses it.
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
            try {
                Process.setThreadPriority(originalPriority)
            } catch (_: SecurityException) {
                // The benchmark result remains valid; Android may deny restoration.
            } finally {
                running.set(false)
            }
        }
    }
}
