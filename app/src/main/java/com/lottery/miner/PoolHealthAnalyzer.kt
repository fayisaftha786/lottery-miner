package com.lottery.miner

data class PoolHealthSnapshot(
    val state: String,
    val jobAgeSeconds: Long?,
    val responseAgeSeconds: Long?,
    val accepted: Int,
    val rejected: Int,
    val submitted: Int,
    val unknown: Int,
    val dropped: Int,
    val acceptancePercent: Double?,
    val responseCoveragePercent: Double?,
    val hashratePerActiveWorker: Double?
)

/**
 * Describes observable pool/session health. It does not infer block proximity,
 * and it never treats a submitted or pending share as accepted.
 */
object PoolHealthAnalyzer {
    const val FRESH_JOB_MAX_AGE_SECONDS = 90L
    const val FRESH_RESPONSE_MAX_AGE_SECONDS = 180L

    fun analyze(
        connected: Boolean,
        authorizedAtMillis: Long,
        lastJobAtMillis: Long,
        lastShareResponseAtMillis: Long,
        nowMillis: Long,
        submitted: Int,
        accepted: Int,
        rejected: Int,
        unknown: Int,
        dropped: Int,
        hashrateHps: Double,
        activeWorkers: Int
    ): PoolHealthSnapshot {
        require(nowMillis >= 0L)
        require(listOf(submitted, accepted, rejected, unknown, dropped).all { it >= 0 })
        require(hashrateHps.isFinite() && hashrateHps >= 0.0)
        require(activeWorkers >= 0)
        require(accepted + rejected <= submitted) {
            "Resolved accepted/rejected responses cannot exceed submitted shares"
        }

        fun age(timestamp: Long): Long? =
            if (timestamp <= 0L || timestamp > nowMillis) null
            else (nowMillis - timestamp) / 1000L

        val jobAge = age(lastJobAtMillis)
        val responseAge = age(lastShareResponseAtMillis)
        val knownResponses = accepted + rejected
        val acceptance = if (knownResponses > 0) accepted.toDouble() / knownResponses * 100.0 else null
        val coverage = if (submitted > 0) knownResponses.toDouble() / submitted * 100.0 else null
        val state = when {
            !connected -> "DISCONNECTED"
            authorizedAtMillis <= 0L -> "CONNECTED · NOT AUTHORIZED"
            lastJobAtMillis <= 0L -> "AUTHORIZED · WAITING FOR JOB"
            jobAge == null -> "JOB TIMESTAMP INVALID"
            jobAge > FRESH_JOB_MAX_AGE_SECONDS -> "JOB MAY BE STALE"
            submitted > knownResponses && unknown > 0 -> "POOL RESPONSE INCOMPLETE"
            submitted > knownResponses -> "WAITING FOR SHARE RESPONSES"
            lastShareResponseAtMillis > 0L && responseAge != null &&
                responseAge > FRESH_RESPONSE_MAX_AGE_SECONDS -> "NO RECENT SHARE RESPONSE"
            else -> "POOL SESSION HEALTHY"
        }
        return PoolHealthSnapshot(
            state = state,
            jobAgeSeconds = jobAge,
            responseAgeSeconds = responseAge,
            accepted = accepted,
            rejected = rejected,
            submitted = submitted,
            unknown = unknown,
            dropped = dropped,
            acceptancePercent = acceptance,
            responseCoveragePercent = coverage,
            hashratePerActiveWorker = if (activeWorkers > 0 && hashrateHps > 0.0)
                hashrateHps / activeWorkers else null
        )
    }
}
