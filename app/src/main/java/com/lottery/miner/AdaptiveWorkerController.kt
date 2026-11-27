package com.lottery.miner

/**
 * Empirically tunes worker count using averages of real rolling native-scan
 * hashrate samples. It never assumes that more threads are faster.
 *
 * The controller starts with one worker, gathers a stable baseline, tests one
 * additional worker for a full window, and retains it only when the measured
 * average improves by the configured margin. Safety limits always win.
 */
class AdaptiveWorkerController(
    private val rampIntervalMillis: Long = DEFAULT_RAMP_INTERVAL_MILLIS,
    private val measurementWindowMillis: Long = DEFAULT_MEASUREMENT_WINDOW_MILLIS,
    private val minimumImprovementFraction: Double = DEFAULT_MINIMUM_IMPROVEMENT
) {
    private var lastRampAtMillis: Long? = null
    private var lastSafetyLimit: Int? = null
    private var safetyPaused = false
    private var tuningLocked = false

    private var stableWorkerCount = 0
    private var stableRateSum = 0.0
    private var stableRateSamples = 0

    private var candidateStartedAtMillis: Long? = null
    private var candidateWorkerCount = 0
    private var candidateBaselineRate = 0.0
    private var trialRateSum = 0.0
    private var trialRateSamples = 0

    private var bestWorkerCount = 0

    @Volatile
    var statusMessage: String = "Waiting for an authorized mining job"
        private set

    init {
        require(rampIntervalMillis > 0L)
        require(measurementWindowMillis > 0L)
        require(minimumImprovementFraction in 0.0..1.0)
    }

    /**
     * Returns an empirically selected worker count, never above safetyWorkerLimit.
     * currentHashrate must be the finite rolling rate from completed native scans.
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
            cancelCandidate()
            resetStableWindow(currentWorkerCount)
            lastRampAtMillis = nowMillis
            if (safetyWorkerLimit > previousSafetyLimit) {
                // A recovered thermal/battery limit permits a fresh trial.
                tuningLocked = false
                bestWorkerCount = currentWorkerCount.coerceAtLeast(1)
            } else if (bestWorkerCount > safetyWorkerLimit) {
                bestWorkerCount = safetyWorkerLimit
                tuningLocked = false
            }
        }
        lastSafetyLimit = safetyWorkerLimit

        if (currentWorkerCount > safetyWorkerLimit) {
            cancelCandidate()
            lastRampAtMillis = nowMillis
            resetStableWindow(safetyWorkerLimit)
            if (bestWorkerCount == 0 || bestWorkerCount > safetyWorkerLimit) {
                bestWorkerCount = safetyWorkerLimit
            }
            statusMessage = "Safety cap applied immediately: $safetyWorkerLimit workers"
            return safetyWorkerLimit
        }

        if (currentWorkerCount == 0) {
            cancelCandidate()
            tuningLocked = false
            bestWorkerCount = 1.coerceAtMost(safetyWorkerLimit)
            lastRampAtMillis = nowMillis
            resetStableWindow(bestWorkerCount)
            statusMessage = "Starting conservative baseline: 1 worker"
            return bestWorkerCount
        }

        val candidateAt = candidateStartedAtMillis
        if (candidateAt != null) {
            if (currentWorkerCount != candidateWorkerCount) {
                // A safety decision or external state change superseded the trial.
                cancelCandidate()
                lastRampAtMillis = nowMillis
                resetStableWindow(currentWorkerCount)
            } else {
                if (currentHashrate > 0.0) {
                    trialRateSum += currentHashrate
                    trialRateSamples++
                }
                if (nowMillis - candidateAt < measurementWindowMillis) {
                    statusMessage = "Measuring $candidateWorkerCount workers using real hashrate samples"
                    return currentWorkerCount
                }

                val trialAverage = if (trialRateSamples >= MIN_TRIAL_SAMPLES) {
                    trialRateSum / trialRateSamples
                } else {
                    0.0
                }
                val requiredAverage = candidateBaselineRate * (1.0 + minimumImprovementFraction)
                if (candidateBaselineRate > 0.0 && trialAverage >= requiredAverage) {
                    bestWorkerCount = candidateWorkerCount
                    cancelCandidate()
                    lastRampAtMillis = nowMillis
                    resetStableWindow(currentWorkerCount)
                    statusMessage = "Accepted $bestWorkerCount workers: window-average hashrate improved by at least " +
                        "${(minimumImprovementFraction * 100.0).toInt()}%"
                    return currentWorkerCount
                }

                val rejectedWorkers = candidateWorkerCount
                val retained = bestWorkerCount.coerceIn(1, safetyWorkerLimit)
                cancelCandidate()
                tuningLocked = true
                resetStableWindow(retained)
                statusMessage = "Retained $retained workers; $rejectedWorkers-worker average did not improve enough"
                return retained
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

        if (stableWorkerCount != currentWorkerCount) {
            resetStableWindow(currentWorkerCount)
            lastRampAtMillis = nowMillis
        }
        if (currentHashrate > 0.0) {
            stableRateSum += currentHashrate
            stableRateSamples++
        }

        val lastRamp = lastRampAtMillis ?: nowMillis.also { lastRampAtMillis = it }
        if (nowMillis - lastRamp < rampIntervalMillis) {
            statusMessage = "Stable at $currentWorkerCount workers; gathering baseline samples"
            return currentWorkerCount
        }

        if (stableRateSamples < MIN_BASELINE_SAMPLES) {
            statusMessage = "Waiting for enough real baseline hashrate samples"
            return currentWorkerCount
        }

        candidateBaselineRate = stableRateSum / stableRateSamples
        if (!candidateBaselineRate.isFinite() || candidateBaselineRate <= 0.0) {
            statusMessage = "Waiting for a valid non-zero baseline hashrate"
            return currentWorkerCount
        }

        candidateWorkerCount = currentWorkerCount + 1
        candidateStartedAtMillis = nowMillis
        trialRateSum = 0.0
        trialRateSamples = 0
        statusMessage = "Testing $candidateWorkerCount workers against the $currentWorkerCount-worker average"
        return candidateWorkerCount
    }

    private fun resetStableWindow(workerCount: Int) {
        stableWorkerCount = workerCount
        stableRateSum = 0.0
        stableRateSamples = 0
    }

    private fun cancelCandidate() {
        candidateStartedAtMillis = null
        candidateWorkerCount = 0
        candidateBaselineRate = 0.0
        trialRateSum = 0.0
        trialRateSamples = 0
    }

    private fun clearTuningState() {
        lastRampAtMillis = null
        lastSafetyLimit = null
        tuningLocked = false
        bestWorkerCount = 0
        stableWorkerCount = 0
        resetStableWindow(0)
        cancelCandidate()
    }

    companion object {
        const val DEFAULT_RAMP_INTERVAL_MILLIS = 45_000L
        const val DEFAULT_MEASUREMENT_WINDOW_MILLIS = 30_000L
        const val DEFAULT_MINIMUM_IMPROVEMENT = 0.03
        private const val MIN_BASELINE_SAMPLES = 5
        private const val MIN_TRIAL_SAMPLES = 3
    }
}
