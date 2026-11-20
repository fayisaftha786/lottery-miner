package com.lottery.miner

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.security.MessageDigest

object Util {
    val DIFF1: BigInteger = BigInteger.valueOf(0xFFFFL).shiftLeft(208)
    private val MAX256: BigInteger = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)

    fun hexToBytes(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length" }
        return ByteArray(s.length / 2) { i ->
            ((Character.digit(s[2 * i], 16) shl 4) or Character.digit(s[2 * i + 1], 16)).toByte()
        }
    }

    fun bytesToHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format("%02x", x.toInt() and 0xff))
        return sb.toString()
    }

    fun sha256d(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(data))
    }

    fun toBytes32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        val srcStart = if (raw.size > 32) raw.size - 32 else 0
        val len = raw.size - srcStart
        System.arraycopy(raw, srcStart, out, 32 - len, len)
        return out
    }

    /** share target (32 bytes big-endian) = diff1 / difficulty */
    fun difficultyToTarget(diff: Double): ByteArray {
        val d = if (diff.isNaN() || diff.isInfinite() || diff <= 0.0) 1.0 else diff
        val t = BigDecimal(DIFF1).divide(BigDecimal(d), 0, RoundingMode.FLOOR).toBigInteger()
        return toBytes32(if (t > MAX256) MAX256 else t)
    }

    fun nbitsToTarget(nbitsHex: String): BigInteger {
        val bits = nbitsHex.toLong(16)
        val exp = (bits ushr 24).toInt()
        val mant = bits and 0x7fffffL
        return if (exp <= 3) BigInteger.valueOf(mant ushr (8 * (3 - exp)))
        else BigInteger.valueOf(mant).shiftLeft(8 * (exp - 3))
    }

    fun networkDifficulty(nbitsHex: String): Double {
        val t = nbitsToTarget(nbitsHex)
        return if (t.signum() == 0) 0.0 else DIFF1.toDouble() / t.toDouble()
    }

    fun fmtRate(hps: Double): String = when {
        hps >= 1e9 -> String.format(java.util.Locale.US, "%.2f GH/s", hps / 1e9)
        hps >= 1e6 -> String.format(java.util.Locale.US, "%.2f MH/s", hps / 1e6)
        hps >= 1e3 -> String.format(java.util.Locale.US, "%.2f kH/s", hps / 1e3)
        else -> String.format(java.util.Locale.US, "%.0f H/s", hps)
    }
}
