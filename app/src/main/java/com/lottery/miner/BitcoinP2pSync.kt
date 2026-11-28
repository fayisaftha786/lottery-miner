package com.lottery.miner

import java.io.ByteArrayOutputStream

/** Bounded Bitcoin P2P getheaders/headers primitives. Hashes use wire/internal byte order. */
object BitcoinP2pSync {
    const val MAX_HEADERS_PER_MESSAGE = 2_000
    const val MSG_BLOCK = 2
    const val PROTOCOL_VERSION = 70_016

    fun buildGetHeaders(locatorHashesInternal: List<ByteArray>, stopHashInternal: ByteArray = ByteArray(32)): ByteArray {
        require(locatorHashesInternal.isNotEmpty() && locatorHashesInternal.size <= 101) {
            "header locator must contain 1..101 hashes"
        }
        require(locatorHashesInternal.all { it.size == 32 }) { "locator hashes must be 32 bytes" }
        require(stopHashInternal.size == 32) { "stop hash must be 32 bytes" }
        val out = ByteArrayOutputStream(4 + 9 + locatorHashesInternal.size * 32 + 32)
        writeUint32LE(out, PROTOCOL_VERSION.toLong())
        out.write(BitcoinP2pCodec.encodeCompactSize(locatorHashesInternal.size.toLong()))
        locatorHashesInternal.forEach { out.write(it) }
        out.write(stopHashInternal)
        return out.toByteArray()
    }

    /**
     * Checks message bounds, each header's encoded PoW, and the required zero
     * transaction-count field. This does NOT validate linkage, expected nBits,
     * MTP, deployment state, chainwork selection, or reorgs.
     */
    fun parseHeaders(payload: ByteArray, verifyProofOfWork: Boolean = true): List<ByteArray> {
        val (count, countBytes) = BitcoinP2pCodec.decodeCompactSize(payload, 0)
        require(count <= MAX_HEADERS_PER_MESSAGE.toLong()) { "too many headers in one message" }
        var offset = countBytes
        val headers = ArrayList<ByteArray>(count.toInt())
        repeat(count.toInt()) {
            require(offset <= payload.size - 80) { "truncated block header in headers message" }
            val header = payload.copyOfRange(offset, offset + 80)
            if (verifyProofOfWork) BitcoinHeader.parseAndCheck(header)
            offset += 80
            val (txCount, txCountBytes) = BitcoinP2pCodec.decodeCompactSize(payload, offset)
            require(txCount == 0L) { "headers message entries must have zero transaction count" }
            offset += txCountBytes
            headers.add(header)
        }
        require(offset == payload.size) { "trailing bytes in headers message" }
        return headers
    }

    fun buildGetDataForBlock(blockHashInternal: ByteArray): ByteArray {
        require(blockHashInternal.size == 32) { "block hash must be 32 bytes" }
        val out = ByteArrayOutputStream(37)
        out.write(1) // one inventory vector
        writeUint32LE(out, MSG_BLOCK.toLong())
        out.write(blockHashInternal)
        return out.toByteArray()
    }

    private fun writeUint32LE(out: ByteArrayOutputStream, value: Long) {
        require(value in 0..0xffffffffL)
        repeat(4) { out.write((value ushr (8 * it)).toInt() and 0xff) }
    }
}
