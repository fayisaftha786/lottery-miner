package com.lottery.miner

/**
 * Pure policy for Android's device-wide thermal status.
 *
 * Battery temperature and Android thermal status are different measurements:
 * the latter may reflect SoC, skin, charging and other device sensors.
 */
internal data class ThermalWorkerDecision(
    val workerCount: Int,
    val maximumAllowedWorkers: Int,
    val statusLabel: String,
    val reason: String
)

internal object ThermalWorkerPolicy {
    const val UNKNOWN = -1
    private const val NONE = 0
    private const val LIGHT = 1
    private const val MODERATE = 2
    private const val SEVERE = 3
    private const val CRITICAL = 4
    private const val EMERGENCY = 5
    private const val SHUTDOWN = 6

    fun label(status: Int?): String = when (status) {
        null -> "NOT AVAILABLE"
        UNKNOWN -> "UNKNOWN"
        NONE -> "NONE"
        LIGHT -> "LIGHT"
        MODERATE -> "MODERATE"
        SEVERE -> "SEVERE"
        CRITICAL -> "CRITICAL"
        EMERGENCY -> "EMERGENCY"
        SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN ($status)"
    }

    fun evaluate(
        configuredWorkers: Int,
        requestedWorkers: Int,
        thermalStatus: Int
    ): ThermalWorkerDecision {
        require(configuredWorkers >= 1) { "configuredWorkers must be positive" }
        require(requestedWorkers in 0..configuredWorkers) {
            "requestedWorkers must be within the configured worker range"
        }

        val statusLabel = label(thermalStatus)
        val maximumAllowed = when {
            thermalStatus == UNKNOWN -> 1
            thermalStatus >= SEVERE -> 0
            thermalStatus >= MODERATE -> maxOf(1, configuredWorkers / 2)
            else -> configuredWorkers
        }
        val effective = minOf(requestedWorkers, maximumAllowed)
        val reason = when {
            thermalStatus == UNKNOWN ->
                "Android thermal status unavailable; safe worker cap $maximumAllowed/$configuredWorkers"
            thermalStatus >= SEVERE ->
                "Android thermal $statusLabel; mining paused for device protection"
            thermalStatus >= MODERATE ->
                "Android thermal MODERATE; worker cap $maximumAllowed/$configuredWorkers"
            else -> ""
        }

        return ThermalWorkerDecision(
            workerCount = effective,
            maximumAllowedWorkers = maximumAllowed,
            statusLabel = statusLabel,
            reason = reason
        )
    }
}
