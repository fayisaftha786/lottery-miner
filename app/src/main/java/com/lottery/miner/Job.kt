package com.lottery.miner

/** One mining.notify job from the pool. */
class Job(
    val id: String,
    val prevHashHex: String,
    val coinb1: ByteArray,
    val coinb2: ByteArray,
    val merkle: List<ByteArray>,
    val versionHex: String,
    val nbitsHex: String,
    val ntimeHex: String,
    val clean: Boolean
) {
    init {
        require(id.isNotBlank() && id.length <= 128) { "invalid job id" }
        require(prevHashHex.matches(Regex("[0-9a-fA-F]{64}"))) { "prevhash must be 32-byte hex" }
        require(versionHex.matches(Regex("[0-9a-fA-F]{8}"))) { "version must be 4-byte hex" }
        require(nbitsHex.matches(Regex("[0-9a-fA-F]{8}"))) { "nbits must be 4-byte hex" }
        // Reject malformed compact targets while parsing the Stratum job, not later in a worker thread.
        // This turns invalid pool work into a protocol failure and preserves worker health.
        Util.nbitsToTarget(nbitsHex)
        require(ntimeHex.matches(Regex("[0-9a-fA-F]{8}"))) { "ntime must be 4-byte hex" }
        require(coinb1.size <= 100000 && coinb2.size <= 100000) { "coinbase fragment too large" }
        require(merkle.size <= 32 && merkle.all { it.size == 32 }) { "invalid merkle branches" }
    }

    /** Stratum sends prevhash with every 4-byte word byte-reversed; the header wants the internal order. */
    private val prevInternal: ByteArray = run {
        val p = Util.hexToBytes(prevHashHex)
        val out = ByteArray(32)
        for (w in 0 until 8) for (b in 0 until 4) out[w * 4 + b] = p[w * 4 + 3 - b]
        out
    }

    private fun copyReversed(src: ByteArray, dst: ByteArray, off: Int) {
        for (i in src.indices) dst[off + i] = src[src.size - 1 - i]
    }

    /**
     * Builds the 80-byte block header for the given extranonce1/2 and optional
     * BIP310 version_bits. The pool recomputes the submitted version from the
     * original job version and this same version_bits value.
     */
    fun buildHeader(
        en1: ByteArray,
        en2: ByteArray,
        versionBits: Long = 0L,
        versionMask: Long = 0L
    ): ByteArray {
        require(versionBits in 0L..0xffff_ffffL) { "version_bits must be an unsigned 32-bit value" }
        require(versionMask in 0L..0xffff_ffffL) { "version mask must be an unsigned 32-bit value" }
        require((versionBits and versionMask.inv()) == 0L) {
            "version_bits contains bits outside the negotiated version mask"
        }
        val cb = ByteArray(coinb1.size + en1.size + en2.size + coinb2.size)
        var o = 0
        for (part in arrayOf(coinb1, en1, en2, coinb2)) {
            System.arraycopy(part, 0, cb, o, part.size)
            o += part.size
        }
        var h = Util.sha256d(cb)
        for (b in merkle) h = Util.sha256d(h + b)

        val header = ByteArray(80)
        // BIP310 requires: (job_version & ~last_mask) | (version_bits & last_mask).
        // A simple OR is incorrect because it cannot clear a bit that the pool
        // permits the miner to roll from 1 to 0.
        val jobVersion = versionHex.toLong(16)
        val rolledVersion = ((jobVersion and versionMask.inv()) or (versionBits and versionMask))
            .toString(16).padStart(8, '0')
        copyReversed(Util.hexToBytes(rolledVersion), header, 0)
        System.arraycopy(prevInternal, 0, header, 4, 32)
        // SHA256d returns the raw digest bytes, which are already the serialized
        // internal-byte-order uint256 used in a Bitcoin block header. Do not reverse
        // this digest again: the genesis coinbase vector must produce 3ba3edfd... here.
        System.arraycopy(h, 0, header, 36, 32)
        copyReversed(Util.hexToBytes(ntimeHex), header, 68)
        copyReversed(Util.hexToBytes(nbitsHex), header, 72)
        return header
    }
}
