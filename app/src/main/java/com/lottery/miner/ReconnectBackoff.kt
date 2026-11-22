package com.lottery.miner

/**
 * Exponential retry backoff for transient Stratum socket failures.
 *
 * A delay is reset only after a long, authenticated session that received
 * work. A connection that repeatedly aborts after a few minutes must not
 * keep reconnecting at the minimum delay.
 */
internal class ReconnectBackoff(
    private val initialDelayMs: Long = 5_000L,
    private val maxDelayMs: Long = 30_000L,
    private val resetAfterStableSessionMs: Long = 300_000L
) {
    private var nextDelayMs = initialDelayMs

    init {
        require(initialDelayMs > 0L)
        require(maxDelayMs >= initialDelayMs)
        require(resetAfterStableSessionMs > 0L)
    }

    @Synchronized
    fun nextDelayAfter(sessionDurationMs: Long, healthySession: Boolean): Long {
        require(sessionDurationMs >= 0L) { "Session duration cannot be negative" }
        if (healthySession && sessionDurationMs >= resetAfterStableSessionMs) {
            nextDelayMs = initialDelayMs
        }

        val delay = nextDelayMs
        nextDelayMs = if (nextDelayMs >= maxDelayMs / 2L) {
            maxDelayMs
        } else {
            minOf(maxDelayMs, nextDelayMs * 2L)
        }
        return delay
    }
}
