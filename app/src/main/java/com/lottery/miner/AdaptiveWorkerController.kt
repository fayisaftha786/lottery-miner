package com.lottery.miner

/**
 * Gradually ramps worker concurrency while safety limits remain authoritative.
 *
 * This controller optimizes a conservative sustained-load profile; it does not
 * claim watts-per-hash optimization because Android battery telemetry is not a
 * calibrated wall-power meter.
 */
class AdaptiveWorkerController(
    private val rampIntervalMillis: Long = DEFAULT_RAMP_INTERVAL_MILLIS
) {
    private var lastRampAtMillis: Long? = null

    init {
        require(rampIntervalMillis > 0L)
    }

    /**
     * Returns the next worker target. Safety reductions apply immediately;
     * increases add at most one worker per ramp interval.
     */
    @Synchronized
    fun selectWorkerCount(
        safetyWorkerLimit: Int,
        currentWorkerCount: Int,
        nowMillis: Long
    ): Int {
        require(safetyWorkerLimit >= 0)
        require(currentWorkerCount >= 0)
        require(nowMillis >= 0L)

        if (safetyWorkerLimit == 0) {
            lastRampAtMillis = null
            return 0
        }

        if (currentWorkerCount > safetyWorkerLimit) {
            lastRampAtMillis = nowMillis
            return safetyWorkerLimit
        }

        if (currentWorkerCount == 0) {
            lastRampAtMillis = nowMillis
            return 1.coerceAtMost(safetyWorkerLimit)
        }

        if (currentWorkerCount == safetyWorkerLimit) {
            if (lastRampAtMillis == null) lastRampAtMillis = nowMillis
            return currentWorkerCount
        }

        val previousRampAt = lastRampAtMillis ?: nowMillis.also { lastRampAtMillis = it }
        if (nowMillis - previousRampAt < rampIntervalMillis) {
            return currentWorkerCount
        }

        lastRampAtMillis = nowMillis
        return (currentWorkerCount + 1).coerceAtMost(safetyWorkerLimit)
    }

    companion object {
        const val DEFAULT_RAMP_INTERVAL_MILLIS = 2_000L
    }
}
