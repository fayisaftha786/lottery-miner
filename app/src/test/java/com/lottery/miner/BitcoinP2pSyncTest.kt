package com.lottery.miner

import org.junit.Assert.*
import org.junit.Test

class BitcoinP2pSyncTest {
    private val genesisHeader = hex(
        "01000000" + "00".repeat(32) +
            "3ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a" +
            "29ab5f49ffff001d1dac2b7c"
    )

    @Test fun getHeadersSerializesVersionLocatorAndStopHash() {
        val locator = ByteArray(32) { it.toByte() }
        val stop = ByteArray(32) { (255 - it).toByte() }
        val payload = BitcoinP2pSync.buildGetHeaders(listOf(locator), stop)
        assertEquals(69, payload.size)
        assertEquals(0x70, payload[0].toInt() and 0xff)
        assertEquals(0x11, payload[1].toInt() and 0xff)
        assertEquals(1, payload[4].toInt() and 0xff)
        assertArrayEquals(locator, payload.copyOfRange(5, 37))
        assertArrayEquals(stop, payload.copyOfRange(37, 69))
    }

    @Test fun parsesGenesisHeaderWithZeroTransactionCount() {
        val payload = byteArrayOf(1) + genesisHeader + byteArrayOf(0)
        val headers = BitcoinP2pSync.parseHeaders(payload)
        assertEquals(1, headers.size)
        assertArrayEquals(genesisHeader, headers.single())
    }

    @Test fun acceptsEmptyHeadersResponse() {
        assertTrue(BitcoinP2pSync.parseHeaders(byteArrayOf(0)).isEmpty())
    }

    @Test fun rejectsHeaderEntriesWithTransactionsOrTrailingBytes() {
        assertThrows(IllegalArgumentException::class.java) {
            BitcoinP2pSync.parseHeaders(byteArrayOf(1) + genesisHeader + byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BitcoinP2pSync.parseHeaders(byteArrayOf(0, 0))
        }
    }

    @Test fun rejectsMoreThanTwoThousandHeadersBeforeAllocating() {
        assertThrows(IllegalArgumentException::class.java) {
            BitcoinP2pSync.parseHeaders(byteArrayOf(0xfd.toByte(), 0xd1.toByte(), 0x07))
        }
    }

    @Test fun blockGetDataUsesInventoryTypeTwo() {
        val hash = ByteArray(32) { it.toByte() }
        val payload = BitcoinP2pSync.buildGetDataForBlock(hash)
        assertEquals(37, payload.size)
        assertEquals(1, payload[0].toInt())
        assertEquals(2, payload[1].toInt())
        assertEquals(0, payload[2].toInt())
        assertArrayEquals(hash, payload.copyOfRange(5, 37))
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
