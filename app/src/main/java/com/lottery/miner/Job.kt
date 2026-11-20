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

    /** Builds the 80 byte block header (nonce = 0) for the given extranonce1/2. */
    fun buildHeader(en1: ByteArray, en2: ByteArray): ByteArray {
        val cb = ByteArray(coinb1.size + en1.size + en2.size + coinb2.size)
        var o = 0
        for (part in arrayOf(coinb1, en1, en2, coinb2)) {
            System.arraycopy(part, 0, cb, o, part.size)
            o += part.size
        }
        var h = Util.sha256d(cb)
        for (b in merkle) h = Util.sha256d(h + b)

        val header = ByteArray(80)
        copyReversed(Util.hexToBytes(versionHex), header, 0)
        System.arraycopy(prevInternal, 0, header, 4, 32)
        // Bitcoin serializes the merkle root as a little-endian uint256 in the block header.
        copyReversed(h, header, 36)
        copyReversed(Util.hexToBytes(ntimeHex), header, 68)
        copyReversed(Util.hexToBytes(nbitsHex), header, 72)
        return header
    }
}
