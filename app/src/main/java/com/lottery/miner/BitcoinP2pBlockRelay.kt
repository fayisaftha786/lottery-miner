package com.lottery.miner

import java.io.ByteArrayOutputStream

/**
 * Bitcoin inventory announcements and requested block serving.
 * An inv write is not block acceptance; the complete block is sent only after getdata.
 */
object BitcoinP2pBlockRelay {
    const val MSG_BLOCK = 2L
    const val MSG_WITNESS_BLOCK = 0x40000002L

    data class Inventory(val type: Long, val hashInternal: ByteArray)
    data class Result(
        val blockHash: String,
        val attemptedPeers: Int,
        val announcedPeers: Int,
        val failedPeers: List<Int>
    )

    fun announce(found: EightLaneSha256dScanner.FoundBlock, peers: List<BitcoinP2pPeer>): Result {
        require(peers.isNotEmpty()) { "at least one connected Mainnet peer is required" }
        val parsed = validateCandidateBlock(found.blockBytes)
        val payload = buildInventoryPayload(listOf(Inventory(MSG_BLOCK, parsed.hashInternal)))
        var announced = 0
        val failed = ArrayList<Int>()
        peers.forEachIndexed { index, peer ->
            try {
                peer.send("inv", payload)
                announced++
            } catch (_: Exception) {
                failed.add(index)
            }
        }
        return Result(parsed.hashDisplay, peers.size, announced, failed)
    }

    fun serveGetData(peer: BitcoinP2pPeer, payload: ByteArray, blockBytes: ByteArray): Boolean {
        val header = validateCandidateBlock(blockBytes)
        val requested = parseInventoryPayload(payload)
        val match = requested.any {
            (it.type == MSG_BLOCK || it.type == MSG_WITNESS_BLOCK) &&
                it.hashInternal.contentEquals(header.hashInternal)
        }
        if (match) peer.send("block", blockBytes)
        return match
    }

    fun buildInventoryPayload(items: List<Inventory>): ByteArray {
        require(items.size <= 50_000) { "too many inventory items" }
        require(items.all { it.type in 0..0xffffffffL && it.hashInternal.size == 32 }) {
            "invalid inventory vector"
        }
        val out = ByteArrayOutputStream(9 + items.size * 36)
        out.write(BitcoinP2pCodec.encodeCompactSize(items.size.toLong()))
        items.forEach {
            writeUint32LE(out, it.type)
            out.write(it.hashInternal)
        }
        return out.toByteArray()
    }

    fun parseInventoryPayload(payload: ByteArray): List<Inventory> {
        val (count, countBytes) = BitcoinP2pCodec.decodeCompactSize(payload)
        require(count <= 50_000) { "invalid inventory count" }
        val required = count * 36L
        require(required <= payload.size.toLong() - countBytes) { "truncated inventory payload" }
        require(countBytes + required == payload.size.toLong()) { "trailing inventory bytes" }
        var offset = countBytes
        val result = ArrayList<Inventory>(count.toInt())
        repeat(count.toInt()) {
            val type = readUint32LE(payload, offset)
            offset += 4
            result.add(Inventory(type, payload.copyOfRange(offset, offset + 32)))
            offset += 32
        }
        return result
    }

    private fun validateCandidateBlock(block: ByteArray): BitcoinHeader.Parsed {
        require(block.size in 81..4_000_000) { "serialized block is outside Mainnet size bound" }
        val header = BitcoinHeader.parseAndCheck(block.copyOfRange(0, 80))
        val (transactionCount, countBytes) = BitcoinP2pCodec.decodeCompactSize(block, 80)
        require(transactionCount == 1L) { "relay path accepts coinbase-only blocks only" }
        require(block.size > 80 + countBytes) { "coinbase transaction is missing" }
        return header
    }

    private fun writeUint32LE(out: ByteArrayOutputStream, value: Long) {
        repeat(4) { out.write((value ushr (8 * it)).toInt() and 0xff) }
    }

    private fun readUint32LE(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset <= bytes.size - 4)
        var value = 0L
        repeat(4) { value = value or ((bytes[offset + it].toLong() and 0xffL) shl (8 * it)) }
        return value
    }
}
