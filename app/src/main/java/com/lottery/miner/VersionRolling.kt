package com.lottery.miner

/**
 * BIP310 version-rolling mask and deterministic subset enumeration.
 *
 * The miner advertises only the general-purpose BIP320 mask. A pool may
 * return a smaller intersection; any malformed or out-of-range mask fails
 * closed to fixed-version mining.
 */
internal object VersionRolling {
    const val REQUESTED_MASK: Long = 0x1fffe000L

    fun parseMask(value: String?): Long? {
        if (value == null || !value.matches(Regex("[0-9a-fA-F]{8}"))) return null
        val parsed = value.toLong(16)
        return parsed.takeIf { (it and REQUESTED_MASK.inv()) == 0L }
    }

    fun variantCount(mask: Long): Long {
        require((mask and REQUESTED_MASK.inv()) == 0L) { "version mask exceeds the advertised miner mask" }
        return 1L shl java.lang.Long.bitCount(mask)
    }

    /** Maps ordinal 0..2^popcount(mask)-1 to a unique submask. */
    fun variantAt(mask: Long, ordinal: Long): Long {
        val count = variantCount(mask)
        require(ordinal >= 0L && ordinal < count) { "version variant ordinal is out of range" }
        var remaining = ordinal
        var result = 0L
        for (bit in 0..31) {
            val flag = 1L shl bit
            if ((mask and flag) != 0L) {
                if ((remaining and 1L) != 0L) result = result or flag
                remaining = remaining ushr 1
            }
        }
        return result
    }
}
