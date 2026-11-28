package com.lottery.miner

/**
 * Splits the complete uint32 nonce domain into disjoint arithmetic progressions.
 * Lane i checks i, i + workerCount, i + 2*workerCount, ...
 *
 * All lanes still use Bitcoin's one consensus SHA256d equation. This partitions
 * work; it does not create eight different proof-of-work equations.
 */
class NonceLanes(val workerCount: Int = DEFAULT_WORKERS) {
    companion object {
        const val DEFAULT_WORKERS = 8
        const val NONCE_SPACE = 4_294_967_296L
        const val MAX_NONCE = NONCE_SPACE - 1L
    }

    data class Batch(
        val workerId: Int,
        val startNonce: Long,
        val stride: Long,
        val count: Int,
        val firstOrdinal: Long
    ) {
        val lastNonce: Long
            get() = startNonce + (count - 1L) * stride
    }

    init {
        require(workerCount in 1..64) { "worker count must be between 1 and 64" }
    }

    fun nonceCount(workerId: Int): Long {
        require(workerId in 0 until workerCount)
        return ((MAX_NONCE - workerId) / workerCount) + 1L
    }

    fun nonceAt(workerId: Int, ordinal: Long): Long {
        require(ordinal >= 0L && ordinal < nonceCount(workerId)) { "nonce ordinal is outside this lane" }
        return workerId.toLong() + ordinal * workerCount.toLong()
    }

    /** Returns null when this lane has exhausted all uint32 nonces. */
    fun nextBatch(workerId: Int, nextOrdinal: Long, maxCount: Int): Batch? {
        require(workerId in 0 until workerCount)
        require(nextOrdinal >= 0L)
        require(maxCount in 1..1_000_000)
        val remaining = nonceCount(workerId) - nextOrdinal
        if (remaining <= 0L) return null
        val count = minOf(remaining, maxCount.toLong()).toInt()
        val start = nonceAt(workerId, nextOrdinal)
        val batch = Batch(workerId, start, workerCount.toLong(), count, nextOrdinal)
        check(batch.lastNonce <= MAX_NONCE) { "lane batch would wrap the uint32 nonce domain" }
        return batch
    }
}
