package com.lottery.miner

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * Direct TCP connection to a Bitcoin Mainnet peer. All calls are blocking and
 * MUST be invoked from a worker thread, never the Android main thread.
 *
 * This implements only transport + the initial version/verack handshake.
 * It deliberately does not claim header consensus validation or mining.
 */
class BitcoinP2pPeer(
    private val socketFactory: () -> Socket = { Socket() },
    private val connectTimeoutMs: Int = 10_000,
    private val handshakeTimeoutMs: Int = 12_000
) : AutoCloseable {
    companion object {
        const val MAINNET_PORT = 8333
        const val PROTOCOL_VERSION = 70_016
        const val MIN_PROTOCOL_VERSION = 70_001
        private val DNS_SEEDS = listOf(
            "seed.bitcoin.sipa.be", "dnsseed.bluematt.me",
            "seed.bitcoin.jonasschnelli.ch", "seed.btc.petertodd.net",
            "seed.bitcoin.sprovoost.nl", "dnsseed.emzy.de"
        )

        /** Resolve public Bitcoin DNS seeds. Results are candidates, not trusted peers. */
        fun discoverCandidates(max: Int = 24): List<InetAddress> {
            require(max in 1..128)
            val found = LinkedHashMap<String, InetAddress>()
            for (seed in DNS_SEEDS) {
                try {
                    for (address in InetAddress.getAllByName(seed)) {
                        if (!address.isAnyLocalAddress && !address.isLoopbackAddress &&
                            !address.isLinkLocalAddress && !address.isMulticastAddress) {
                            found.putIfAbsent(address.hostAddress ?: continue, address)
                            if (found.size >= max) return found.values.toList()
                        }
                    }
                } catch (_: Exception) { /* try the next seed */ }
            }
            return found.values.toList()
        }
    }

    data class Handshake(val peerVersion: Long, val peerAddress: String, val connectedAt: Long)
    @Volatile private var socket: Socket? = null
    @Volatile private var input: InputStream? = null
    @Volatile private var output: java.io.OutputStream? = null
    private val outputLock = Any()

    @Synchronized
    fun connectAndHandshake(address: InetAddress, port: Int = MAINNET_PORT): Handshake {
        require(port in 1..65535)
        check(socket == null) { "peer already connected" }
        val s = socketFactory()
        try {
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(address, port), connectTimeoutMs)
            s.soTimeout = handshakeTimeoutMs
            val i = s.getInputStream()
            socket = s
            input = i
            output = s.getOutputStream()
            send("version", buildVersionPayload(address, port))
            var gotVersion = false
            var gotVerack = false
            var peerVersion = -1L
            while (!gotVersion || !gotVerack) {
                val msg = readMessage(i)
                when (msg.command) {
                    "version" -> {
                        require(!gotVersion) { "duplicate version message" }
                        require(msg.payload.size >= 80) { "truncated version payload" }
                        peerVersion = uint32le(msg.payload, 0)
                        require(peerVersion >= MIN_PROTOCOL_VERSION) { "peer protocol version too old" }
                        gotVersion = true
                        send("verack", byteArrayOf())
                    }
                    "verack" -> {
                        require(msg.payload.isEmpty()) { "verack payload must be empty" }
                        gotVerack = true
                    }
                    "ping" -> {
                        require(msg.payload.size == 8) { "invalid ping payload" }
                        send("pong", msg.payload)
                    }
                    "sendaddrv2", "wtxidrelay" ->
                        require(msg.payload.isEmpty()) { "unexpected capability payload" }
                    else -> { /* ignore other well-formed messages during handshake */ }
                }
            }
            s.soTimeout = 30_000
            return Handshake(peerVersion, address.hostAddress ?: address.toString(), System.currentTimeMillis() / 1000L)
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    /** Request headers from a chain locator; hashes are serialized in internal/wire order. */
    fun requestHeaders(locatorHashesInternal: List<ByteArray>, stopHashInternal: ByteArray = ByteArray(32)) {
        send("getheaders", BitcoinP2pSync.buildGetHeaders(locatorHashesInternal, stopHashInternal))
    }

    /** Announce willingness to receive headers announcements. */
    fun requestHeadersAnnouncements() = send("sendheaders", byteArrayOf())

    /** Read one bounded, checksum-validated P2P frame on the caller's worker thread. */
    fun receiveMessage(): BitcoinP2pCodec.Message {
        val stream = input ?: error("peer is not connected")
        return readMessage(stream)
    }

    fun send(command: String, payload: ByteArray) {
        val frame = BitcoinP2pCodec.encode(command, payload)
        synchronized(outputLock) {
            val out = output ?: error("peer is not connected")
            out.write(frame)
            out.flush()
        }
    }

    private fun readMessage(stream: InputStream): BitcoinP2pCodec.Message {
        val header = ByteArray(24)
        readFully(stream, header)
        val size = uint32le(header, 16)
        require(size <= BitcoinP2pCodec.MAX_PAYLOAD_BYTES.toLong()) { "peer payload exceeds limit" }
        val frame = ByteArray(24 + size.toInt())
        System.arraycopy(header, 0, frame, 0, 24)
        if (size > 0) readFully(stream, frame, 24, size.toInt())
        return BitcoinP2pCodec.decode(frame)
    }

    private fun readFully(stream: InputStream, target: ByteArray, offset: Int = 0, length: Int = target.size) {
        var pos = offset
        val end = offset + length
        while (pos < end) {
            val count = stream.read(target, pos, end - pos)
            if (count < 0) throw EOFException("peer closed connection mid-message")
            if (count > 0) pos += count
        }
    }

    private fun buildVersionPayload(peer: InetAddress, port: Int): ByteArray {
        val out = ByteArrayOutputStream(128)
        writeUint32LE(out, PROTOCOL_VERSION.toLong())
        writeUint64LE(out, 0L) // no NODE_NETWORK service claim
        writeUint64LE(out, System.currentTimeMillis() / 1000L)
        writeNetworkAddress(out, peer, port)
        writeNetworkAddress(out, InetAddress.getByAddress(ByteArray(16)), MAINNET_PORT)
        out.write(ByteArray(8).also { SecureRandom().nextBytes(it) })
        val agent = "/LotteryMiner:2.0-p2p/".toByteArray(Charsets.US_ASCII)
        out.write(BitcoinP2pCodec.encodeCompactSize(agent.size.toLong()))
        out.write(agent)
        writeUint32LE(out, 0L) // height unknown until header sync
        out.write(0) // relay=false
        return out.toByteArray()
    }

    private fun writeNetworkAddress(out: ByteArrayOutputStream, address: InetAddress, port: Int) {
        writeUint64LE(out, 0L)
        val raw = address.address
        val ip = if (raw.size == 16) raw else ByteArray(16).also {
            it[10] = 0xff.toByte(); it[11] = 0xff.toByte()
            System.arraycopy(raw, 0, it, 12, 4)
        }
        out.write(ip)
        out.write((port ushr 8) and 0xff)
        out.write(port and 0xff)
    }

    private fun writeUint32LE(out: ByteArrayOutputStream, v: Long) {
        repeat(4) { out.write((v ushr (8 * it)).toInt() and 0xff) }
    }
    private fun writeUint64LE(out: ByteArrayOutputStream, v: Long) {
        repeat(8) { out.write((v ushr (8 * it)).toInt() and 0xff) }
    }
    private fun uint32le(b: ByteArray, o: Int): Long {
        var v = 0L
        repeat(4) { v = v or ((b[o + it].toLong() and 0xffL) shl (8 * it)) }
        return v
    }

    @Synchronized
    override fun close() {
        synchronized(outputLock) {
            try { socket?.close() } catch (_: Exception) { }
            socket = null
            input = null
            output = null
        }
    }
}
