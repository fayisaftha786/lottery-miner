package com.lottery.miner

/** Runs real native SHA-256d nonce scans against an impossible zero target; submits nothing. */
object Sha256dBenchmark {
    data class Result(val hashes: Long, val elapsedMillis: Long, val hashesPerSecond: Double, val hardwareSha: Boolean)

    fun run(durationMillis: Long = 5_000L): Result {
        require(durationMillis in 1_000L..30_000L)
        val hw = Native.initCore()
        check(hw >= 0) { "Native SHA-256 self-test failed" }

        // A deterministic synthetic 80-byte header. Target zero means no share can
        // realistically be found; this benchmark never contacts a pool or wallet.
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
            hashes += count.toLong()
            nonce += count
            if (nonce > Int.MAX_VALUE - chunk) nonce = 0
        }
        val elapsed = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
        return Result(
            hashes = hashes,
            elapsedMillis = elapsed,
            hashesPerSecond = hashes * 1000.0 / elapsed,
            hardwareSha = hw == 1
        )
    }
}
