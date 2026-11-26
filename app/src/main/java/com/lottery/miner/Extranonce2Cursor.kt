package com.lottery.miner

import java.math.BigInteger

/**
 * Thread-safe, monotonically increasing Stratum extranonce2 allocator.
 *
 * Stratum V1 permits extranonce2 widths larger than a signed JVM Long. This
 * cursor therefore uses an unsigned fixed-width byte representation backed by
 * BigInteger. It emits every value exactly once, preserves leading zero bytes,
 * and returns null only after the complete negotiated allocation is exhausted.
 *
 * Reset only when the pool supplies a genuinely new extranonce allocation.
 */
internal class Extranonce2Cursor(
    initialSize: Int,
    initialValue: BigInteger = BigInteger.ZERO
) {
    private var sizeBytes: Int = validateSize(initialSize)
    private var nextValue: BigInteger = validateValue(initialValue, sizeBytes)
    private var exclusiveLimit: BigInteger = BigInteger.ONE.shiftLeft(sizeBytes * 8)

    @Synchronized
    fun reset(sizeBytes: Int) {
        val checkedSize = validateSize(sizeBytes)
        this.sizeBytes = checkedSize
        this.exclusiveLimit = BigInteger.ONE.shiftLeft(checkedSize * 8)
        this.nextValue = BigInteger.ZERO
    }

    /** Returns the next unique fixed-width big-endian extranonce2, or null when exhausted. */
    @Synchronized
    fun allocate(): ByteArray? {
        if (nextValue >= exclusiveLimit) return null

        val raw = nextValue.toByteArray()
        // BigInteger uses signed two's-complement; strip only its sign byte when present.
        val sourceStart = if (raw.size > sizeBytes) raw.size - sizeBytes else 0
        val length = raw.size - sourceStart
        val result = ByteArray(sizeBytes)
        System.arraycopy(raw, sourceStart, result, sizeBytes - length, length)
        nextValue = nextValue.add(BigInteger.ONE)
        return result
    }

    companion object {
        private fun validateSize(size: Int): Int {
            require(size in 1..16) { "extranonce2 size must be between 1 and 16 bytes" }
            return size
        }

        private fun validateValue(value: BigInteger, size: Int): BigInteger {
            require(value.signum() >= 0) { "extranonce2 cursor cannot start negative" }
            require(value < BigInteger.ONE.shiftLeft(size * 8)) {
                "initial extranonce2 value does not fit negotiated width"
            }
            return value
        }
    }
}
