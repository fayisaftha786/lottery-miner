package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SustainedPerformanceMonitorTest {
    @Test fun reportsObservedShortAndLongWindowRatesWithoutInventingHistory() {
        val monitor = SustainedPerformanceMonitor()
        val first = monitor.record(0L, 1_000_000_000L)
        assertEquals(0.0, first.fiveMinuteRateHps, 0.0)
        assertEquals(0.0, first.thirtyMinuteRateHps, 0.0)

        val next = monitor.record(20_000L, 11_000_000_000L)
        assertEquals(2_000.0, next.fiveMinuteRateHps, 0.0)
        assertEquals(2_000.0, next.thirtyMinuteRateHps, 0.0)
        assertEquals(10.0, next.fiveMinuteWindowSeconds, 0.0)
        assertEquals(10.0, next.thirtyMinuteWindowSeconds, 0.0)
    }

    @Test fun shortWindowExpiresBeforeThirtyMinuteWindow() {
        val monitor = SustainedPerformanceMonitor()
        monitor.record(0L, 1_000_000_000L)
        monitor.record(60_000L, 31_000_000_000L)
        val reading = monitor.record(120_000L, 331_000_000_000L)
        assertTrue(reading.thirtyMinuteWindowSeconds > reading.fiveMinuteWindowSeconds)
        assertEquals(120_000.0 / 300.0, reading.fiveMinuteRateHps, 0.001)
        assertEquals(120_000.0 / 330.0, reading.thirtyMinuteRateHps, 0.001)
    }

    @Test fun counterResetStartsFreshWindows() {
        val monitor = SustainedPerformanceMonitor()
        monitor.record(100_000L, 10_000_000_000L)
        val reset = monitor.record(10L, 11_000_000_000L)
        assertEquals(0.0, reset.fiveMinuteRateHps, 0.0)
        assertEquals(0.0, reset.thirtyMinuteRateHps, 0.0)
    }
}
