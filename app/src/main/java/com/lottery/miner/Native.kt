package com.lottery.miner

object Native {
    init {
        System.loadLibrary("minercore")
    }

    /** Returns 1 if ARM hardware SHA-256 is used, 0 for software, -1 if the built-in self-test failed. */
    external fun initCore(): Int

    /** True only when the paired ARM SHA2 scan passed its startup equivalence tests. */
    external fun usesArmPairScan(): Boolean

    /** True only when the experimental four-stream scanner passed equivalence and device benchmark gates. */
    external fun usesArmQuadScan(): Boolean

    /**
     * Scans nonces [start, start+count). Returns hashes at or below the supplied scan target (nonces in [found]); Kotlin classifies each hit as a pool share and/or network block candidate.
     * best[0] = best share difficulty, best[1] = its nonce, best[2] = extra hits beyond found buffer.
     */
    external fun scan(
        header: ByteArray, start: Int, count: Int, target: ByteArray,
        found: IntArray, best: DoubleArray
    ): Int

    /** Double SHA-256 of an 80 byte header (digest in internal byte order). */
    external fun sha256dHeader(header: ByteArray): ByteArray
}
