package com.lottery.miner

/** Two real rolling windows derived only from cumulative native hash counts. */
internal data class SustainedPerformanceReading(
    val fiveMinuteRateHps: Double,
    val fiveMinuteWindowSeconds: Double,
    val thirtyMinuteRateHps: Double,
    val thirtyMinuteWindowSeconds: Double
)

internal class SustainedPerformanceMonitor {
    private val recent = HashrateWindow()
    // 30 minutes sampled every ~2 seconds; retain enough points for the full window.
    private val sustained = HashrateWindow(
        windowNanos = THIRTY_MINUTES_NANOS,
        maxSamples = MAX_SUSTAINED_SAMPLES
    )

    fun record(cumulativeHashes: Long, atNanos: Long): SustainedPerformanceReading {
        val short = recent.record(cumulativeHashes, atNanos)
        val long = sustained.record(cumulativeHashes, atNanos)
        return SustainedPerformanceReading(
            fiveMinuteRateHps = short.hashRateHps,
            fiveMinuteWindowSeconds = short.windowSeconds,
            thirtyMinuteRateHps = long.hashRateHps,
            thirtyMinuteWindowSeconds = long.windowSeconds
        )
    }

    companion object {
        const val THIRTY_MINUTES_NANOS = 1_800_000_000_000L
        const val MAX_SUSTAINED_SAMPLES = 901
    }
}
