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
    SOLO_BLOCK_REWARD,
    /** Proportional share-based pool payout model. */
    PROPORTIONAL_POOL
}

data class MiningEconomicsInput(
    val deviceHashrate: Double,
    val networkHashrate: Double,
    val dailyNetworkRewardCoins: Double,
    val coinPriceFiat: Double,
    val powerWatts: Double,
    val electricityFiatPerKwh: Double,
    val poolFeePercent: Double,
    val snapshotAgeSeconds: Long,
    val networkBlocksPerDay: Double = 144.0,
    val payoutModel: PayoutModel = PayoutModel.SOLO_BLOCK_REWARD
)

data class MiningEconomicsResult(
    val grossFiatPerDay: Double,
    val electricityFiatPerDay: Double,
    val poolFeeFiatPerDay: Double,
    val netFiatPerDay: Double,
    val expectedBlocksFoundPerDay: Double,
    val expectedDaysPerBlock: Double,
    val blockChance1Day: Double,
    val blockChance30Days: Double,
    val blockChance365Days: Double,
    val hashrateForOnePercentChanceInOneYear: Double,
    val hashrateForFiftyPercentChanceInOneYear: Double,
    val hashrateForNinetyPercentChanceInOneYear: Double,
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
        require(input.poolFeePercent.isFinite() && input.poolFeePercent in 0.0..100.0)
        require(input.snapshotAgeSeconds >= 0L)
        require(input.networkBlocksPerDay.isFinite() && input.networkBlocksPerDay > 0.0)
        require(input.payoutModel == PayoutModel.SOLO_BLOCK_REWARD) {
            "Proportional pool estimates require pool-specific hashrate and payout data; network-wide data cannot be reused"
        }

        val share = (input.deviceHashrate / input.networkHashrate).coerceIn(0.0, 1.0)
        val gross = share * input.dailyNetworkRewardCoins * input.coinPriceFiat
        val fee = gross * input.poolFeePercent / 100.0
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
            poolFeeFiatPerDay = fee,
            netFiatPerDay = if (fresh) gross - fee - electricity else Double.NaN,
            expectedBlocksFoundPerDay = expectedBlocksPerDay,
            expectedDaysPerBlock = expectedDaysPerBlock,
            blockChance1Day = blockProbability(input.deviceHashrate, input.networkHashrate, 1.0, input.networkBlocksPerDay),
            blockChance30Days = blockProbability(input.deviceHashrate, input.networkHashrate, 30.0, input.networkBlocksPerDay),
            blockChance365Days = blockProbability(input.deviceHashrate, input.networkHashrate, 365.0, input.networkBlocksPerDay),
            hashrateForOnePercentChanceInOneYear = requiredHashrateForProbability(0.01, input.networkHashrate, 365.0, input.networkBlocksPerDay),
            hashrateForFiftyPercentChanceInOneYear = requiredHashrateForProbability(0.50, input.networkHashrate, 365.0, input.networkBlocksPerDay),
            hashrateForNinetyPercentChanceInOneYear = requiredHashrateForProbability(0.90, input.networkHashrate, 365.0, input.networkBlocksPerDay),
            isFresh = fresh,
            payoutModel = input.payoutModel
        )
    }

    /** Poisson approximation; assumes constant hashrates and average block interval. */
    fun blockProbability(
        deviceHashrate: Double,
        networkHashrate: Double,
        days: Double,
        blocksPerDay: Double = 144.0
    ): Double {
        require(deviceHashrate.isFinite() && deviceHashrate >= 0.0)
        require(networkHashrate.isFinite() && networkHashrate > 0.0)
        require(days.isFinite() && days >= 0.0)
        require(blocksPerDay.isFinite() && blocksPerDay > 0.0)
        val expectedBlocks = (deviceHashrate / networkHashrate) * blocksPerDay * days
        return (-Math.expm1(-expectedBlocks)).coerceIn(0.0, 1.0)
    }

    /** Required hashes/second for a target chance during the supplied horizon. */
    fun requiredHashrateForProbability(
        probability: Double,
        networkHashrate: Double,
        days: Double,
        blocksPerDay: Double = 144.0
    ): Double {
        require(probability.isFinite() && probability in 0.0..1.0)
        require(networkHashrate.isFinite() && networkHashrate > 0.0)
        require(days.isFinite() && days > 0.0)
        require(blocksPerDay.isFinite() && blocksPerDay > 0.0)
        if (probability == 0.0) return 0.0
        if (probability == 1.0) return Double.POSITIVE_INFINITY
        return -kotlin.math.ln1p(-probability) * networkHashrate / (blocksPerDay * days)
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
