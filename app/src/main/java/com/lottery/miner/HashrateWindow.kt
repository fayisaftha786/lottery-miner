package com.lottery.miner

import java.util.ArrayDeque

internal data class HashrateWindowReading(
    val hashRateHps: Double,
    val windowSeconds: Double
)

/**
 * Calculates a real rolling hashrate from cumulative native hashes and a
 * monotonic clock. When the app has been running for less than the window,
 * the returned average covers only the observed portion of the session.
 */
internal class HashrateWindow(
    private val windowNanos: Long = DEFAULT_WINDOW_NANOS,
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES
) {
    private data class Sample(val atNanos: Long, val hashes: Long)
    private val samples = ArrayDeque<Sample>()

    init {
        require(windowNanos > 0L)
        require(maxSamples >= 2)
    }

    @Synchronized
    fun record(cumulativeHashes: Long, atNanos: Long): HashrateWindowReading {
        require(cumulativeHashes >= 0L)

        val previous = samples.peekLast()
        if (previous != null &&
            (atNanos < previous.atNanos || cumulativeHashes < previous.hashes)
        ) {
            // A clock/counter reset starts a new measurement window.
            samples.clear()
        }

        val last = samples.peekLast()
        if (last != null && atNanos == last.atNanos) {
            // Keep one authoritative sample per timestamp.
            samples.removeLast()
        }
        samples.addLast(Sample(atNanos, cumulativeHashes))

        val cutoff = atNanos - windowNanos
        while (samples.size > 1 && samples.peekFirst().atNanos < cutoff) {
            samples.removeFirst()
        }
        while (samples.size > maxSamples) samples.removeFirst()

        if (samples.size < 2) return HashrateWindowReading(0.0, 0.0)
        val first = samples.peekFirst()
        val newest = samples.peekLast()
        val elapsedNanos = newest.atNanos - first.atNanos
        if (elapsedNanos <= 0L || newest.hashes < first.hashes) {
            return HashrateWindowReading(0.0, 0.0)
        }

        val elapsedSeconds = elapsedNanos / 1_000_000_000.0
        val rate = (newest.hashes - first.hashes).toDouble() / elapsedSeconds
        return HashrateWindowReading(
            hashRateHps = rate.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
            windowSeconds = elapsedSeconds
        )
    }

    companion object {
        const val DEFAULT_WINDOW_NANOS = 300_000_000_000L
        const val DEFAULT_MAX_SAMPLES = 181
    }
}
