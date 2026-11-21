package com.lottery.miner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinerUtilTest {
    @Test fun validatesLegacyMainnetAndWorkerSuffix() {
        assertTrue(Util.isValidBitcoinAddress("1BoatSLRHtKNngkdXEeobR76b53LETtpyT"))
        assertTrue(Util.isValidBitcoinAddress("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy.worker_1"))
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

    @Test fun estimatesPoolFeedbackShareIntervalFromDiffAndHashrate() {
        val seconds = Util.expectedShareIntervalSeconds(10_000.0, 1_550_000.0)
        assertEquals(27_709_466.4, seconds, 1.0)
        assertEquals(320.7, seconds / 86_400.0, 0.1)
    }

    @Test fun rejectsInvalidShareIntervalInputs() {
        assertFalse(runCatching { Util.expectedShareIntervalSeconds(0.0, 1_000.0) }.isSuccess)
        assertFalse(runCatching { Util.expectedShareIntervalSeconds(1.0, 0.0) }.isSuccess)
        assertFalse(runCatching { Util.expectedShareIntervalSeconds(Double.NaN, 1_000.0) }.isSuccess)
    }

}
