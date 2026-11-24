package com.lottery.miner

import org.junit.Assert.*
import org.junit.Test

class SatsYieldAdvisorTest {
    private fun input(age: Long = 0L) = SatsYieldInput(
        deviceHashrateHps = 1.0,
        networkHashrateHps = 144.0,
        blockSubsidySats = 10_000_000.0,
        btcPriceInr = 1_000.0,
        powerWatts = 100.0,
        electricityInrPerKwh = 10.0,
        poolFeePercent = 10.0,
        snapshotAgeSeconds = age,
        blocksPerDay = 144.0
    )

    @Test fun calculatesExpectedSatsEnergyAndSoloProbability() {
        val result = SatsYieldAdvisor.analyze(input())
        assertTrue(result.isFresh)
        assertEquals(10_000_000.0, result.expectedGrossSatsPerDay, 1e-6)
        assertEquals(9_000_000.0, result.expectedAfterPoolFeeSatsPerDay, 1e-6)
        assertEquals(24.0, result.energyKwhPerDay, 1e-9)
        assertEquals(240.0, result.electricityCostInrPerDay, 1e-9)
        assertEquals(24_000_000.0, result.electricityCostSatsPerDay, 1e-6)
        assertEquals(-15_000_000.0, result.expectedNetSatsPerDay, 1e-6)
        assertEquals(-150.0, result.expectedNetInrPerDay, 1e-9)
        assertEquals(1.0, result.expectedBlocksPerDay, 1e-9)
        assertEquals(1.0, result.expectedDaysPerBlock, 1e-9)
        assertEquals(1.0 - kotlin.math.exp(-1.0), result.blockProbability1Day, 1e-12)
        assertTrue(result.blockProbability7Days > result.blockProbability1Day)
        assertTrue(result.blockProbability30Days > result.blockProbability7Days)
    }

    @Test fun staleSnapshotsCannotDriveRewardOrRentalDecisions() {
        val result = SatsYieldAdvisor.analyze(
            input(SatsYieldAdvisor.MAX_SNAPSHOT_AGE_SECONDS + 1)
                .copy(rentalQuoteSatsPerThDay = 12.0)
        )
        assertFalse(result.isFresh)
        assertTrue(result.expectedNetSatsPerDay.isNaN())
        assertTrue(result.blockProbability30Days.isNaN())
        assertNull(result.rentalDeltaSatsPerThDay)
        assertEquals(24.0, result.energyKwhPerDay, 1e-9)
    }

    @Test fun comparesUserSuppliedRentalQuoteToSubsidyOnlyCeiling() {
        val result = SatsYieldAdvisor.analyze(
            input().copy(
                networkHashrateHps = 1_000_000_000_000.0,
                deviceHashrateHps = 1_000.0,
                rentalQuoteSatsPerThDay = 2_000_000_000.0
            )
        )
        assertNotNull(result.rentalDeltaSatsPerThDay)
        assertTrue(result.rentalDeltaSatsPerThDay!! < 0.0)
    }

    @Test fun rejectsInvalidAndImpossibleInputs() {
        assertTrue(runCatching { SatsYieldAdvisor.analyze(input().copy(deviceHashrateHps = 0.0)) }.isFailure)
        assertTrue(runCatching { SatsYieldAdvisor.analyze(input().copy(deviceHashrateHps = 145.0)) }.isFailure)
        assertTrue(runCatching { SatsYieldAdvisor.analyze(input().copy(poolFeePercent = 100.1)) }.isFailure)
        assertTrue(runCatching { SatsYieldAdvisor.analyze(input().copy(rentalQuoteSatsPerThDay = -1.0)) }.isFailure)
    }

    @Test fun efficiencyDoesNotPretendPowerWasMeasuredByThePhone() {
        val result = SatsYieldAdvisor.analyze(input())
        assertEquals(0.01, result.hashesPerWatt, 1e-12)
        assertEquals(1.0e14, result.joulesPerTerahash, 1e-3)
    }
}
