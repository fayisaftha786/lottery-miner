package com.lottery.miner

import java.math.BigInteger

/**
 * Ten deterministic, non-overlapping extranonce2 search lanes.
 * Affine permutations change traversal order, never per-hash odds.
 */
object TenLaneSearchPlanner {
    const val MAX_LANES = 10
    private val seeds = longArrayOf(1, 3, 5, 7, 11, 13, 17, 19, 23, 29)

    data class Lane(val workerIndex: Int, val workerCount: Int, val maxCounter: Long) {
        private val first = workerIndex.toLong()
        private val stride = workerCount.toLong()
        private val count: BigInteger = if (first > maxCounter) BigInteger.ZERO else
            BigInteger.valueOf(maxCounter).subtract(BigInteger.valueOf(first))
                .divide(BigInteger.valueOf(stride)).add(BigInteger.ONE)
        private val modulus = count.max(BigInteger.ONE)
        private val multiplier = coprimeMultiplier(seeds[workerIndex] % modulus.toLong().coerceAtLeast(1L), modulus)
        private val offset = BigInteger.valueOf((workerIndex.toLong() * 7L + 3L)).mod(modulus)

        fun counterAt(sequence: Long): Long? {
            require(sequence >= 0L)
            val seq = BigInteger.valueOf(sequence)
            if (seq >= count) return null
            val permuted = multiplier.multiply(seq).add(offset).mod(modulus)
            val value = BigInteger.valueOf(first).add(BigInteger.valueOf(stride).multiply(permuted))
            return value.toLong().takeIf { it <= maxCounter }
        }

        fun capacity(): Long = count.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()
    }

    fun lane(workerIndex: Int, workerCount: Int, maxCounter: Long): Lane {
        require(workerCount in 1..MAX_LANES) { "Worker count must be between 1 and 10" }
        require(workerIndex in 0 until workerCount)
        require(maxCounter >= 0L)
        return Lane(workerIndex, workerCount, maxCounter)
    }

    private fun coprimeMultiplier(seed: Long, modulus: BigInteger): BigInteger {
        if (modulus == BigInteger.ONE) return BigInteger.ZERO
        var candidate = BigInteger.valueOf(seed.coerceAtLeast(1L))
        while (candidate.gcd(modulus) != BigInteger.ONE) candidate = candidate.add(BigInteger.ONE)
        return candidate
    }
}
