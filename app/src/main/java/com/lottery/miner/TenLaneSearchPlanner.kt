package com.lottery.miner

import java.math.BigInteger

/**
 * Ten deterministic, non-overlapping extranonce2 search lanes.
 *
 * Each lane owns counters congruent to its worker index modulo workerCount.
 * A lane-specific affine permutation changes traversal order only; it does
 * not increase the probability of any individual SHA-256d hash.
 */
object TenLaneSearchPlanner {
    const val MAX_LANES = 10
    private val seeds = longArrayOf(1, 3, 5, 7, 11, 13, 17, 19, 23, 29)

    data class Lane(val workerIndex: Int, val workerCount: Int, val maxCounter: Long) {
        private val first = workerIndex.toLong()
        private val stride = workerCount.toLong()
        private val count: Long = if (first > maxCounter) 0L else (maxCounter - first) / stride + 1L
        private val modulus = BigInteger.valueOf(count.coerceAtLeast(1L))
        private val multiplier: BigInteger = coprimeMultiplier(seeds[workerIndex] % count.coerceAtLeast(1L), modulus)
        private val offset: BigInteger = BigInteger.valueOf((workerIndex.toLong() * 7L + 3L) % count.coerceAtLeast(1L))

        /** Returns the next unique extranonce2 counter, or null after this lane is exhausted. */
        fun counterAt(sequence: Long): Long? {
            require(sequence >= 0L)
            if (sequence >= count) return null
            val permuted = multiplier.multiply(BigInteger.valueOf(sequence))
                .add(offset).mod(modulus)
            return BigInteger.valueOf(first)
                .add(BigInteger.valueOf(stride).multiply(permuted))
                .longValueExact()
                .takeIf { it <= maxCounter }
        }

        fun capacity(): Long = count
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
        while (candidate.gcd(modulus) != BigInteger.ONE) {
            candidate = candidate.add(BigInteger.ONE)
        }
        return candidate
    }
}
