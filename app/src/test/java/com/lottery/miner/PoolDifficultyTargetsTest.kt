package com.lottery.miner

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PoolDifficultyTargetsTest {
    @Test fun difficultyUpdateDoesNotChangeActiveJobUntilNextJob() {
        val targets = PoolDifficultyTargets(1.0)
        val original = targets.activeTarget()

        targets.stage(2.0)

        assertArrayEquals(original, targets.activeTarget())
        assertArrayEquals(Util.difficultyToTarget(2.0), targets.activateForNextJob())
        assertArrayEquals(Util.difficultyToTarget(2.0), targets.activeTarget())
    }

    @Test fun displayedActiveDifficultyChangesOnlyWhenNextJobActivates() {
        val targets = PoolDifficultyTargets(1.0)
        targets.stage(2.5)

        org.junit.Assert.assertEquals(1.0, targets.activeDifficulty(), 0.0)
        org.junit.Assert.assertEquals(2.5, targets.pendingDifficulty()!!, 0.0)

        targets.activateForNextJob()

        org.junit.Assert.assertEquals(2.5, targets.activeDifficulty(), 0.0)
        org.junit.Assert.assertEquals(null, targets.pendingDifficulty())
    }

    @Test fun nextJobWithoutNewDifficultyKeepsTheCurrentActiveDifficulty() {
        val targets = PoolDifficultyTargets(1.0)
        targets.stage(3.0)
        targets.activateForNextJob()

        assertArrayEquals(Util.difficultyToTarget(3.0), targets.activateForNextJob())
        org.junit.Assert.assertEquals(3.0, targets.activeDifficulty(), 0.0)
        org.junit.Assert.assertEquals(null, targets.pendingDifficulty())
    }

    @Test fun latestDifficultyBeforeNextJobWins() {
        val targets = PoolDifficultyTargets(1.0)
        targets.stage(2.0)
        targets.stage(4.0)

        assertArrayEquals(Util.difficultyToTarget(4.0), targets.activateForNextJob())
    }

    @Test fun invalidDifficultyDoesNotReplaceActiveOrPendingTarget() {
        val targets = PoolDifficultyTargets(1.0)
        val original = targets.activeTarget()

        try {
            targets.stage(Double.NaN)
            throw AssertionError("NaN difficulty should be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }

        assertArrayEquals(original, targets.activateForNextJob())
    }
}
