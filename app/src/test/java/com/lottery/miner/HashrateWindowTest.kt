package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Test

class HashrateWindowTest {
    @Test fun reportsZeroUntilTwoDistinctSamplesExist() {
        val window = HashrateWindow(windowNanos = 10_000_000_000L)
        assertEquals(0.0, window.record(0L, 0L).hashRateHps, 0.0)
        assertEquals(0.0, window.record(10L, 0L).hashRateHps, 0.0)
        val reading = window.record(1_000L, 1_000_000_000L)
        assertEquals(1_000.0, reading.hashRateHps, 0.0)
        assertEquals(1.0, reading.windowSeconds, 0.0)
    }

    @Test fun computesRateFromRealCumulativeHashSamples() {
        val window = HashrateWindow(windowNanos = 20_000_000_000L)
        window.record(0L, 0L)
        window.record(2_000L, 2_000_000_000L)
        val reading = window.record(10_000L, 10_000_000_000L)
        assertEquals(1_000.0, reading.hashRateHps, 0.0)
        assertEquals(10.0, reading.windowSeconds, 0.0)
    }

    @Test fun discardsSamplesOlderThanConfiguredWindow() {
        val window = HashrateWindow(windowNanos = 10_000_000_000L)
        window.record(0L, 0L)
        window.record(5_000L, 5_000_000_000L)
        window.record(10_000L, 10_000_000_000L)
        val reading = window.record(12_000L, 15_000_000_000L)
        assertEquals(700.0, reading.hashRateHps, 0.0)
        assertEquals(10.0, reading.windowSeconds, 0.0)
    }

    @Test fun counterOrClockResetStartsANewWindow() {
        val window = HashrateWindow(windowNanos = 10_000_000_000L)
        window.record(1_000L, 10_000_000_000L)
        assertEquals(1_000.0, window.record(2_000L, 11_000_000_000L).hashRateHps, 0.0)
        assertEquals(0.0, window.record(5L, 12_000_000_000L).hashRateHps, 0.0)
        assertEquals(1_000.0, window.record(1_005L, 13_000_000_000L).hashRateHps, 0.0)
        assertEquals(0.0, window.record(2_000L, 1L).hashRateHps, 0.0)
    }

    @Test fun acceptsOnlyPositiveWindowAndSampleCapacity() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            HashrateWindow(windowNanos = 0L)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            HashrateWindow(maxSamples = 1)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            HashrateWindow().record(-1L, 0L)
        }
    }
}
