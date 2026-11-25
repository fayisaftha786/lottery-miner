package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalHeadroomPolicyTest {
    private val thresholds = mapOf(1 to 0.55f, 2 to 0.72f, 3 to 0.90f)

    @Test fun belowDeviceThresholdsLeavesRequestedWorkersUnchanged() {
        val result = ThermalHeadroomPolicy.evaluate(8, 8, 0.50, thresholds)
        assertEquals(8, result.workerCount)
        assertEquals(null, result.limitingStatus)
        assertTrue(result.reason.isEmpty())
    }

    @Test fun lightThresholdReducesWorkersToThreeQuarters() {
        val result = ThermalHeadroomPolicy.evaluate(8, 8, 0.60, thresholds)
        assertEquals(6, result.workerCount)
        assertEquals(1, result.limitingStatus)
        assertTrue(result.reason.contains("LIGHT"))
    }

    @Test fun moderateThresholdCapsWorkersAtHalf() {
        val result = ThermalHeadroomPolicy.evaluate(8, 8, 0.75, thresholds)
        assertEquals(4, result.workerCount)
        assertEquals(2, result.limitingStatus)
    }

    @Test fun severeThresholdPausesMining() {
        val result = ThermalHeadroomPolicy.evaluate(8, 8, 0.92, thresholds)
        assertEquals(0, result.workerCount)
        assertEquals(3, result.limitingStatus)
        assertTrue(result.reason.contains("paused"))
    }

    @Test fun thermalPolicyNeverRaisesAnExistingLowerSafetyLimit() {
        val result = ThermalHeadroomPolicy.evaluate(8, 2, 0.60, thresholds)
        assertEquals(2, result.workerCount)
        assertEquals(null, result.limitingStatus)
    }

    @Test fun missingOrInvalidThresholdDataDoesNotInventAWorkerLimit() {
        assertEquals(8, ThermalHeadroomPolicy.evaluate(8, 8, 0.99, emptyMap()).workerCount)
        assertEquals(8, ThermalHeadroomPolicy.evaluate(8, 8, Double.NaN, thresholds).workerCount)
        assertEquals(8, ThermalHeadroomPolicy.evaluate(8, 8, 0.99, mapOf(2 to Float.NaN)).workerCount)
    }

    @Test fun invalidHeadroomIsNotTreatedAsAZeroReading() {
        val result = ThermalHeadroomPolicy.evaluate(8, 8, Double.POSITIVE_INFINITY, thresholds)
        assertEquals(8, result.workerCount)
        assertFalse(result.reason.contains("threshold"))
    }
}
