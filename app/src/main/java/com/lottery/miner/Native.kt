package com.lottery.miner

object Native {
    init {
        System.loadLibrary("minercore")
    }

    /** Returns 1 if ARM hardware SHA-256 is used, 0 for software, -1 if the built-in self-test failed. */
    external fun initCore(): Int

    /**
     * Scans nonces [start, start+count). Returns number of shares found (nonces in [found]).
     * best[0] = best share difficulty, best[1] = its nonce, best[2] = extra hits beyond found buffer.
     */
    external fun scan(
        header: ByteArray, start: Int, count: Int, target: ByteArray,
        found: IntArray, best: DoubleArray
    ): Int

    /** Double SHA-256 of an 80 byte header (digest in internal byte order). */
    external fun sha256dHeader(header: ByteArray): ByteArray
}
