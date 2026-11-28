package com.lottery.miner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinerUtilTest {
    @Test fun validatesOnlyPlainLegacyMainnetPayoutAddresses() {
        assertTrue(Util.isValidBitcoinAddress("1BoatSLRHtKNngkdXEeobR76b53LETtpyT"))
        assertFalse(Util.isValidBitcoinAddress("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy.worker_1"))
        assertFalse(Util.isValidBitcoinAddress("1BoatSLRHtKNngkdXEeobR76b53LETtpyU"))
        assertFalse(Util.isValidBitcoinAddress("mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn"))
    }

    @Test fun rejectsMalformedSegwitAddresses() {
        assertFalse(Util.isValidBitcoinAddress("bc1not-a-valid-address"))
        assertFalse(Util.isValidBitcoinAddress("BC1qmixedCase"))
    }

    @Test fun validatesCompactTargetAndRejectsMalformedValues() {
        assertEquals(Util.DIFF1, Util.nbitsToTarget("1d00ffff"))
        assertTrue(Util.networkDifficulty("1d00ffff") >= 1.0)
        assertFalse(runCatching { Util.nbitsToTarget("1d80ffff") }.isSuccess)
        assertFalse(runCatching { Util.nbitsToTarget("00000000") }.isSuccess)
    }

    @Test fun hexConversionRejectsInvalidCharacters() {
        assertArrayEquals(byteArrayOf(0, 15, -1), Util.hexToBytes("000fff"))
        assertEquals("000fff", Util.bytesToHex(byteArrayOf(0, 15, -1)))
        assertFalse(runCatching { Util.hexToBytes("0g") }.isSuccess)
    }
    @Test fun rejectsCompactTargetsAboveBitcoinMainnetProofOfWorkLimit() {
        assertFalse(runCatching { Util.nbitsToTarget("1e00ffff") }.isSuccess)
    }

    @Test fun rejectsDifficultyTooLargeToRepresentAsNonZeroTarget() {
        assertFalse(runCatching { Util.difficultyToTarget(Double.MAX_VALUE) }.isSuccess)
    }



}
