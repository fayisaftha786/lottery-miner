package com.lottery.miner

import org.junit.Assert.*
import org.junit.Test

class MiningStrategyTest {
    private fun input(age: Long = 0L) = MiningEconomicsInput(
        deviceHashrate = 100.0,
        networkHashrate = 10_000.0,
        dailyNetworkRewardCoins = 100.0,
        coinPriceFiat = 2.0,
        powerWatts = 10.0,
        electricityFiatPerKwh = 0.10,
        poolFeePercent = 2.0,
        snapshotAgeSeconds = age,
        networkBlocksPerDay = 144.0,
        payoutModel = PayoutModel.SOLO_BLOCK_REWARD
    )

    @Test fun computesGrossFeesPowerNetAndExpectedSoloWaitSeparately() {
        val result = MiningStrategy.estimate(input())
        assertEquals(2.0, result.grossFiatPerDay, 1e-9)
        assertEquals(0.04, result.poolFeeFiatPerDay, 1e-9)
        assertEquals(0.024, result.electricityFiatPerDay, 1e-9)
        assertEquals(1.936, result.netFiatPerDay, 1e-9)
        assertEquals(1.44, result.expectedBlocksFoundPerDay, 1e-9)
        assertEquals(1.0 / 1.44, result.expectedDaysPerBlock, 1e-9)
        assertEquals(PayoutModel.SOLO_BLOCK_REWARD, result.payoutModel)
        assertTrue(result.isFresh)
    }

    @Test fun staleMarketSnapshotCannotProduceNetProfit() {
        val result = MiningStrategy.estimate(input(MiningStrategy.MAX_SNAPSHOT_AGE_SECONDS + 1))
        assertFalse(result.isFresh)
        assertTrue(result.netFiatPerDay.isNaN())
    }

    @Test fun rejectsInvalidEconomicsInputs() {
        assertTrue(runCatching {
            MiningStrategy.estimate(input().copy(networkHashrate = 0.0))
        }.isFailure)
        assertTrue(runCatching {
            MiningStrategy.estimate(input().copy(poolFeePercent = 101.0))
        }.isFailure)
        assertTrue(runCatching {
            MiningStrategy.estimate(input().copy(networkBlocksPerDay = 0.0))
        }.isFailure)
    }

    @Test fun neverSelectsUninstalledOrUnprofitableAlgorithms() {
        val installed = MiningAlgorithms.candidates.first().copy(deviceBenchmarkHps = 100.0)
        val unavailable = MiningAlgorithms.candidates[1].copy(deviceBenchmarkHps = 1_000.0)
        val positive = MiningStrategy.estimate(input())
        val loss = MiningStrategy.estimate(input().copy(electricityFiatPerKwh = 100.0))
        assertEquals(installed, MiningStrategy.chooseBest(listOf(installed to positive, unavailable to positive)))
        assertNull(MiningStrategy.chooseBest(listOf(installed to loss)))
    }

    @Test fun refusesProportionalPoolEstimateWithoutPoolSpecificData() {
        assertTrue(runCatching {
            MiningStrategy.estimate(input().copy(payoutModel = PayoutModel.PROPORTIONAL_POOL))
        }.isFailure)
    }

    @Test fun blockProbabilityUsesPoissonChanceAndIncreasesWithHashrateAndTime() {
        val oneDay = MiningStrategy.blockProbability(100.0, 10_000.0, 1.0)
        val month = MiningStrategy.blockProbability(100.0, 10_000.0, 30.0)
        val faster = MiningStrategy.blockProbability(200.0, 10_000.0, 1.0)
        assertTrue(oneDay > 0.0)
        assertTrue(month > oneDay)
        assertTrue(faster > oneDay)
        assertTrue(oneDay < 1.0)
    }

    @Test fun requiredHashrateInvertsTheProbabilityCalculation() {
        val required = MiningStrategy.requiredHashrateForProbability(0.50, 10_000.0, 365.0)
        assertEquals(0.50, MiningStrategy.blockProbability(required, 10_000.0, 365.0), 1e-12)
    }

    @Test fun probabilityInputsRejectInvalidValues() {
        assertTrue(runCatching { MiningStrategy.blockProbability(-1.0, 10_000.0, 1.0) }.isFailure)
        assertTrue(runCatching { MiningStrategy.blockProbability(1.0, 0.0, 1.0) }.isFailure)
        assertTrue(runCatching { MiningStrategy.requiredHashrateForProbability(1.1, 10_000.0, 365.0) }.isFailure)
    }

    @Test fun expectedSoloBlockIntervalGrowsWhenDeviceHashrateFalls() {
        val slow = MiningStrategy.estimate(input().copy(deviceHashrate = 1.0))
        val fast = MiningStrategy.estimate(input().copy(deviceHashrate = 100.0))
        assertTrue(slow.expectedDaysPerBlock > fast.expectedDaysPerBlock)
    }
}
