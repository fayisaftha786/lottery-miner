package com.lottery.miner

import org.junit.Assert.*
import org.junit.Test

class MiningStrategyTest {
    private fun input(age: Long = 0L) = MiningEconomicsInput(
        deviceHashrate = 100.0,
        networkHashrate = 10_000.0,
        dailyNetworkRewardCoins = 100.0,
        coinPriceUsd = 2.0,
        powerWatts = 10.0,
        electricityUsdPerKwh = 0.10,
        poolFeePercent = 2.0,
        snapshotAgeSeconds = age
    )

    @Test fun computesGrossFeesPowerAndNetSeparately() {
        val result = MiningStrategy.estimate(input())
        assertEquals(2.0, result.grossUsdPerDay, 1e-9)
        assertEquals(0.04, result.poolFeeUsdPerDay, 1e-9)
        assertEquals(0.024, result.electricityUsdPerDay, 1e-9)
        assertEquals(1.936, result.netUsdPerDay, 1e-9)
        assertTrue(result.isFresh)
    }

    @Test fun staleMarketSnapshotCannotProduceNetProfit() {
        val result = MiningStrategy.estimate(input(MiningStrategy.MAX_SNAPSHOT_AGE_SECONDS + 1))
        assertFalse(result.isFresh)
        assertTrue(result.netUsdPerDay.isNaN())
    }

    @Test fun rejectsInvalidEconomicsInputs() {
        assertFalse(runCatching {
            MiningStrategy.estimate(input().copy(networkHashrate = 0.0))
        }.isSuccess)
        assertFalse(runCatching {
            MiningStrategy.estimate(input().copy(poolFeePercent = 101.0))
        }.isSuccess)
    }

    @Test fun neverSelectsUninstalledOrUnprofitableAlgorithms() {
        val installed = MiningAlgorithms.candidates.first().copy(deviceBenchmarkHps = 100.0)
        val unavailable = MiningAlgorithms.candidates[1].copy(deviceBenchmarkHps = 1_000.0)
        val positive = MiningStrategy.estimate(input())
        val loss = MiningStrategy.estimate(input().copy(electricityUsdPerKwh = 100.0))
        assertEquals(installed, MiningStrategy.chooseBest(listOf(installed to positive, unavailable to positive)))
        assertNull(MiningStrategy.chooseBest(listOf(installed to loss)))
    }
}
