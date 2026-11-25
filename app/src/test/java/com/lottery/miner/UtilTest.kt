package com.lottery.miner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilTest {
    @Test fun candidateScanTargetUsesLooserShareTargetWhenShareIsEasier() {
        val share = Util.toBytes32(Util.DIFF1.divide(java.math.BigInteger.valueOf(2)))
        val network = Util.toBytes32(Util.DIFF1.divide(java.math.BigInteger.valueOf(100)))
        assertArrayEquals(share, Util.candidateScanTarget(share, network))
    }

    @Test fun candidateScanTargetUsesNetworkTargetWhenNetworkIsEasier() {
        val share = Util.toBytes32(Util.DIFF1.divide(java.math.BigInteger.valueOf(100)))
        val network = Util.toBytes32(Util.DIFF1.divide(java.math.BigInteger.valueOf(2)))
        assertArrayEquals(network, Util.candidateScanTarget(share, network))
    }

    @Test fun candidateScanTargetUsesShareTargetWhenTargetsAreEqual() {
        val target = Util.toBytes32(Util.DIFF1.divide(java.math.BigInteger.valueOf(7)))
        assertArrayEquals(target, Util.candidateScanTarget(target, target))
    }

    @Test(expected = IllegalArgumentException::class)
    fun candidateScanTargetRejectsWrongTargetSize() {
        Util.candidateScanTarget(ByteArray(31), ByteArray(32))
    }

    @Test fun candidateScanTargetDoesNotMutateInputs() {
        val share = Util.toBytes32(Util.DIFF1.divide(java.math.BigInteger.valueOf(2)))
        val network = Util.toBytes32(Util.DIFF1.divide(java.math.BigInteger.valueOf(100)))
        val originalShare = share.copyOf()
        val originalNetwork = network.copyOf()
        Util.candidateScanTarget(share, network)
        assertArrayEquals(originalShare, share)
        assertArrayEquals(originalNetwork, network)
    }

    @Test fun difficultyOneProducesPositiveTarget() {
        val target = Util.difficultyToTarget(1.0)
        assertTrue(target.any { it.toInt() != 0 })
    }

    @Test fun stagesDifficultyUntilNextJobAndResetClearsIt() {
        val targets = PoolDifficultyTargets(initialDifficulty = 2.0)
        targets.stage(4.0)
        assertEquals(2.0, targets.activeDifficulty(), 0.0)
        assertArrayEquals(Util.difficultyToTarget(2.0), targets.activeTarget())

        assertArrayEquals(Util.difficultyToTarget(4.0), targets.activateForNextJob())
        assertEquals(4.0, targets.activeDifficulty(), 0.0)

        targets.stage(8.0)
        targets.reset()
        assertEquals(1.0, targets.activeDifficulty(), 0.0)
        assertArrayEquals(Util.difficultyToTarget(1.0), targets.activeTarget())
        assertArrayEquals(Util.difficultyToTarget(1.0), targets.activateForNextJob())
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
