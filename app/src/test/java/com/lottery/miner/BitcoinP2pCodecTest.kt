package com.lottery.miner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BitcoinP2pCodecTest {
    @Test fun encodesAndDecodesMainnetPingFrame() {
        val payload = ByteArray(8) { it.toByte() }
        val decoded = BitcoinP2pCodec.decode(BitcoinP2pCodec.encode("ping", payload))
        assertEquals("ping", decoded.command)
        assertArrayEquals(payload, decoded.payload)
    }
    @Test fun rejectsWrongNetworkMagic() {
        val frame = BitcoinP2pCodec.encode("verack", byteArrayOf()); frame[0] = 0
        assertThrows(IllegalArgumentException::class.java) { BitcoinP2pCodec.decode(frame) }
    }
    @Test fun rejectsChecksumTampering() {
        val frame = BitcoinP2pCodec.encode("ping", ByteArray(8)); frame[24] = 1
        assertThrows(IllegalArgumentException::class.java) { BitcoinP2pCodec.decode(frame) }
    }
    @Test fun rejectsTrailingBytesAndOversizedPayloadDeclaration() {
        val trailing = BitcoinP2pCodec.encode("verack", byteArrayOf()) + byteArrayOf(1)
        assertThrows(IllegalArgumentException::class.java) { BitcoinP2pCodec.decode(trailing) }
        val oversized = BitcoinP2pCodec.encode("verack", byteArrayOf())
        oversized[16] = 1; oversized[17] = 0; oversized[18] = 0x40; oversized[19] = 0
        assertThrows(IllegalArgumentException::class.java) { BitcoinP2pCodec.decode(oversized) }
    }
    @Test fun compactSizeUsesCanonicalBoundaries() {
        val values = listOf(0L, 252L, 253L, 65535L, 65536L, 0xffffffffL, 0x1_0000_0000L)
        values.forEach { value ->
            val encoded = BitcoinP2pCodec.encodeCompactSize(value)
            val (decoded, consumed) = BitcoinP2pCodec.decodeCompactSize(encoded)
            assertEquals(value, decoded); assertEquals(encoded.size, consumed)
        }
    }
    @Test fun rejectsNonCanonicalCompactSize() {
        assertThrows(IllegalArgumentException::class.java) {
            BitcoinP2pCodec.decodeCompactSize(byteArrayOf(253.toByte(), 1, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BitcoinP2pCodec.decodeCompactSize(byteArrayOf(254.toByte(), 1, 0, 0, 0))
        }
    }
}
