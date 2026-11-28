package com.lottery.miner

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Bitcoin Mainnet P2P message framing and CompactSize primitives. */
object BitcoinP2pCodec {
    private val MAINNET_MAGIC = byteArrayOf(0xf9.toByte(), 0xbe.toByte(), 0xb4.toByte(), 0xd9.toByte())
    const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024
    data class Message(val command: String, val payload: ByteArray)

    fun encode(command: String, payload: ByteArray): ByteArray {
        require(command.matches(Regex("[a-zA-Z0-9]{1,12}"))) { "invalid P2P command" }
        require(payload.size <= MAX_PAYLOAD_BYTES) { "P2P payload too large" }
        val out = ByteArrayOutputStream(24 + payload.size)
        out.write(MAINNET_MAGIC)
        val cmd = command.toByteArray(StandardCharsets.US_ASCII)
        out.write(cmd)
        repeat(12 - cmd.size) { out.write(0) }
        writeUint32LE(out, payload.size.toLong())
        out.write(sha256d(payload), 0, 4)
        out.write(payload)
        return out.toByteArray()
    }

    /** Decodes exactly one complete message; trailing bytes are rejected. */
    fun decode(frame: ByteArray): Message {
        require(frame.size >= 24) { "truncated P2P header" }
        require(MAINNET_MAGIC.indices.all { frame[it] == MAINNET_MAGIC[it] }) { "wrong network magic" }
        val commandBytes = frame.copyOfRange(4, 16)
        val zeroAt = commandBytes.indexOf(0)
        val commandLength = if (zeroAt < 0) 12 else zeroAt
        if (zeroAt >= 0) require(commandBytes.drop(zeroAt).all { it == 0.toByte() }) { "malformed command padding" }
        val command = String(commandBytes, 0, commandLength, StandardCharsets.US_ASCII)
        require(command.matches(Regex("[a-zA-Z0-9]{1,12}"))) { "invalid P2P command" }
        val payloadLength = readUint32LE(frame, 16)
        require(payloadLength <= MAX_PAYLOAD_BYTES) { "P2P payload too large" }
        require(frame.size.toLong() == 24L + payloadLength) { "frame length mismatch or trailing bytes" }
        val payload = frame.copyOfRange(24, frame.size)
        val checksum = sha256d(payload).copyOfRange(0, 4)
        require((0..3).all { frame[20 + it] == checksum[it] }) { "P2P checksum mismatch" }
        return Message(command, payload)
    }

    fun encodeCompactSize(value: Long): ByteArray {
        require(value >= 0) { "CompactSize cannot encode a negative value" }
        val out = ByteArrayOutputStream(9)
        when {
            value < 253L -> out.write(value.toInt())
            value <= 0xffffL -> { out.write(253); writeUint16LE(out, value) }
            value <= 0xffffffffL -> { out.write(254); writeUint32LE(out, value) }
            else -> { out.write(255); repeat(8) { out.write((value ushr (8 * it)).toInt() and 0xff) } }
        }
        return out.toByteArray()
    }

    fun decodeCompactSize(bytes: ByteArray, offset: Int = 0): Pair<Long, Int> {
        require(offset in bytes.indices) { "missing CompactSize" }
        return when (val prefix = bytes[offset].toInt() and 0xff) {
            in 0..252 -> prefix.toLong() to 1
            253 -> {
                require(offset + 3 <= bytes.size) { "truncated CompactSize uint16" }
                val v = readUint16LE(bytes, offset + 1)
                require(v >= 253) { "non-canonical CompactSize uint16" }
                v to 3
            }
            254 -> {
                require(offset + 5 <= bytes.size) { "truncated CompactSize uint32" }
                val v = readUint32LE(bytes, offset + 1)
                require(v > 0xffffL) { "non-canonical CompactSize uint32" }
                v to 5
            }
            else -> {
                require(offset + 9 <= bytes.size) { "truncated CompactSize uint64" }
                var v = 0L
                for (i in 0..7) v = v or ((bytes[offset + 1 + i].toLong() and 0xffL) shl (8 * i))
                require(v >= 0L && v > 0xffffffffL) { "unsupported or non-canonical CompactSize uint64" }
                v to 9
            }
        }
    }

    private fun sha256d(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(digest.digest(data))
    }
    private fun writeUint16LE(out: ByteArrayOutputStream, v: Long) {
        out.write(v.toInt() and 0xff); out.write((v ushr 8).toInt() and 0xff)
    }
    private fun writeUint32LE(out: ByteArrayOutputStream, v: Long) {
        repeat(4) { out.write((v ushr (8 * it)).toInt() and 0xff) }
    }
    private fun readUint16LE(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xffL) or ((b[o + 1].toLong() and 0xffL) shl 8)
    private fun readUint32LE(b: ByteArray, o: Int): Long {
        var v = 0L
        repeat(4) { v = v or ((b[o + it].toLong() and 0xffL) shl (8 * it)) }
        return v
    }
}
