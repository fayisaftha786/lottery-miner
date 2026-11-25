package com.lottery.miner

/**
 * Uses Android's manufacturer-provided thermal headroom thresholds.
 * Headroom is meaningful for worker control only when the device supplies
 * matching thresholds. No universal thresholds are fabricated.
 */
internal data class ThermalHeadroomDecision(
    val workerCount: Int,
    val limitingStatus: Int?,
    val reason: String
)

internal object ThermalHeadroomPolicy {
    private const val LIGHT = 1
    private const val MODERATE = 2
    private const val SEVERE = 3
    private const val MAX_SUPPORTED_STATUS = 6

    fun evaluate(
        configuredWorkers: Int,
        requestedWorkers: Int,
        forecastHeadroom: Double?,
        manufacturerThresholds: Map<Int, Float>
    ): ThermalHeadroomDecision {
        require(configuredWorkers >= 1) { "configuredWorkers must be positive" }
        require(requestedWorkers in 0..configuredWorkers) {
            "requestedWorkers must be within the configured worker range"
        }
        if (requestedWorkers == 0) return ThermalHeadroomDecision(0, null, "")
        val headroom = forecastHeadroom
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return ThermalHeadroomDecision(requestedWorkers, null, "")
        val thresholds = manufacturerThresholds.filter { (status, value) ->
            status in LIGHT..MAX_SUPPORTED_STATUS &&
                value.isFinite() && value in 0.01f..2.0f
        }
        if (thresholds.isEmpty()) return ThermalHeadroomDecision(requestedWorkers, null, "")

        fun thresholdAtOrAbove(status: Int): Pair<Int, Float>? =
            thresholds.entries
                .filter { it.key >= status }
                .minByOrNull { it.key }
                ?.let { it.key to it.value }

        val severe = thresholdAtOrAbove(SEVERE)
        val moderate = thresholdAtOrAbove(MODERATE)
        val light = thresholdAtOrAbove(LIGHT)

        val limit = when {
            severe != null && headroom >= severe.second ->
                Triple(0, severe.first, "Device ${label(severe.first)} headroom threshold reached; mining paused")
            moderate != null && headroom >= moderate.second ->
                Triple(maxOf(1, configuredWorkers / 2), moderate.first, "Device ${label(moderate.first)} headroom threshold reached; workers capped")
            light != null && headroom >= light.second ->
                Triple(maxOf(1, (configuredWorkers * 3 + 3) / 4), light.first, "Device ${label(light.first)} headroom threshold reached; workers reduced")
            else -> null
        } ?: return ThermalHeadroomDecision(requestedWorkers, null, "")

        val effective = minOf(requestedWorkers, limit.first)
        return ThermalHeadroomDecision(
            workerCount = effective,
            limitingStatus = limit.second.takeIf { effective < requestedWorkers },
            reason = limit.third.takeIf { effective < requestedWorkers } ?: ""
        )
    }

    private fun label(status: Int): String = when (status) {
        1 -> "LIGHT"
        2 -> "MODERATE"
        3 -> "SEVERE"
        4 -> "CRITICAL"
        5 -> "EMERGENCY"
        6 -> "SHUTDOWN"
        else -> "UNKNOWN"
    }
}
