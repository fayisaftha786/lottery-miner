package com.lottery.miner

import org.junit.Assert.*
import org.junit.Test

class BitcoinP2pBlockRelayTest {
    @Test fun encodesAndParsesBitcoinBlockInventory() {
        val hash = ByteArray(32) { it.toByte() }
        val encoded = BitcoinP2pBlockRelay.buildInventoryPayload(
            listOf(BitcoinP2pBlockRelay.Inventory(BitcoinP2pBlockRelay.MSG_BLOCK, hash))
        )
        assertEquals(37, encoded.size)
        assertEquals(1, encoded[0].toInt())
        assertEquals(2, encoded[1].toInt())
        assertArrayEquals(hash, encoded.copyOfRange(5, 37))
        val decoded = BitcoinP2pBlockRelay.parseInventoryPayload(encoded)
        assertEquals(1, decoded.size)
        assertEquals(BitcoinP2pBlockRelay.MSG_BLOCK, decoded.single().type)
        assertArrayEquals(hash, decoded.single().hashInternal)
    }

    @Test fun acceptsWitnessBlockInventoryType() {
        val hash = ByteArray(32) { (it * 7).toByte() }
        val payload = BitcoinP2pBlockRelay.buildInventoryPayload(
            listOf(BitcoinP2pBlockRelay.Inventory(BitcoinP2pBlockRelay.MSG_WITNESS_BLOCK, hash))
        )
        assertEquals(BitcoinP2pBlockRelay.MSG_WITNESS_BLOCK,
            BitcoinP2pBlockRelay.parseInventoryPayload(payload).single().type)
    }

    @Test fun rejectsTruncatedInventoryVectors() {
        assertThrows(IllegalArgumentException::class.java) {
            BitcoinP2pBlockRelay.parseInventoryPayload(byteArrayOf(1, 2, 0))
        }
    }
}
