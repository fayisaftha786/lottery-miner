package com.lottery.miner

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Locale

/**
 * Creates a one-transaction (coinbase-only) candidate block.
 *
 * The caller MUST supply consensus-validated height, previous hash, version,
 * timestamp and nBits from a fully validated Mainnet chain view. This builder
 * does not obtain those values or validate the chain state itself.
 */
object BitcoinCoinbaseTemplate {
    private const val INITIAL_SUBSIDY_SATS = 5_000_000_000L
    private const val HALVING_INTERVAL = 210_000L
    private const val MAX_COINBASE_SCRIPT_BYTES = 100

    data class Candidate(
        val height: Long,
        val coinbaseTransaction: ByteArray,
        val headerWithoutNonce: ByteArray,
        val target: BigInteger
    ) {
        fun headerForNonce(nonce: Long): ByteArray {
            require(nonce in 0..0xffffffffL)
            return headerWithoutNonce.copyOf().also {
                for (i in 0..3) it[76 + i] = (nonce ushr (8 * i)).toByte()
            }
        }

        /** Serializes and checks the header's encoded PoW before returning a full block. */
        fun serializeBlock(nonce: Long): ByteArray {
            val header = headerForNonce(nonce)
            BitcoinHeader.parseAndCheck(header)
            val out = ByteArrayOutputStream(header.size + 9 + coinbaseTransaction.size)
            out.write(header)
            out.write(1) // CompactSize transaction count = 1
            out.write(coinbaseTransaction)
            return out.toByteArray()
        }
    }

    fun subsidyForHeight(height: Long): Long {
        require(height >= 0L)
        val halvings = height / HALVING_INTERVAL
        return if (halvings >= 64L) 0L else INITIAL_SUBSIDY_SATS ushr halvings.toInt()
    }

    fun payoutScriptForAddress(address: String): ByteArray {
        require(address.isNotBlank() && address.length <= 90) { "invalid payout address length" }
        return if (address.lowercase(Locale.US).startsWith("bc1")) segwitScript(address)
        else base58Script(address)
    }

    fun build(
        height: Long,
        previousHashInternal: ByteArray,
        version: Long,
        timestamp: Long,
        bits: Long,
        payoutAddress: String,
        extraNonce: ByteArray,
        tag: String = "LotteryMiner/P2P"
    ): Candidate {
        require(height > 0L) { "coinbase block height must be above genesis" }
        require(previousHashInternal.size == 32) { "previous block hash must be 32 bytes in wire order" }
        require(version in 2..0xffffffffL && timestamp in 0..0xffffffffL && bits in 0..0xffffffffL)
        require(extraNonce.isNotEmpty() && extraNonce.size <= 32) { "extraNonce must be 1..32 bytes" }
        require(tag.isNotEmpty() && tag.length <= 32 && tag.all { it.code in 0x20..0x7e }) {
            "coinbase tag must be 1..32 printable ASCII characters"
        }

        val payoutScript = payoutScriptForAddress(payoutAddress)
        val scriptSig = ByteArrayOutputStream()
        scriptSig.write(pushScriptNumber(height))
        scriptSig.write(pushData(extraNonce))
        scriptSig.write(pushData(tag.toByteArray(Charsets.US_ASCII)))
        val script = scriptSig.toByteArray()
        require(script.size in 2..MAX_COINBASE_SCRIPT_BYTES) { "coinbase scriptSig must be 2..100 bytes" }

        val coinbase = serializeCoinbase(subsidyForHeight(height), script, payoutScript)
        val merkleRoot = sha256d(coinbase) // one transaction: root is its txid in internal order
        val header = ByteArray(80)
        putUint32LE(header, 0, version)
        System.arraycopy(previousHashInternal, 0, header, 4, 32)
        System.arraycopy(merkleRoot, 0, header, 36, 32)
        putUint32LE(header, 68, timestamp)
        putUint32LE(header, 72, bits)
        return Candidate(height, coinbase, header, BitcoinHeader.compactToTarget(bits))
    }

    private fun serializeCoinbase(valueSats: Long, scriptSig: ByteArray, payoutScript: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeUint32LE(out, 2L) // transaction version
        out.write(1) // one input
        out.write(ByteArray(32)) // null prevout hash
        writeUint32LE(out, 0xffffffffL)
        out.write(BitcoinP2pCodec.encodeCompactSize(scriptSig.size.toLong()))
        out.write(scriptSig)
        writeUint32LE(out, 0xffffffffL)
        out.write(1) // one output
        writeUint64LE(out, valueSats)
        out.write(BitcoinP2pCodec.encodeCompactSize(payoutScript.size.toLong()))
        out.write(payoutScript)
        writeUint32LE(out, 0L)
        return out.toByteArray()
    }

    /** Minimal CScript number push used by BIP34 for contemporary Mainnet heights. */
    private fun pushScriptNumber(value: Long): ByteArray {
        if (value == 0L) return byteArrayOf(0)
        if (value in 1L..16L) return byteArrayOf((0x50L + value).toByte())
        var n = value
        val bytes = ByteArrayOutputStream()
        while (n > 0L) {
            bytes.write((n and 0xffL).toInt())
            n = n ushr 8
        }
        var encoded = bytes.toByteArray()
        if ((encoded.last().toInt() and 0x80) != 0) encoded += byteArrayOf(0)
        return pushData(encoded)
    }

