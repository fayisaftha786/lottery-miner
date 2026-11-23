package com.lottery.miner

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.Locale

object Util {
    val DIFF1: BigInteger = BigInteger.valueOf(0xFFFFL).shiftLeft(208)
    private val MAX256: BigInteger = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)
    private const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private const val BECH32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    fun hexToBytes(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length" }
        return ByteArray(s.length / 2) { i ->
            val hi = Character.digit(s[2 * i], 16)
            val lo = Character.digit(s[2 * i + 1], 16)
            require(hi >= 0 && lo >= 0) { "invalid hex" }
            ((hi shl 4) or lo).toByte()
        }
    }

    fun bytesToHex(b: ByteArray): String = buildString(b.size * 2) {
        for (x in b) append(String.format(Locale.US, "%02x", x.toInt() and 0xff))
    }

    fun sha256d(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(data))
    }

    /** Validate a mainnet legacy or SegWit address, optionally followed by a pool worker suffix. */
    fun isValidBitcoinAddress(input: String): Boolean {
        val parts = input.split('.', limit = 2)
        val address = parts[0]
        if (address.isEmpty() || (parts.size == 2 && !parts[1].matches(Regex("[A-Za-z0-9_-]{1,20}")))) return false
        return if (address.startsWith("1") || address.startsWith("3")) isValidBase58Address(address)
        else isValidSegwitAddress(address)
    }

    private fun isValidBase58Address(address: String): Boolean {
        try {
            var value = BigInteger.ZERO
            for (c in address) {
                val digit = BASE58.indexOf(c)
                if (digit < 0) return false
                value = value.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
            }
            val raw = value.toByteArray()
            val significant = if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
            val decoded = ByteArray(address.takeWhile { it == '1' }.length) + significant
            if (decoded.size != 25) return false
            val version = decoded[0].toInt() and 0xff
            if (version != 0 && version != 5) return false
            val payload = decoded.copyOfRange(0, 21)
            return decoded.copyOfRange(21, 25).contentEquals(sha256d(payload).copyOfRange(0, 4))
        } catch (_: Exception) {
            return false
        }
    }

    private fun isValidSegwitAddress(input: String): Boolean {
        if (input.length !in 14..90 || (input.any { it.isLowerCase() } && input.any { it.isUpperCase() })) return false
        val address = input.lowercase(Locale.ROOT)
        val separator = address.lastIndexOf('1')
        if (separator !in 1..(address.length - 7) || address.substring(0, separator) != "bc") return false
        val data = IntArray(address.length - separator - 1)
        for (i in data.indices) {
            val value = BECH32.indexOf(address[separator + 1 + i])
            if (value < 0) return false
            data[i] = value
        }
        val hrp = address.substring(0, separator)
        val expanded = hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 } + data.toList()
        val polymod = bech32Polymod(expanded)
        if (polymod != 1 && polymod != 0x2bc830a3) return false
        val payload = data.copyOf(data.size - 6)
        if (payload.isEmpty() || payload[0] !in 0..16) return false
        val version = payload[0]
        val program = convertBits(payload.copyOfRange(1, payload.size), 5, 8) ?: return false
        if (version == 0) return polymod == 1 && (program.size == 20 || program.size == 32)
        if (polymod != 0x2bc830a3 || program.size !in 2..40) return false
        return version != 1 || program.size == 32
    }

    private fun bech32Polymod(values: List<Int>): Int {
        val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0..4) if (((top ushr i) and 1) != 0) chk = chk xor generators[i]
        }
        return chk
    }

    private fun convertBits(data: IntArray, from: Int, to: Int): ByteArray? {
        var acc = 0
        var bits = 0
        val out = ArrayList<Byte>()
        val maxv = (1 shl to) - 1
        val maxAcc = (1 shl (from + to - 1)) - 1
        for (value in data) {
            if (value < 0 || (value ushr from) != 0) return null
            acc = ((acc shl from) or value) and maxAcc
            bits += from
            while (bits >= to) {
                bits -= to
                out.add(((acc ushr bits) and maxv).toByte())
            }
        }
        if (bits >= from || ((acc shl (to - bits)) and maxv) != 0) return null
        return ByteArray(out.size) { out[it] }
    }

    fun toBytes32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        val srcStart = if (raw.size > 32) raw.size - 32 else 0
        val len = raw.size - srcStart
        System.arraycopy(raw, srcStart, out, 32 - len, len)
        return out
    }

    fun difficultyToTarget(diff: Double): ByteArray {
        require(diff.isFinite() && diff > 0.0) { "difficulty must be positive and finite" }
        val target = BigDecimal(DIFF1).divide(BigDecimal.valueOf(diff), 0, RoundingMode.FLOOR).toBigInteger()
        require(target.signum() > 0) { "difficulty is too high to represent a non-zero target" }
        return toBytes32(if (target > MAX256) MAX256 else target)
    }

    fun nbitsToTarget(nbitsHex: String): BigInteger {
        require(nbitsHex.length == 8) { "nbits must contain 8 hex characters" }
        val bits = nbitsHex.toLong(16)
        val exponent = (bits ushr 24).toInt()
        val mantissa = bits and 0x7fffffL
        require((bits and 0x00800000L) == 0L && mantissa != 0L && exponent in 1..32) { "invalid compact target" }
        val target = if (exponent <= 3) BigInteger.valueOf(mantissa ushr (8 * (3 - exponent)))
        else BigInteger.valueOf(mantissa).shiftLeft(8 * (exponent - 3))
        require(target.signum() > 0) { "compact target rounds to zero" }
        require(target <= MAX256) { "target exceeds 256 bits" }
        require(target <= DIFF1) { "compact target exceeds Bitcoin mainnet proof-of-work limit" }
        return target
    }

    fun networkDifficulty(nbitsHex: String): Double = DIFF1.toDouble() / nbitsToTarget(nbitsHex).toDouble()

    /** Expected pool feedback-share interval; this is not a block-finding or payout estimate. */
    fun expectedShareIntervalSeconds(difficulty: Double, hashRate: Double): Double {
        require(difficulty.isFinite() && difficulty > 0.0) { "difficulty must be positive and finite" }
        require(hashRate.isFinite() && hashRate > 0.0) { "hashrate must be positive and finite" }
        return difficulty * 4_294_967_296.0 / hashRate
    }


    fun fmtRate(hps: Double): String = when {
        hps >= 1e9 -> String.format(Locale.US, "%.2f GH/s", hps / 1e9)
        hps >= 1e6 -> String.format(Locale.US, "%.2f MH/s", hps / 1e6)
        hps >= 1e3 -> String.format(Locale.US, "%.2f kH/s", hps / 1e3)
        else -> String.format(Locale.US, "%.0f H/s", hps)
    }
}


/**
 * Keeps the active share target stable for the current Stratum job.
 * Difficulty notifications are staged and only take effect when the next
 * mining.notify arrives, as required by Stratum V1 job semantics.
 */
class PoolDifficultyTargets(initialDifficulty: Double = 1.0) {
    @Volatile private var active: ByteArray = Util.difficultyToTarget(initialDifficulty)
    @Volatile private var activeDiff: Double = initialDifficulty
    @Volatile private var pending: ByteArray? = null
    @Volatile private var pendingDiff: Double? = null

    @Synchronized fun stage(difficulty: Double) {
        // Validate and compute before mutating pending state.
        val target = Util.difficultyToTarget(difficulty)
        pending = target
        pendingDiff = difficulty
    }

    @Synchronized fun activateForNextJob(): ByteArray {
        val nextTarget = pending
        if (nextTarget != null) {
            active = nextTarget
            activeDiff = pendingDiff ?: activeDiff
            pending = null
            pendingDiff = null
        }
        return active.copyOf()
    }

    @Synchronized fun activeTarget(): ByteArray = active.copyOf()

    @Synchronized fun activeDifficulty(): Double = activeDiff

    @Synchronized fun pendingDifficulty(): Double? = pendingDiff
}
