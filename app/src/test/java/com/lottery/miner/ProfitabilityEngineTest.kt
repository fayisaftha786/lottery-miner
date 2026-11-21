package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfitabilityEngineTest {
    @Test fun calculatesExpectedBitcoinRevenueAndElectricityCost() {
        val result = ProfitabilityEngine.estimateBitcoin(
            hashrateHps = 1_000_000_000_000.0,
            powerWatts = 100.0,
            tariffInrPerKwh = 10.0,
            usdInr = 90.0,
            hashpriceUsdPerPhDay = 27.4,
            poolFeePercent = 2.0
        )

        assertEquals(0.0274, result.grossRevenueUsdPerDay, 1e-12)
        assertEquals(0.026852, result.revenueAfterPoolFeeUsdPerDay, 1e-12)
        assertEquals(2.41668, result.revenueAfterPoolFeeInrPerDay, 1e-9)
        assertEquals(2.4, result.energyKwhPerDay, 1e-12)
        assertEquals(24.0, result.electricityCostInrPerDay, 1e-12)
        assertEquals(-21.58332, result.expectedNetInrPerDay, 1e-9)
        assertEquals(2.41668 / 2.4, result.breakEvenTariffInrPerKwh, 1e-9)
    }

    @Test fun derivesBreakEvenHashrateFromPowerAndTariff() {
        val low = ProfitabilityEngine.estimateBitcoin(
            hashrateHps = 1e6,
            powerWatts = 100.0,
            tariffInrPerKwh = 10.0,
            usdInr = 90.0,
            hashpriceUsdPerPhDay = 27.4,
            poolFeePercent = 2.0
        )
        val high = ProfitabilityEngine.estimateBitcoin(
            hashrateHps = 1e6,
            powerWatts = 200.0,
            tariffInrPerKwh = 10.0,
            usdInr = 90.0,
            hashpriceUsdPerPhDay = 27.4,
            poolFeePercent = 2.0
        )
        assertTrue(high.breakEvenHashrateHps > low.breakEvenHashrateHps)
        assertEquals(low.breakEvenHashrateHps * 2.0, high.breakEvenHashrateHps, 1e-3)
    }

    @Test fun zeroPowerHasZeroElectricityCost() {
        val result = ProfitabilityEngine.estimateBitcoin(
            hashrateHps = 3_540_000.0,
            powerWatts = 0.0,
            tariffInrPerKwh = 12.0,
            usdInr = 90.0,
            hashpriceUsdPerPhDay = 27.4,
            poolFeePercent = 2.0
        )
        assertEquals(0.0, result.electricityCostInrPerDay, 0.0)
        assertTrue(result.expectedNetInrPerDay >= 0.0)
    }

    @Test fun rejectsInvalidInputsAndFeeOutOfRange() {
        assertTrue(runCatching {
            ProfitabilityEngine.estimateBitcoin(-1.0, 100.0, 10.0, 90.0, 27.4, 2.0)
        }.isFailure)
        assertTrue(runCatching {
            ProfitabilityEngine.estimateBitcoin(1e6, Double.NaN, 10.0, 90.0, 27.4, 2.0)
        }.isFailure)
        assertTrue(runCatching {
            ProfitabilityEngine.estimateBitcoin(1e6, 100.0, -1.0, 90.0, 27.4, 2.0)
        }.isFailure)
        assertTrue(runCatching {
            ProfitabilityEngine.estimateBitcoin(1e6, 100.0, 10.0, 0.0, 27.4, 2.0)
        }.isFailure)
        assertTrue(runCatching {
            ProfitabilityEngine.estimateBitcoin(1e6, 100.0, 10.0, 90.0, 27.4, 101.0)
        }.isFailure)
    }
}
