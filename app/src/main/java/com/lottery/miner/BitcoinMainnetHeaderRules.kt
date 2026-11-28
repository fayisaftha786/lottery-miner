package com.lottery.miner

/**
 * Contextual Bitcoin Mainnet header rules for extending an already validated chain.
 * The caller must supply trusted ancestry; this is not a full block/script validator.
 */
object BitcoinMainnetHeaderRules {
    const val DIFFICULTY_INTERVAL = 2_016L
    const val TARGET_TIMESPAN_SECONDS = 1_209_600L
    const val MAX_FUTURE_BLOCK_TIME_SECONDS = 7_200L
    const val BIP34_ACTIVATION_HEIGHT = 227_931L
    const val BIP66_ACTIVATION_HEIGHT = 363_725L
    const val BIP65_ACTIVATION_HEIGHT = 388_381L

    fun minimumBlockVersion(height: Long): Long {
        require(height >= 0L)
        return when {
            height >= BIP65_ACTIVATION_HEIGHT -> 4L
            height >= BIP66_ACTIVATION_HEIGHT -> 3L
            height >= BIP34_ACTIVATION_HEIGHT -> 2L
            else -> 1L
        }
    }

    fun medianTimePast(previousTimestamps: List<Long>): Long {
        require(previousTimestamps.isNotEmpty() && previousTimestamps.size <= 11) {
            "MTP needs 1..11 previous block timestamps"
        }
        require(previousTimestamps.all { it in 0..0xffffffffL }) { "timestamp outside uint32" }
        return previousTimestamps.sorted()[previousTimestamps.size / 2]
    }

    fun checkTimestamp(candidateTime: Long, previousTimestamps: List<Long>, nowSeconds: Long) {
        require(candidateTime in 0..0xffffffffL && nowSeconds >= 0L)
        val mtp = medianTimePast(previousTimestamps)
        require(candidateTime > mtp) { "block timestamp must be greater than Median Time Past" }
        require(candidateTime <= nowSeconds + MAX_FUTURE_BLOCK_TIME_SECONDS) {
            "block timestamp is more than two hours in the future"
        }
    }

    fun expectedBits(
        nextHeight: Long,
        previousBits: Long,
        previousTimestamp: Long,
        firstIntervalTimestamp: Long? = null
    ): Long {
        require(nextHeight > 0L)
        BitcoinHeader.compactToTarget(previousBits)
        if (nextHeight % DIFFICULTY_INTERVAL != 0L) return previousBits
        val first = requireNotNull(firstIntervalTimestamp) {
            "retarget boundary requires timestamp from height nextHeight - 2016"
        }
        require(previousTimestamp in 0..0xffffffffL && first in 0..0xffffffffL)
        return BitcoinHeader.calculateRetarget(previousBits, previousTimestamp - first)
    }

    fun validateNextHeader(
        rawHeader: ByteArray,
        nextHeight: Long,
        previousHeader: BitcoinHeader.Parsed,
        previousTimestamps: List<Long>,
        nowSeconds: Long,
        firstIntervalTimestamp: Long? = null
    ): BitcoinHeader.Parsed {
        require(nextHeight > 0L)
        val parsed = BitcoinHeader.parseAndCheck(rawHeader)
        require(parsed.previousHash.contentEquals(previousHeader.hashInternal)) {
            "header does not link to the selected previous block"
        }
        val wantedBits = expectedBits(
            nextHeight, previousHeader.bits, previousHeader.timestamp, firstIntervalTimestamp
        )
        require(parsed.bits == wantedBits) { "header nBits is not the Mainnet difficulty for this height" }
        checkTimestamp(parsed.timestamp, previousTimestamps, nowSeconds)
        val minVersion = minimumBlockVersion(nextHeight)
        require(parsed.version in minVersion..0x7fffffffL) {
            "block version is below the activated Mainnet buried-softfork floor"
        }
        return parsed
    }
}
