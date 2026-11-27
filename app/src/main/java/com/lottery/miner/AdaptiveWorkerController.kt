package com.lottery.miner

/**
 * Empirically tunes worker count against the hashrate measured by the native
 * scanner. It never assumes that more threads are faster.
 *
 * The controller starts at one worker, tests one additional worker at a time,
 * and retains an increase only when the smoothed measured rate improves by at
 * least [minimumImprovementFraction] over a complete measurement window.
 * Battery/thermal/connection limits always override tuning immediately.
 */
class AdaptiveWorkerController(
    private val rampIntervalMillis: Long = DEFAULT_RAMP_INTERVAL_MILLIS,
    private val measurementWindowMillis: Long = DEFAULT_MEASUREMENT_WINDOW_MILLIS,
    private val minimumImprovementFraction: Double = DEFAULT_MINIMUM_IMPROVEMENT
) {
    private var lastRampAtMillis: Long? = null
    private var candidateStartedAtMillis: Long? = null
    private var candidateWorkerCount = 0
    private var candidateBaselineRate = 0.0
    private var bestWorkerCount = 0
    private var lastSafetyLimit: Int? = null
    private var safetyPaused = false
    private var tuningLocked = false

    @Volatile
    var statusMessage: String = "Waiting for an authorized mining job"
        private set

    init {
        require(rampIntervalMillis > 0L)
        require(measurementWindowMillis > 0L)
        require(minimumImprovementFraction in 0.0..1.0)
    }

    /**
     * Returns the empirically selected worker count, never above safetyWorkerLimit.
     * currentHashrate must be the real rolling rate from completed native scans.
     */
    @Synchronized
    fun selectWorkerCount(
        safetyWorkerLimit: Int,
        currentWorkerCount: Int,
        nowMillis: Long,
        currentHashrate: Double
    ): Int {
        require(safetyWorkerLimit >= 0)
        require(currentWorkerCount >= 0)
        require(nowMillis >= 0L)
        require(currentHashrate.isFinite() && currentHashrate >= 0.0) {
            "currentHashrate must be a finite non-negative measurement"
        }

        if (safetyWorkerLimit == 0) {
            if (!safetyPaused) {
                clearTuningState()
                safetyPaused = true
            }
            statusMessage = "Mining paused by battery, thermal, or job-safety policy"
            return 0
        }

        if (safetyPaused) {
            safetyPaused = false
            clearTuningState()
            lastRampAtMillis = nowMillis
            lastSafetyLimit = safetyWorkerLimit
        }

        val previousSafetyLimit = lastSafetyLimit
        if (previousSafetyLimit != null && safetyWorkerLimit != previousSafetyLimit) {
            candidateStartedAtMillis = null
            candidateWorkerCount = 0
            lastRampAtMillis = nowMillis
            if (safetyWorkerLimit > previousSafetyLimit) {
                // Cooling/telemetry recovery permits a fresh trial above the old cap.
                tuningLocked = false
                bestWorkerCount = currentWorkerCount.coerceAtLeast(1)
            } else if (bestWorkerCount > safetyWorkerLimit) {
                bestWorkerCount = safetyWorkerLimit
                tuningLocked = false
            }
        }
        lastSafetyLimit = safetyWorkerLimit

        if (currentWorkerCount > safetyWorkerLimit) {
            candidateStartedAtMillis = null
            candidateWorkerCount = 0
            lastRampAtMillis = nowMillis
            if (bestWorkerCount == 0 || bestWorkerCount > safetyWorkerLimit) {
                bestWorkerCount = safetyWorkerLimit
            }
            statusMessage = "Safety cap applied immediately: $safetyWorkerLimit workers"
            return safetyWorkerLimit
        }

        if (currentWorkerCount == 0) {
            candidateStartedAtMillis = null
            candidateWorkerCount = 0
            tuningLocked = false
            bestWorkerCount = 1.coerceAtMost(safetyWorkerLimit)
            lastRampAtMillis = nowMillis
            statusMessage = "Starting conservative baseline: 1 worker"
            return bestWorkerCount
        }

        val candidateAt = candidateStartedAtMillis
        if (candidateAt != null) {
            if (currentWorkerCount != candidateWorkerCount) {
                // A safety decision or external state change superseded this trial.
                candidateStartedAtMillis = null
                candidateWorkerCount = 0
                lastRampAtMillis = nowMillis
            } else if (nowMillis - candidateAt < measurementWindowMillis) {
                statusMessage = "Measuring $candidateWorkerCount workers against the previous measured rate"
                return currentWorkerCount
            } else {
                val requiredRate = candidateBaselineRate * (1.0 + minimumImprovementFraction)
                if (candidateBaselineRate > 0.0 && currentHashrate >= requiredRate) {
                    bestWorkerCount = candidateWorkerCount
                    candidateStartedAtMillis = null
                    candidateWorkerCount = 0
                    lastRampAtMillis = nowMillis
                    statusMessage = "Accepted $bestWorkerCount workers: measured rate improved by at least " +
                        "${(minimumImprovementFraction * 100.0).toInt()}%"
                    return currentWorkerCount
                } else {
                    val rejectedWorkers = candidateWorkerCount
                    candidateStartedAtMillis = null
                    candidateWorkerCount = 0
                    tuningLocked = true
                    val retained = bestWorkerCount.coerceIn(1, safetyWorkerLimit)
                    statusMessage = "Retained $retained workers; trial at $rejectedWorkers workers " +
                        "did not improve measured hashrate enough"
                    return retained
                }
            }
        }

        if (tuningLocked) {
            val retained = bestWorkerCount.coerceIn(1, safetyWorkerLimit)
            statusMessage = "Using measured best: $retained workers"
            return retained
        }

        if (currentWorkerCount >= safetyWorkerLimit) {
            if (bestWorkerCount == 0 || currentWorkerCount > bestWorkerCount) {
                bestWorkerCount = currentWorkerCount
            }
            statusMessage = "Using $currentWorkerCount workers (configured/safety limit)"
            return currentWorkerCount
        }

        val lastRamp = lastRampAtMillis ?: nowMillis.also { lastRampAtMillis = it }
        if (nowMillis - lastRamp < rampIntervalMillis) {
            statusMessage = "Stable at $currentWorkerCount workers; waiting before next trial"
            return currentWorkerCount
        }

        if (currentHashrate <= 0.0) {
            statusMessage = "Waiting for a valid non-zero hashrate sample before tuning"
            return currentWorkerCount
        }

        candidateWorkerCount = currentWorkerCount + 1
        candidateBaselineRate = currentHashrate
        candidateStartedAtMillis = nowMillis
        statusMessage = "Testing $candidateWorkerCount workers against $currentWorkerCount-worker baseline"
        return candidateWorkerCount
    }

    private fun clearTuningState() {
        lastRampAtMillis = null
        candidateStartedAtMillis = null
        candidateWorkerCount = 0
        candidateBaselineRate = 0.0
        bestWorkerCount = 0
        tuningLocked = false
    }

    companion object {
        const val DEFAULT_RAMP_INTERVAL_MILLIS = 45_000L
        const val DEFAULT_MEASUREMENT_WINDOW_MILLIS = 30_000L
        const val DEFAULT_MINIMUM_IMPROVEMENT = 0.03
    }
}
