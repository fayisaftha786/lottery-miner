package com.lottery.miner

/**
 * A candidate may be selected only when its native implementation exists,
 * its device benchmark is real, and the market/network snapshot is fresh.
 * Hashrates from different algorithms are never compared directly.
 */
data class AlgorithmCandidate(
    val id: String,
    val displayName: String,
    val algorithm: String,
    val installed: Boolean,
    val deviceBenchmarkHps: Double? = null
)

enum class PayoutModel {
    /** Expected value of rare network-block payouts; not a daily cash payment. */
    SOLO_BLOCK_REWARD
}

data class MiningEconomicsInput(
    val deviceHashrate: Double,
    val networkHashrate: Double,
    val dailyNetworkRewardCoins: Double,
    val coinPriceFiat: Double,
    val powerWatts: Double,
    val electricityFiatPerKwh: Double,
    val snapshotAgeSeconds: Long,
    val networkBlocksPerDay: Double = 144.0,
    val payoutModel: PayoutModel = PayoutModel.SOLO_BLOCK_REWARD
)

data class MiningEconomicsResult(
    val grossFiatPerDay: Double,
    val electricityFiatPerDay: Double,
    val netFiatPerDay: Double,
    val expectedBlocksFoundPerDay: Double,
    val expectedDaysPerBlock: Double,
    val isFresh: Boolean,
    val payoutModel: PayoutModel
)

object MiningStrategy {
    const val MAX_SNAPSHOT_AGE_SECONDS = 900L

    fun estimate(input: MiningEconomicsInput): MiningEconomicsResult {
        require(input.deviceHashrate.isFinite() && input.deviceHashrate > 0.0)
        require(input.networkHashrate.isFinite() && input.networkHashrate > 0.0)
        require(input.dailyNetworkRewardCoins.isFinite() && input.dailyNetworkRewardCoins >= 0.0)
        require(input.coinPriceFiat.isFinite() && input.coinPriceFiat >= 0.0)
        require(input.powerWatts.isFinite() && input.powerWatts >= 0.0)
        require(input.electricityFiatPerKwh.isFinite() && input.electricityFiatPerKwh >= 0.0)
        require(input.snapshotAgeSeconds >= 0L)
        require(input.networkBlocksPerDay.isFinite() && input.networkBlocksPerDay > 0.0)

        val share = (input.deviceHashrate / input.networkHashrate).coerceIn(0.0, 1.0)
        val gross = share * input.dailyNetworkRewardCoins * input.coinPriceFiat
        val electricity = input.powerWatts / 1000.0 * 24.0 * input.electricityFiatPerKwh
        val fresh = input.snapshotAgeSeconds <= MAX_SNAPSHOT_AGE_SECONDS
        val expectedBlocksPerDay = share * input.networkBlocksPerDay
        val expectedDaysPerBlock = if (expectedBlocksPerDay > 0.0) {
            1.0 / expectedBlocksPerDay
        } else {
            Double.POSITIVE_INFINITY
        }
        return MiningEconomicsResult(
            grossFiatPerDay = gross,
            electricityFiatPerDay = electricity,
            netFiatPerDay = if (fresh) gross - electricity else Double.NaN,
            expectedBlocksFoundPerDay = expectedBlocksPerDay,
            expectedDaysPerBlock = expectedDaysPerBlock,
            isFresh = fresh,
            payoutModel = input.payoutModel
        )
    }

    fun chooseBest(
        candidates: List<Pair<AlgorithmCandidate, MiningEconomicsResult>>
    ): AlgorithmCandidate? = candidates
        .asSequence()
        .filter { (candidate, result) ->
            candidate.installed && result.isFresh &&
                candidate.deviceBenchmarkHps != null &&
                candidate.deviceBenchmarkHps.isFinite() &&
                candidate.deviceBenchmarkHps > 0.0 &&
                result.netFiatPerDay.isFinite() && result.netFiatPerDay > 0.0
        }
        .maxByOrNull { it.second.netFiatPerDay }
        ?.first
}

/** Only SHA-256d is currently implemented in this app. Other entries are research targets. */
object MiningAlgorithms {
    val candidates = listOf(
        AlgorithmCandidate("sha256d", "Bitcoin SHA-256d", "SHA-256d", installed = true),
        AlgorithmCandidate("verushash", "VerusHash 2.2", "VerusHash", installed = false),
        AlgorithmCandidate("randomx", "Monero RandomX", "RandomX", installed = false),
        AlgorithmCandidate("scrypt", "Scrypt", "Scrypt", installed = false),
        AlgorithmCandidate("ghostrider", "Raptoreum GhostRider", "GhostRider", installed = false)
    )
}
