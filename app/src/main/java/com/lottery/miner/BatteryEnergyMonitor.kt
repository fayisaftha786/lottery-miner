package com.lottery.miner

/**
 * Estimates net battery-side power/energy from Android fuel-gauge telemetry.
 *
 * This is NOT USB input power, wall power, or miner-only power. Values depend
 * on device fuel-gauge support and averaging behaviour. Unsupported or invalid
 * sensors are reported as unavailable instead of fabricated as zero.
 */
internal data class BatteryEnergySnapshot(
    val available: Boolean,
    /** Signed microamps: positive charges the battery, negative discharges it. */
    val currentMicroAmps: Long?,
    val voltageMilliVolts: Int?,
    /** Signed watts: positive charges the battery, negative discharges it. */
    val netPowerWatts: Double?,
    /** Cumulative battery energy discharged during this monitor session. */
    val dischargedMilliWattHours: Double,
    val validSampleCount: Int
)

internal class BatteryEnergyMonitor(
    private val maxIntegrationGapNanos: Long = DEFAULT_MAX_INTEGRATION_GAP_NANOS
) {
    private var previousPowerWatts: Double? = null
    private var previousSampleAtNanos: Long? = null
    private var dischargedMilliWattHours = 0.0
    private var validSampleCount = 0

    init {
        require(maxIntegrationGapNanos > 0L)
    }

    @Synchronized
    fun update(
        currentMicroAmps: Long?,
        voltageMilliVolts: Int?,
        nowNanos: Long
    ): BatteryEnergySnapshot {
        val current = currentMicroAmps?.takeIf {
            it != Long.MIN_VALUE && it in MIN_VALID_CURRENT_MICRO_AMPS..MAX_VALID_CURRENT_MICRO_AMPS
        }
        val voltage = voltageMilliVolts?.takeIf { it in MIN_VALID_VOLTAGE_MV..MAX_VALID_VOLTAGE_MV }

        if (current == null || voltage == null) {
            // Break the integration chain so a missing sensor interval is never
            // silently treated as if the previous draw continued unchanged.
            previousPowerWatts = null
            previousSampleAtNanos = null
            return snapshot(false, null, null, null)
        }

        val powerWatts = current.toDouble() * voltage.toDouble() / MICROAMP_MILLIVOLT_PER_WATT
        if (!powerWatts.isFinite()) {
            previousPowerWatts = null
            previousSampleAtNanos = null
            return snapshot(false, null, null, null)
        }

        val previousPower = previousPowerWatts
        val previousAt = previousSampleAtNanos
        if (previousPower != null && previousAt != null) {
            val elapsed = nowNanos - previousAt
            // Only integrate contiguous, monotonic samples where both endpoints
            // show net battery discharge. This intentionally avoids overcounting
            // charge/discharge transitions and long periods without telemetry.
            if (elapsed in 1L..maxIntegrationGapNanos && previousPower < 0.0 && powerWatts < 0.0) {
                val averageDischargeWatts = -(previousPower + powerWatts) / 2.0
                val elapsedHours = elapsed.toDouble() / NANOS_PER_HOUR
                val incrementMWh = averageDischargeWatts * elapsedHours * 1000.0
                if (incrementMWh.isFinite() && incrementMWh >= 0.0) {
                    dischargedMilliWattHours += incrementMWh
                }
            }
        }

        previousPowerWatts = powerWatts
        previousSampleAtNanos = nowNanos
        validSampleCount++
        return snapshot(true, current, voltage, powerWatts)
    }

    @Synchronized
    fun reset() {
        previousPowerWatts = null
        previousSampleAtNanos = null
        dischargedMilliWattHours = 0.0
        validSampleCount = 0
    }

    private fun snapshot(
        available: Boolean,
        current: Long?,
        voltage: Int?,
        power: Double?
    ) = BatteryEnergySnapshot(
        available = available,
        currentMicroAmps = current,
        voltageMilliVolts = voltage,
        netPowerWatts = power,
        dischargedMilliWattHours = dischargedMilliWattHours,
        validSampleCount = validSampleCount
    )

    companion object {
        private const val MIN_VALID_CURRENT_MICRO_AMPS = -10_000_000L
        private const val MAX_VALID_CURRENT_MICRO_AMPS = 10_000_000L
        private const val MIN_VALID_VOLTAGE_MV = 2_500
        private const val MAX_VALID_VOLTAGE_MV = 5_000
        private const val MICROAMP_MILLIVOLT_PER_WATT = 1_000_000_000.0
        private const val NANOS_PER_HOUR = 3_600_000_000_000.0
        private const val DEFAULT_MAX_INTEGRATION_GAP_NANOS = 15_000_000_000L
    }
}
