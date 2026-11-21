package com.lottery.miner

/**
 * A candidate is selectable only when its native implementation is present and
 * the live economics snapshot is fresh. Algorithm hashrates are never compared
 * across different algorithms.
 */
data class AlgorithmCandidate(
    val id: String,
    val displayName: String,
    val algorithm: String,
    val installed: Boolean,
    val deviceBenchmarkHps: Double? = null
)

data class MiningEconomicsInput(
    val deviceHashrate: Double,
    val networkHashrate: Double,
    val dailyNetworkRewardCoins: Double,
    val coinPriceUsd: Double,
    val powerWatts: Double,
    val electricityUsdPerKwh: Double,
    val poolFeePercent: Double,
    val snapshotAgeSeconds: Long
)

data class MiningEconomicsResult(
    val grossUsdPerDay: Double,
    val electricityUsdPerDay: Double,
    val poolFeeUsdPerDay: Double,
    val netUsdPerDay: Double,
    val isFresh: Boolean
)

object MiningStrategy {
    const val MAX_SNAPSHOT_AGE_SECONDS = 900L

    fun estimate(input: MiningEconomicsInput): MiningEconomicsResult {
        require(input.deviceHashrate.isFinite() && input.deviceHashrate > 0.0)
        require(input.networkHashrate.isFinite() && input.networkHashrate > 0.0)
        require(input.dailyNetworkRewardCoins.isFinite() && input.dailyNetworkRewardCoins >= 0.0)
        require(input.coinPriceUsd.isFinite() && input.coinPriceUsd >= 0.0)
        require(input.powerWatts.isFinite() && input.powerWatts >= 0.0)
        require(input.electricityUsdPerKwh.isFinite() && input.electricityUsdPerKwh >= 0.0)
        require(input.poolFeePercent.isFinite() && input.poolFeePercent in 0.0..100.0)
        require(input.snapshotAgeSeconds >= 0L)

        val share = (input.deviceHashrate / input.networkHashrate).coerceIn(0.0, 1.0)
        val gross = share * input.dailyNetworkRewardCoins * input.coinPriceUsd
        val fee = gross * input.poolFeePercent / 100.0
        val electricity = input.powerWatts / 1000.0 * 24.0 * input.electricityUsdPerKwh
        val fresh = input.snapshotAgeSeconds <= MAX_SNAPSHOT_AGE_SECONDS
        return MiningEconomicsResult(
            grossUsdPerDay = gross,
            electricityUsdPerDay = electricity,
            poolFeeUsdPerDay = fee,
            netUsdPerDay = if (fresh) gross - fee - electricity else Double.NaN,
            isFresh = fresh
        )
    }

    fun chooseBest(
        candidates: List<Pair<AlgorithmCandidate, MiningEconomicsResult>>
    ): AlgorithmCandidate? = candidates
        .asSequence()
        .filter { (candidate, result) ->
            candidate.installed && result.isFresh &&
                candidate.deviceBenchmarkHps != null &&
                candidate.deviceBenchmarkHps > 0.0 &&
                result.netUsdPerDay.isFinite() && result.netUsdPerDay > 0.0
        }
        .maxByOrNull { it.second.netUsdPerDay }
        ?.first
}

/** Only SHA-256d is currently implemented in this app. Other entries are research targets. */
object MiningAlgorithms {
    val candidates = listOf(
        AlgorithmCandidate("sha256d", "Bitcoin SHA-256d", "SHA-256d", installed = true),
        AlgorithmCandidate("randomx", "Monero RandomX", "RandomX", installed = false),
        AlgorithmCandidate("verushash", "VerusHash 2.2", "VerusHash", installed = false),
        AlgorithmCandidate("ghostrider", "Raptoreum GhostRider", "GhostRider", installed = false),
        AlgorithmCandidate("astrobwt", "DERO AstroBWT", "AstroBWT", installed = false)
    )
}
