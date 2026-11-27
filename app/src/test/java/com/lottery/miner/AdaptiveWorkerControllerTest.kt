package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveWorkerControllerTest {
    private fun controller() = AdaptiveWorkerController(
        rampIntervalMillis = 10_000L,
        measurementWindowMillis = 5_000L,
        minimumImprovementFraction = 0.03
    )

    @Test fun startsWithOneWorkerAndOnlyRampsAfterARealRateSample() {
        val c = controller()
        assertEquals(1, c.selectWorkerCount(8, 0, 1_000L, 0.0))
        assertEquals(1, c.selectWorkerCount(8, 1, 10_999L, 100.0))
        assertEquals(2, c.selectWorkerCount(8, 1, 11_000L, 100.0))
        assertTrue(c.statusMessage.contains("Testing 2"))
    }

    @Test fun acceptsAnAdditionalWorkerOnlyAfterMeasuredImprovement() {
        val c = controller()
        assertEquals(1, c.selectWorkerCount(8, 0, 1_000L, 0.0))
        assertEquals(2, c.selectWorkerCount(8, 1, 11_000L, 100.0))
        assertEquals(2, c.selectWorkerCount(8, 2, 15_999L, 140.0))
        assertEquals(2, c.selectWorkerCount(8, 2, 16_000L, 140.0))
        assertTrue(c.statusMessage.contains("Accepted 2"))
        assertEquals(2, c.selectWorkerCount(8, 2, 25_999L, 140.0))
        assertEquals(3, c.selectWorkerCount(8, 2, 26_000L, 140.0))
    }

    @Test fun rejectsAnUnproductiveWorkerAndRollsBackToBestMeasuredCount() {
        val c = controller()
        assertEquals(1, c.selectWorkerCount(8, 0, 1_000L, 0.0))
        assertEquals(2, c.selectWorkerCount(8, 1, 11_000L, 100.0))
        assertEquals(2, c.selectWorkerCount(8, 2, 16_000L, 101.0))
        assertTrue(c.statusMessage.contains("did not improve"))
        assertEquals(1, c.selectWorkerCount(8, 1, 60_000L, 100.0))
        assertTrue(c.statusMessage.contains("measured best"))
    }

    @Test fun safetyReductionIsImmediateAndNeverOverriddenByTuning() {
        val c = controller()
        assertEquals(1, c.selectWorkerCount(8, 0, 1_000L, 0.0))
        assertEquals(2, c.selectWorkerCount(8, 1, 11_000L, 100.0))
        assertEquals(1, c.selectWorkerCount(1, 2, 11_001L, 80.0))
        assertTrue(c.statusMessage.contains("Safety cap"))
        assertEquals(0, c.selectWorkerCount(0, 1, 12_000L, 0.0))
        assertTrue(c.statusMessage.contains("paused"))
    }

    @Test fun coolingOrARecoveredSafetyLimitAllowsFreshTuning() {
        val c = controller()
        assertEquals(1, c.selectWorkerCount(2, 0, 1_000L, 0.0))
        assertEquals(1, c.selectWorkerCount(1, 1, 2_000L, 100.0))
        assertEquals(1, c.selectWorkerCount(2, 1, 3_000L, 100.0))
        assertEquals(1, c.selectWorkerCount(2, 1, 12_999L, 100.0))
        assertEquals(2, c.selectWorkerCount(2, 1, 13_000L, 100.0))
    }

    @Test fun invalidHasheratesAreRejected() {
        val c = controller()
        try {
            c.selectWorkerCount(4, 0, 1L, Double.NaN)
            throw AssertionError("NaN hashrate must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: tuning may only consume a real finite measurement.
        }
    }

    @Test fun doesNotExceedConfiguredOrSafetyWorkerLimit() {
        val c = controller()
        assertEquals(1, c.selectWorkerCount(1, 0, 1L, 0.0))
        assertEquals(1, c.selectWorkerCount(1, 1, 2L, 100.0))
        assertEquals(0, c.selectWorkerCount(0, 1, 3L, 0.0))
    }
}
