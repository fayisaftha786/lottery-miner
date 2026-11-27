package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilTest {
    @Test fun configuredTrustWalletPayoutAddressIsValidBitcoinMainnetAddress() {
        assertTrue(Util.isValidBitcoinAddress(MinerDefaults.DEFAULT_PAYOUT_ADDRESS))
        assertEquals("bc1qe4xhqarfw9tqaxaad3cx6ayl0r93ehyqf5uses", MinerDefaults.DEFAULT_PAYOUT_ADDRESS)
    }

    @Test fun difficultyOneProducesPositiveTarget() {
        val target = Util.difficultyToTarget(1.0)
        assertTrue(target.any { it.toInt() != 0 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDifficultyThatRoundsTargetToZero() {
        Util.difficultyToTarget(Double.MAX_VALUE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCompactTargetThatRoundsToZero() {
        Util.nbitsToTarget("01000001")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeCompactTarget() {
        Util.nbitsToTarget("1d80ffff")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTargetAboveBitcoinMainnetPowLimit() {
        Util.nbitsToTarget("207fffff")
    }
}
