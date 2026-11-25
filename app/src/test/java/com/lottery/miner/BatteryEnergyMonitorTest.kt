package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryEnergyMonitorTest {
    @Test fun signedBatteryCurrentProducesSignedBatteryPower() {
        val monitor = BatteryEnergyMonitor()
        val discharge = monitor.update(-500_000L, 3_800, 1_000_000_000L)
        assertTrue(discharge.available)
        assertEquals(-1.9, discharge.netPowerWatts!!, 1e-9)
        assertEquals(-500_000L, discharge.currentMicroAmps)
        assertEquals(0.0, discharge.dischargedMilliWattHours, 1e-12)

        val charging = monitor.update(500_000L, 3_800, 3_000_000_000L)
        assertEquals(1.9, charging.netPowerWatts!!, 1e-9)
        assertEquals(0.0, charging.dischargedMilliWattHours, 1e-12)
    }

    @Test fun integratesOnlyContiguousDischargingSamples() {
        val monitor = BatteryEnergyMonitor()
        monitor.update(-500_000L, 3_800, 1_000_000_000L)
        val snapshot = monitor.update(-500_000L, 3_800, 11_000_000_000L)
        assertEquals(5.2777777778, snapshot.dischargedMilliWattHours, 1e-8)
        assertEquals(2, snapshot.validSampleCount)
    }

    @Test fun chargingAndTransitionIntervalsAreNotCountedAsDischarge() {
        val monitor = BatteryEnergyMonitor()
        monitor.update(-500_000L, 3_800, 1_000_000_000L)
        monitor.update(100_000L, 3_800, 6_000_000_000L)
        val snapshot = monitor.update(-500_000L, 3_800, 11_000_000_000L)
        assertEquals(0.0, snapshot.dischargedMilliWattHours, 1e-12)
    }

    @Test fun missingOrInvalidSensorBreaksTheIntegrationChain() {
        val monitor = BatteryEnergyMonitor()
        monitor.update(-500_000L, 3_800, 1_000_000_000L)
        val unavailable = monitor.update(Long.MIN_VALUE, 3_800, 6_000_000_000L)
        assertFalse(unavailable.available)
        assertNull(unavailable.currentMicroAmps)
        assertNull(unavailable.netPowerWatts)
        val next = monitor.update(-500_000L, 3_800, 11_000_000_000L)
        assertEquals(0.0, next.dischargedMilliWattHours, 1e-12)
    }

    @Test fun longGapsAreNotIntegrated() {
        val monitor = BatteryEnergyMonitor()
        monitor.update(-500_000L, 3_800, 1_000_000_000L)
        val afterGap = monitor.update(-500_000L, 3_800, 30_000_000_000L)
        assertEquals(0.0, afterGap.dischargedMilliWattHours, 1e-12)
        val next = monitor.update(-500_000L, 3_800, 40_000_000_000L)
        assertEquals(5.2777777778, next.dischargedMilliWattHours, 1e-8)
    }

    @Test fun invalidVoltageAndExtremeCurrentAreUnavailable() {
        val monitor = BatteryEnergyMonitor()
        assertFalse(monitor.update(-300_000L, 1_000, 1_000_000_000L).available)
        assertFalse(monitor.update(20_000_000L, 3_800, 2_000_000_000L).available)
        assertEquals(1, monitor.update(-300_000L, 3_800, 3_000_000_000L).validSampleCount)
    }

    @Test fun resetClearsSessionEnergyAndSamplingHistory() {
        val monitor = BatteryEnergyMonitor()
        monitor.update(-500_000L, 3_800, 1_000_000_000L)
        monitor.update(-500_000L, 3_800, 11_000_000_000L)
        monitor.reset()
        val afterReset = monitor.update(-500_000L, 3_800, 30_000_000_000L)
        assertEquals(0.0, afterReset.dischargedMilliWattHours, 1e-12)
        assertEquals(1, afterReset.validSampleCount)
    }
}
