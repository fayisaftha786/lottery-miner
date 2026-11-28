package com.lottery.miner

import java.math.BigInteger
import java.security.MessageDigest

/** Bitcoin 80-byte block-header parsing and proof-of-work arithmetic. */
object BitcoinHeader {
    private val TWO_256 = BigInteger.ONE.shiftLeft(256)
    private val MAINNET_POW_LIMIT = compactToTarget(0x1d00ffffL)
    data class Parsed(
        val raw: ByteArray, val version: Long, val previousHash: ByteArray,
        val merkleRoot: ByteArray, val timestamp: Long, val bits: Long,
        val nonce: Long, val hashInternal: ByteArray, val hashDisplay: String,
        val target: BigInteger, val chainWork: BigInteger
    )

    fun parseAndCheck(header: ByteArray, enforceMainnetPowLimit: Boolean = true): Parsed {
        require(header.size == 80) { "Bitcoin block header must be exactly 80 bytes" }
        val version = uint32le(header, 0)
        val prev = header.copyOfRange(4, 36)
        val merkle = header.copyOfRange(36, 68)
        val timestamp = uint32le(header, 68)
        val bits = uint32le(header, 72)
        val nonce = uint32le(header, 76)
        val target = compactToTarget(bits)
        if (enforceMainnetPowLimit) require(target <= MAINNET_POW_LIMIT) { "target exceeds Mainnet proof-of-work limit" }
        val hash = sha256d(header)
        val hashNumber = BigInteger(1, hash.reversedArray())
        require(hashNumber <= target) { "header does not meet its encoded proof-of-work target" }
        val work = TWO_256.divide(target.add(BigInteger.ONE))
        return Parsed(header.copyOf(), version, prev, merkle, timestamp, bits, nonce,
            hash, hash.reversedArray().joinToString("") { "%02x".format(it) }, target, work)
    }

    fun compactToTarget(compact: Long): BigInteger {
        require(compact in 0..0xffffffffL)
        val size = (compact ushr 24).toInt()
        val word = compact and 0x007fffffL
        require((compact and 0x00800000L) == 0L) { "negative compact target" }
        require(word != 0L) { "zero compact target" }
        val target = if (size <= 3) BigInteger.valueOf(word ushr (8 * (3 - size)))
            else BigInteger.valueOf(word).shiftLeft(8 * (size - 3))
        require(target.signum() > 0 && target < TWO_256) { "compact target overflow" }
        require(!(size > 34 || (word > 0xffL && size > 33) || (word > 0xffffL && size > 32))) {
            "compact target overflow"
        }
        return target
    }

    /** Caller must provide correct boundary history; this does not determine retarget height. */
    fun calculateRetarget(previousBits: Long, actualTimespanSeconds: Long): Long {
        val targetTimespan = 1_209_600L
        val clamped = actualTimespanSeconds.coerceIn(targetTimespan / 4, targetTimespan * 4)
        val adjusted = compactToTarget(previousBits).multiply(BigInteger.valueOf(clamped))
            .divide(BigInteger.valueOf(targetTimespan)).min(MAINNET_POW_LIMIT)
        return targetToCompact(adjusted)
    }

    fun targetToCompact(target: BigInteger): Long {
        require(target.signum() > 0 && target < TWO_256)
        var size = (target.bitLength() + 7) / 8
        var compact = if (size <= 3) target.toLong() shl (8 * (3 - size))
            else target.shiftRight(8 * (size - 3)).toLong()
        if ((compact and 0x00800000L) != 0L) { compact = compact ushr 8; size++ }
        return ((size.toLong() shl 24) or (compact and 0x007fffffL)) and 0xffffffffL
    }

    private fun uint32le(b: ByteArray, o: Int): Long {
        var value = 0L
        repeat(4) { value = value or ((b[o + it].toLong() and 0xffL) shl (8 * it)) }
        return value
    }
    private fun sha256d(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(digest.digest(data))
    }
}
