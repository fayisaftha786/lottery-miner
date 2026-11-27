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

    private fun buildOneWorkerBaseline(c: AdaptiveWorkerController) {
        assertEquals(1, c.selectWorkerCount(8, 0, 1_000L, 0.0))
        for (t in listOf(2_999L, 4_999L, 6_999L, 8_999L, 10_999L)) {
            assertEquals(1, c.selectWorkerCount(8, 1, t, 100.0))
        }
    }

    @Test fun startsWithOneWorkerAndOnlyRampsAfterEnoughRealBaselineSamples() {
        val c = controller()
        buildOneWorkerBaseline(c)
        assertEquals(2, c.selectWorkerCount(8, 1, 11_001L, 100.0))
        assertTrue(c.statusMessage.contains("Testing 2"))
    }

    @Test fun acceptsAnAdditionalWorkerOnlyAfterWindowAverageImproves() {
        val c = controller()
        buildOneWorkerBaseline(c)
        assertEquals(2, c.selectWorkerCount(8, 1, 11_001L, 100.0))
        assertEquals(2, c.selectWorkerCount(8, 2, 13_000L, 140.0))
        assertEquals(2, c.selectWorkerCount(8, 2, 15_000L, 140.0))
        assertEquals(2, c.selectWorkerCount(8, 2, 16_001L, 140.0))
        assertTrue(c.statusMessage.contains("Accepted 2"))
        for (t in listOf(18_001L, 20_001L, 22_001L, 24_001L, 26_001L)) {
            assertEquals(2, c.selectWorkerCount(8, 2, t, 140.0))
        }
        assertEquals(3, c.selectWorkerCount(8, 2, 26_002L, 140.0))
    }

    @Test fun rejectsAnUnproductiveWorkerAndRollsBackToBestMeasuredCount() {
        val c = controller()
        buildOneWorkerBaseline(c)
        assertEquals(2, c.selectWorkerCount(8, 1, 11_001L, 100.0))
        assertEquals(2, c.selectWorkerCount(8, 2, 13_000L, 101.0))
        assertEquals(2, c.selectWorkerCount(8, 2, 15_000L, 101.0))
        assertEquals(1, c.selectWorkerCount(8, 2, 16_001L, 101.0))
        assertTrue(c.statusMessage.contains("did not improve enough"))
        assertEquals(1, c.selectWorkerCount(8, 1, 60_000L, 100.0))
        assertTrue(c.statusMessage.contains("measured best"))
    }

    @Test fun safetyReductionIsImmediateAndNeverOverriddenByTuning() {
        val c = controller()
        buildOneWorkerBaseline(c)
        assertEquals(2, c.selectWorkerCount(8, 1, 11_001L, 100.0))
        assertEquals(1, c.selectWorkerCount(1, 2, 11_002L, 80.0))
        assertTrue(c.statusMessage.contains("Safety cap"))
        assertEquals(0, c.selectWorkerCount(0, 1, 12_000L, 0.0))
        assertTrue(c.statusMessage.contains("paused"))
    }

    @Test fun coolingOrARecoveredSafetyLimitAllowsFreshTuning() {
        val c = controller()
        assertEquals(1, c.selectWorkerCount(2, 0, 1_000L, 0.0))
        assertEquals(1, c.selectWorkerCount(1, 1, 2_000L, 100.0))
        assertEquals(1, c.selectWorkerCount(2, 1, 3_000L, 100.0))
        for (t in listOf(4_999L, 6_999L, 8_999L, 10_999L, 12_999L)) {
            assertEquals(1, c.selectWorkerCount(2, 1, t, 100.0))
        }
        assertEquals(2, c.selectWorkerCount(2, 1, 13_001L, 100.0))
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
