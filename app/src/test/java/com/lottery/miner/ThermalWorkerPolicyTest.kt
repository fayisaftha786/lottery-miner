package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalWorkerPolicyTest {
    @Test fun normalAndLightAllowTheConfiguredWorkerCount() {
        assertEquals(8, ThermalWorkerPolicy.evaluate(8, 8, 0).workerCount)
        assertEquals(8, ThermalWorkerPolicy.evaluate(8, 8, 1).workerCount)
    }

    @Test fun moderateThermalStatusCapsWorkersAtHalf() {
        val decision = ThermalWorkerPolicy.evaluate(8, 8, 2)
        assertEquals(4, decision.maximumAllowedWorkers)
        assertEquals(4, decision.workerCount)
        assertEquals("MODERATE", decision.statusLabel)
        assertTrue(decision.reason.contains("4/8"))
    }

    @Test fun moderateCapDoesNotIncreaseAnAlreadyLowerSafetyLimit() {
        val decision = ThermalWorkerPolicy.evaluate(8, 2, 2)
        assertEquals(2, decision.workerCount)
        assertEquals(4, decision.maximumAllowedWorkers)
    }

    @Test fun severeAndHigherThermalStatesPauseMining() {
        for (status in 3..6) {
            val decision = ThermalWorkerPolicy.evaluate(8, 4, status)
            assertEquals(0, decision.workerCount)
            assertTrue(decision.reason.contains("paused"))
        }
    }

    @Test fun unknownThermalStatusFailsSafeToOneWorker() {
        val decision = ThermalWorkerPolicy.evaluate(8, 8, ThermalWorkerPolicy.UNKNOWN)
        assertEquals(1, decision.workerCount)
        assertEquals("UNKNOWN", decision.statusLabel)
    }

    @Test fun oneConfiguredWorkerRemainsOneAtModerateAndPausesAtSevere() {
        assertEquals(1, ThermalWorkerPolicy.evaluate(1, 1, 2).workerCount)
        assertEquals(0, ThermalWorkerPolicy.evaluate(1, 1, 3).workerCount)
    }

    @Test fun missingThermalApiIsReportedWithoutInventingADeviceState() {
        assertEquals("NOT AVAILABLE", ThermalWorkerPolicy.label(null))
    }
}
