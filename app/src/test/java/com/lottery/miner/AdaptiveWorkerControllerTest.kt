package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveWorkerControllerTest {
    @Test fun defaultRampReachesEightWorkersInAboutFourteenSeconds() {
        val controller = AdaptiveWorkerController()
        assertEquals(2_000L, AdaptiveWorkerController.DEFAULT_RAMP_INTERVAL_MILLIS)
        assertEquals(1, controller.selectWorkerCount(8, 0, 1_000L))
        for (expectedWorkers in 2..8) {
            val rampAt = 1_000L + (expectedWorkers - 1) * 2_000L
            assertEquals(expectedWorkers, controller.selectWorkerCount(8, expectedWorkers - 1, rampAt))
        }
    }

    @Test fun startsWithOneWorkerAndRampsOneAtATime() {
        val controller = AdaptiveWorkerController(rampIntervalMillis = 10_000L)
        assertEquals(1, controller.selectWorkerCount(8, 0, 1_000L))
        assertEquals(1, controller.selectWorkerCount(8, 1, 10_999L))
        assertEquals(2, controller.selectWorkerCount(8, 1, 11_000L))
        assertEquals(2, controller.selectWorkerCount(8, 2, 20_999L))
        assertEquals(3, controller.selectWorkerCount(8, 2, 21_000L))
    }

    @Test fun safetyReductionIsImmediate() {
        val controller = AdaptiveWorkerController(rampIntervalMillis = 10_000L)
        assertEquals(1, controller.selectWorkerCount(8, 0, 1_000L))
        assertEquals(1, controller.selectWorkerCount(8, 1, 10_999L))
        assertEquals(2, controller.selectWorkerCount(8, 1, 11_000L))
        assertEquals(1, controller.selectWorkerCount(1, 2, 11_001L))
    }

    @Test fun safetyPauseResetsRampAndRestartsConservatively() {
        val controller = AdaptiveWorkerController(rampIntervalMillis = 10_000L)
        assertEquals(1, controller.selectWorkerCount(4, 0, 1_000L))
        assertEquals(0, controller.selectWorkerCount(0, 1, 2_000L))
        assertEquals(1, controller.selectWorkerCount(4, 0, 3_000L))
        assertEquals(1, controller.selectWorkerCount(4, 1, 12_999L))
        assertEquals(2, controller.selectWorkerCount(4, 1, 13_000L))
    }

    @Test fun doesNotExceedConfiguredOrSafetyWorkerLimit() {
        val controller = AdaptiveWorkerController(rampIntervalMillis = 1L)
        assertEquals(1, controller.selectWorkerCount(1, 0, 1L))
        assertEquals(1, controller.selectWorkerCount(1, 1, 2L))
        assertEquals(0, controller.selectWorkerCount(0, 1, 3L))
    }
}