    private fun pushData(data: ByteArray): ByteArray {
        require(data.size <= 75) { "only direct pushes up to 75 bytes are supported" }
        return byteArrayOf(data.size.toByte()) + data
    }

    private fun base58Script(address: String): ByteArray {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var number = BigInteger.ZERO
        for (char in address) {
            val digit = alphabet.indexOf(char)
            require(digit >= 0) { "invalid Base58 character" }
            number = number.multiply(BigInteger.valueOf(58L)).add(BigInteger.valueOf(digit.toLong()))
        }
        val leadingZeroes = address.takeWhile { it == '1' }.length
        var decoded = number.toByteArray()
        if (decoded.isNotEmpty() && decoded[0] == 0.toByte()) decoded = decoded.copyOfRange(1, decoded.size)
        val payload = ByteArray(leadingZeroes) + decoded
        require(payload.size == 25) { "invalid Base58Check address length" }
        val body = payload.copyOfRange(0, 21)
        require(checksum(body).contentEquals(payload.copyOfRange(21, 25))) { "Base58Check checksum mismatch" }
        val hash = body.copyOfRange(1, 21)
        return when (body[0].toInt() and 0xff) {
            0x00 -> byteArrayOf(0x76, 0xa9.toByte(), 0x14) + hash + byteArrayOf(0x88.toByte(), 0xac.toByte())
            0x05 -> byteArrayOf(0xa9.toByte(), 0x14) + hash + byteArrayOf(0x87.toByte())
            else -> throw IllegalArgumentException("address is not a Bitcoin Mainnet P2PKH/P2SH address")
        }
    }

    private fun segwitScript(address: String): ByteArray {
        require(address == address.lowercase(Locale.US) || address == address.uppercase(Locale.US)) {
            "mixed-case Bech32 address is invalid"
        }
        val normalized = address.lowercase(Locale.US)
        require(normalized.length in 14..90)
        val separator = normalized.lastIndexOf('1')
        require(separator in 1..83 && separator + 7 <= normalized.length)
        val hrp = normalized.substring(0, separator)
        require(hrp == "bc") { "only Bitcoin Mainnet bc1 addresses are accepted" }
        val charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        val data = normalized.substring(separator + 1).map { ch ->
            val v = charset.indexOf(ch)
            require(v >= 0) { "invalid Bech32 character" }
            v
        }
        val values = data.dropLast(6)
        require(values.isNotEmpty())
        val witnessVersion = values.first()
        require(witnessVersion in 0..16)
        val polymod = bech32Polymod(expandHrp(hrp) + data)
        val expected = if (witnessVersion == 0) 1 else 0x2bc830a3
        require(polymod == expected) { "Bech32/Bech32m checksum mismatch" }
        val program = convertBits(values.drop(1), 5, 8, false)
        require(program.size in 2..40)
        if (witnessVersion == 0) require(program.size == 20 || program.size == 32) {
            "version 0 witness program must be 20 or 32 bytes"
        }
        val opcode = if (witnessVersion == 0) 0 else 0x50 + witnessVersion
        return byteArrayOf(opcode.toByte(), program.size.toByte()) + program
    }

    private fun expandHrp(hrp: String): List<Int> =
        hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }

    private fun bech32Polymod(values: List<Int>): Int {
        val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (value in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor value
            for (i in 0..4) if (((top ushr i) and 1) != 0) chk = chk xor generators[i]
        }
        return chk
    }

    private fun convertBits(data: List<Int>, from: Int, to: Int, pad: Boolean): ByteArray {
        var accumulator = 0
        var bits = 0
        val maxValue = (1 shl to) - 1
        val maxAccumulator = (1 shl (from + to - 1)) - 1
        val out = ByteArrayOutputStream()
        for (value in data) {
            require(value >= 0 && (value ushr from) == 0) { "invalid witness data value" }
            accumulator = ((accumulator shl from) or value) and maxAccumulator
            bits += from
            while (bits >= to) {
                bits -= to
                out.write((accumulator ushr bits) and maxValue)
            }
        }
        if (pad) {
            if (bits > 0) out.write((accumulator shl (to - bits)) and maxValue)
        } else {
            require(bits < from && ((accumulator shl (to - bits)) and maxValue) == 0) {
                "non-zero or excessive Bech32 padding"
            }
        }
        return out.toByteArray()
    }

    private fun checksum(body: ByteArray): ByteArray = sha256d(body).copyOfRange(0, 4)

    private fun sha256d(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(digest.digest(data))
    }

    private fun putUint32LE(out: ByteArray, offset: Int, value: Long) {
        require(value in 0..0xffffffffL)
        repeat(4) { out[offset + it] = (value ushr (8 * it)).toByte() }
    }

    private fun writeUint32LE(out: ByteArrayOutputStream, value: Long) {
        require(value in 0..0xffffffffL)
        repeat(4) { out.write((value ushr (8 * it)).toInt() and 0xff) }
    }

    private fun writeUint64LE(out: ByteArrayOutputStream, value: Long) {
        require(value >= 0L)
        repeat(8) { out.write((value ushr (8 * it)).toInt() and 0xff) }
    }
}
