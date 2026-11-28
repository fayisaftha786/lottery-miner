package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BitcoinHeaderTest {
    @Test fun validatesBitcoinGenesisHeaderAndHash() {
        val hex = "01000000" + "00".repeat(32) +
            "3ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a" +
            "29ab5f49ffff001d1dac2b7c"
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val parsed = BitcoinHeader.parseAndCheck(bytes)
        assertEquals("000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f", parsed.hashDisplay)
        assertEquals(0x1d00ffffL, parsed.bits)
    }
    @Test fun rejectsHeaderWithInvalidProofOfWork() {
        val header = ByteArray(80)
        header[0] = 1
        header[72] = 0xff.toByte(); header[73] = 0xff.toByte()
        header[75] = 0x1d
        assertThrows(IllegalArgumentException::class.java) { BitcoinHeader.parseAndCheck(header) }
    }
    @Test fun compactTargetRoundTripsMainnetPowLimit() {
        val target = BitcoinHeader.compactToTarget(0x1d00ffffL)
        assertEquals(0x1d00ffffL, BitcoinHeader.targetToCompact(target))
        assertEquals("ffff0000000000000000000000000000000000000000000000000000", target.toString(16))
    }
    @Test fun retargetClampsTimespanAndPreservesTargetAtTwoWeeks() {
        assertEquals(0x1d00ffffL, BitcoinHeader.calculateRetarget(0x1d00ffffL, 1_209_600L))
        assertEquals(
            BitcoinHeader.calculateRetarget(0x1d00ffffL, 302_400L),
            BitcoinHeader.calculateRetarget(0x1d00ffffL, 1L)
        )
    }
    @Test fun rejectsNegativeAndZeroCompactTargets() {
        assertThrows(IllegalArgumentException::class.java) { BitcoinHeader.compactToTarget(0x1d80ffffL) }
        assertThrows(IllegalArgumentException::class.java) { BitcoinHeader.compactToTarget(0x1d000000L) }
    }
}
