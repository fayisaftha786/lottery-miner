package com.lottery.miner

/**
 * Immutable snapshot of a Stratum subscription's extranonce allocation.
 * Equality is based on both extranonce1 bytes and extranonce2 width.
 */
internal class ExtranonceAllocation(
    extranonce1: ByteArray,
    val extranonce2Size: Int
) {
    private val extranonce1Bytes = extranonce1.copyOf()

    fun matches(otherExtranonce1: ByteArray, otherExtranonce2Size: Int): Boolean =
        extranonce2Size == otherExtranonce2Size &&
            extranonce1Bytes.contentEquals(otherExtranonce1)
}
