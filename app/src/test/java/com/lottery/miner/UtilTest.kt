package com.lottery.miner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilTest {
    @Test fun difficultyOneProducesPositiveTarget() {
        val target = Util.difficultyToTarget(1.0)
        assertTrue(target.any { it.toInt() != 0 })
    }

    @Test fun resetRestoresDefaultTargetAndDiscardsPendingDifficulty() {
        val targets = PoolDifficultyTargets(initialDifficulty = 2.0)
        targets.stage(4.0)
        assertArrayEquals(Util.difficultyToTarget(2.0), targets.activeTarget())
        assertArrayEquals(Util.difficultyToTarget(4.0), targets.activateForNextJob())

        targets.reset()
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
