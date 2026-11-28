package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockChanceEngineTest {
    @Test fun oneExpectedBlockIntervalHasPoissonChanceOfAbout63Percent() {
        val rate = BlockChanceEngine.DIFFICULTY_ONE_EXPECTED_HASHES / 600.0
        val chance = BlockChanceEngine.probabilityFor(rate, 1.0, 600.0)
        assertEquals(1.0 - kotlin.math.exp(-1.0), chance, 1e-12)
    }

    @Test fun probabilityIncreasesWithTimeAndHashrate() {
        val slow = BlockChanceEngine.calculate(37.33e6, 145.09e12)
        val faster = BlockChanceEngine.calculate(74.66e6, 145.09e12)
        assertTrue(slow.chance1Hour < slow.chance24Hours)
        assertTrue(slow.chance24Hours < slow.chance7Days)
        assertTrue(slow.chance7Days < slow.chance30Days)
        assertTrue(faster.chance24Hours > slow.chance24Hours)
    }

    @Test fun requiredHashrateProducesRequestedProbability() {
        val required = BlockChanceEngine.requiredHashrateForProbability(1.0, 600.0, 0.50)
        val actual = BlockChanceEngine.probabilityFor(required, 1.0, 600.0)
        assertEquals(0.50, actual, 1e-12)
    }

    @Test fun realisticPhoneChanceRemainsTinyAndFinite() {
        val result = BlockChanceEngine.calculate(37.33e6, 145.09e12)
        assertTrue(result.chance24Hours > 0.0)
        assertTrue(result.chance24Hours < 1e-9)
        assertTrue(result.expectedMeanTimeSeconds.isFinite())
        assertTrue(result.hashrateFor1PercentIn24Hours > 37.33e6)
        assertTrue(result.hashrateFor50PercentIn24Hours > result.hashrateFor1PercentIn24Hours)
    }

    @Test fun rejectsInvalidInputs() {
        assertTrue(runCatching {
            BlockChanceEngine.calculate(0.0, 1.0)
        }.isFailure)
        assertTrue(runCatching {
            BlockChanceEngine.calculate(100.0, Double.NaN)
        }.isFailure)
        assertTrue(runCatching {
            BlockChanceEngine.probabilityFor(100.0, 1.0, 0.0)
        }.isFailure)
        assertTrue(runCatching {
            BlockChanceEngine.requiredHashrateForProbability(1.0, 600.0, 1.0)
        }.isFailure)
    }
}
