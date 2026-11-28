package com.lottery.miner

/**
 * Estimates the probability that one miner finds a Bitcoin mainnet block.
 *
 * Difficulty D corresponds to D * 2^32 expected hashes per block. The
 * Poisson model gives P(at least one block in t seconds) = 1 - exp(-H*t/N).
 * A pool feedback share is deliberately not used in this calculation.
 */
data class BlockChanceEstimate(
    val expectedMeanTimeSeconds: Double,
    val chance1Hour: Double,
    val chance24Hours: Double,
    val chance7Days: Double,
    val chance30Days: Double,
    val hashrateFor1PercentIn24Hours: Double,
    val hashrateFor50PercentIn24Hours: Double
)

object BlockChanceEngine {
    const val DIFFICULTY_ONE_EXPECTED_HASHES = 4_294_967_296.0
    const val SECONDS_PER_HOUR = 3_600.0
    const val SECONDS_PER_DAY = 86_400.0

    fun calculate(hashrateHps: Double, networkDifficulty: Double): BlockChanceEstimate {
        require(hashrateHps.isFinite() && hashrateHps > 0.0) {
            "Hashrate must be positive and finite"
        }
        require(networkDifficulty.isFinite() && networkDifficulty > 0.0) {
            "Network difficulty must be positive and finite"
        }
        val expectedHashesPerBlock = networkDifficulty * DIFFICULTY_ONE_EXPECTED_HASHES
        require(expectedHashesPerBlock.isFinite() && expectedHashesPerBlock > 0.0) {
            "Difficulty target cannot be represented"
        }
        val meanTime = expectedHashesPerBlock / hashrateHps
        return BlockChanceEstimate(
            expectedMeanTimeSeconds = meanTime,
            chance1Hour = probabilityFor(hashrateHps, networkDifficulty, SECONDS_PER_HOUR),
            chance24Hours = probabilityFor(hashrateHps, networkDifficulty, SECONDS_PER_DAY),
            chance7Days = probabilityFor(hashrateHps, networkDifficulty, 7.0 * SECONDS_PER_DAY),
            chance30Days = probabilityFor(hashrateHps, networkDifficulty, 30.0 * SECONDS_PER_DAY),
            hashrateFor1PercentIn24Hours = requiredHashrateForProbability(
                networkDifficulty, SECONDS_PER_DAY, 0.01
            ),
            hashrateFor50PercentIn24Hours = requiredHashrateForProbability(
                networkDifficulty, SECONDS_PER_DAY, 0.50
            )
        )
    }

    fun probabilityFor(hashrateHps: Double, networkDifficulty: Double, durationSeconds: Double): Double {
        require(hashrateHps.isFinite() && hashrateHps > 0.0)
        require(networkDifficulty.isFinite() && networkDifficulty > 0.0)
        require(durationSeconds.isFinite() && durationSeconds > 0.0)
        val expectedHashesPerBlock = networkDifficulty * DIFFICULTY_ONE_EXPECTED_HASHES
        require(expectedHashesPerBlock.isFinite() && expectedHashesPerBlock > 0.0)
        val expectedBlocks = (hashrateHps / expectedHashesPerBlock) * durationSeconds
        if (!expectedBlocks.isFinite() || expectedBlocks >= 745.0) return 1.0
        return (-Math.expm1(-expectedBlocks)).coerceIn(0.0, 1.0)
    }

    /** Hashrate needed for the requested probability of at least one block in a time window. */
    fun requiredHashrateForProbability(
        networkDifficulty: Double,
        durationSeconds: Double,
        probability: Double
    ): Double {
        require(networkDifficulty.isFinite() && networkDifficulty > 0.0)
        require(durationSeconds.isFinite() && durationSeconds > 0.0)
        require(probability.isFinite() && probability > 0.0 && probability < 1.0)
        val expectedHashesPerBlock = networkDifficulty * DIFFICULTY_ONE_EXPECTED_HASHES
        require(expectedHashesPerBlock.isFinite() && expectedHashesPerBlock > 0.0)
        return (expectedHashesPerBlock / durationSeconds) * -Math.log1p(-probability)
    }
}
