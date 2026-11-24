package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveScanChunkControllerTest {
    @Test fun growsOnlyAfterRepeatedFastScans() {
        val controller = AdaptiveScanChunkController()
        repeat(3) { controller.observeElapsedNanos(1_000_000L) }
        assertEquals(32_768, controller.currentChunkSize())
        assertEquals(49_152, controller.observeElapsedNanos(1_000_000L))
    }

    @Test fun shrinksAfterRepeatedSlowScans() {
        val controller = AdaptiveScanChunkController()
        controller.observeElapsedNanos(9_000_000L)
        assertEquals(32_768, controller.currentChunkSize())
        assertEquals(24_576, controller.observeElapsedNanos(9_000_000L))
    }

    @Test fun normalSamplesResetGrowthAndShrinkCounters() {
        val controller = AdaptiveScanChunkController()
        repeat(3) { controller.observeElapsedNanos(1_000_000L) }
        controller.observeElapsedNanos(4_000_000L)
        repeat(3) { controller.observeElapsedNanos(1_000_000L) }
        assertEquals(32_768, controller.currentChunkSize())
    }

    @Test fun clampsAtConfiguredBounds() {
        val grow = AdaptiveScanChunkController(
            initialChunkSize = 60_000,
            minChunkSize = 8_192,
            maxChunkSize = 65_536,
            fastSamplesToGrow = 1
        )
        assertEquals(65_536, grow.observeElapsedNanos(1L))
        assertEquals(65_536, grow.observeElapsedNanos(1L))

        val shrink = AdaptiveScanChunkController(
            initialChunkSize = 10_000,
            minChunkSize = 8_192,
            maxChunkSize = 65_536,
            slowSamplesToShrink = 1
        )
        assertEquals(8_192, shrink.observeElapsedNanos(9_000_000L))
        assertEquals(8_192, shrink.observeElapsedNanos(9_000_000L))
    }

    @Test fun rejectsInvalidTimingAndRanges() {
        val controller = AdaptiveScanChunkController()
        assertTrue(runCatching { controller.observeElapsedNanos(-1L) }.isFailure)
        assertTrue(runCatching {
            AdaptiveScanChunkController(initialChunkSize = 4_096)
        }.isFailure)
        assertTrue(runCatching {
            AdaptiveScanChunkController(initialChunkSize = 70_000)
        }.isFailure)
    }
}
