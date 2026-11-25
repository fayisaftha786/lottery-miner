package com.lottery.miner

/**
 * Per-worker scan batching controller.
 *
 * The native call size is adjusted using measured elapsed time. Small batches
 * reduce stale-job/stop latency; larger batches amortize JNI and array-copy
 * overhead. It changes only the size of a contiguous nonce range, never the
 * order or coverage of that worker's nonce space.
 */
internal class AdaptiveScanChunkController(
    initialChunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val minChunkSize: Int = MIN_CHUNK_SIZE,
    private val maxChunkSize: Int = MAX_CHUNK_SIZE,
    private val fastThresholdNanos: Long = FAST_THRESHOLD_NANOS,
    private val slowThresholdNanos: Long = SLOW_THRESHOLD_NANOS,
    private val fastSamplesToGrow: Int = 4,
    private val slowSamplesToShrink: Int = 2
) {
    private var chunkSize = initialChunkSize
    private var fastSamples = 0
    private var slowSamples = 0

    init {
        require(minChunkSize > 0 && minChunkSize <= initialChunkSize) {
            "minChunkSize must be positive and no greater than initialChunkSize"
        }
        require(initialChunkSize <= maxChunkSize) {
            "initialChunkSize must not exceed maxChunkSize"
        }
        require(fastThresholdNanos > 0L && slowThresholdNanos > fastThresholdNanos)
        require(fastSamplesToGrow > 0 && slowSamplesToShrink > 0)
    }

    @Synchronized
    fun currentChunkSize(): Int = chunkSize

    /**
     * Feed the elapsed time for one completed scanRange call, including any
     * overflow-recovery rescan. Returns the next batch size.
     */
    @Synchronized
    fun observeElapsedNanos(elapsedNanos: Long): Int {
        require(elapsedNanos >= 0L) { "elapsedNanos must not be negative" }

        when {
            elapsedNanos < fastThresholdNanos -> {
                fastSamples++
                slowSamples = 0
                if (fastSamples >= fastSamplesToGrow && chunkSize < maxChunkSize) {
                    chunkSize = minOf(maxChunkSize, chunkSize + maxOf(1, chunkSize / 2))
                    fastSamples = 0
                }
            }
            elapsedNanos > slowThresholdNanos -> {
                slowSamples++
                fastSamples = 0
                if (slowSamples >= slowSamplesToShrink && chunkSize > minChunkSize) {
                    chunkSize = maxOf(minChunkSize, chunkSize - maxOf(1, chunkSize / 4))
                    slowSamples = 0
                }
            }
            else -> {
                // Ignore normal timing variation so the worker does not oscillate.
                fastSamples = 0
                slowSamples = 0
            }
        }
        return chunkSize
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 1 shl 15
        const val MIN_CHUNK_SIZE = 1 shl 13
        const val MAX_CHUNK_SIZE = 1 shl 16
        const val FAST_THRESHOLD_NANOS = 2_000_000L
        const val SLOW_THRESHOLD_NANOS = 8_000_000L
    }
}
