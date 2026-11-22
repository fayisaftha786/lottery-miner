package com.lottery.miner

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Minimal Stratum v1 client (plain TCP). */
class StratumClient(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val listener: Listener
) {
    interface Listener {
        fun onSubscribed(extranonce1: ByteArray, extranonce2Size: Int)
        fun onAuthorized()
        fun onDifficulty(diff: Double)
        fun onJob(job: Job)
        fun onShareSubmitted()
        fun onShareResult(accepted: Boolean, reason: String?)
        fun onShareUnknown(count: Int)
        fun onShareDropped(count: Int)
        fun onStatus(msg: String)
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var closed = false
    @Volatile private var writer: OutputStreamWriter? = null
    private val nextId = AtomicInteger(100)
    private val pendingSubmits: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    companion object { private const val MAX_PENDING_SUBMITS = 256; private const val MAX_LINE_CHARS = 65536 }

    fun connectAndRun() {
        require(port in 1..65535) { "Pool port must be 1..65535" }
        val s = connectSocket()
        socket = s
        try {
            if (closed) return
            s.soTimeout = 180000
            s.tcpNoDelay = true
            s.keepAlive = true
            writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            send(1, "mining.subscribe", JSONArray().put("LotteryMiner/1.0"))
            while (true) {
                val line = readBoundedLine(reader, MAX_LINE_CHARS) ?: break
                if (line.isBlank()) continue
                try {
                    handle(JSONObject(line))
                } catch (e: Exception) {
                    listener.onStatus("Stratum message error: ${e.message}")
                    if (e is ProtocolException) throw e
                }
            }
        } finally {
            try { s.close() } catch (_: Exception) { }
            socket = null
            writer = null
            val unresolved = pendingSubmits.size
            pendingSubmits.clear()
            if (unresolved > 0) listener.onShareUnknown(unresolved)
        }
    }

    private fun connectSocket(): Socket {
        val addresses = try {
            InetAddress.getAllByName(host)
                .distinctBy { it.hostAddress }
                .sortedWith(compareByDescending<InetAddress> { it is Inet4Address })
        } catch (e: Exception) {
            throw java.net.UnknownHostException("Could not resolve pool host '$host': ${e.message}")
        }
        var lastError: Exception? = null
        for (address in addresses) {
            if (closed) throw java.io.IOException("Stratum client closed")
            val candidate = Socket()
            try {
                listener.onStatus("Connecting to $host (${address.hostAddress}):$port")
                candidate.connect(InetSocketAddress(address, port), 10000)
                if (closed) {
                    candidate.close()
                    throw java.io.IOException("Stratum client closed")
                }
                return candidate
            } catch (e: Exception) {
                lastError = e
                try { candidate.close() } catch (_: Exception) { }
            }
        }
        throw lastError ?: java.net.ConnectException("Pool host resolved to no addresses")
    }

    fun close() {
        closed = true
        try { socket?.close() } catch (_: Exception) { }
    }

    @Synchronized fun submit(jobId: String, en2Hex: String, ntimeHex: String, nonceHex: String) {
        if (pendingSubmits.size >= MAX_PENDING_SUBMITS) {
            listener.onShareDropped(1)
            listener.onStatus("Share not submitted: pending-response queue is full")
            return
        }
        val id = nextId.incrementAndGet()
        pendingSubmits.add(id)
        try {
            send(id, "mining.submit", JSONArray().put(user).put(jobId).put(en2Hex).put(ntimeHex).put(nonceHex))
            listener.onShareSubmitted()
        } catch (e: Exception) {
            pendingSubmits.remove(id)
            listener.onShareUnknown(1)
            listener.onStatus("Share send failed; outcome is unknown: ${e.message}")
        }
    }

    @Synchronized
    private fun send(id: Int, method: String, params: JSONArray) {
        val message = JSONObject().put("id", id).put("method", method).put("params", params)
        val out = writer ?: throw java.io.IOException("Pool socket is not ready")
        out.write(message.toString())
        out.write("\n")
        out.flush()
    }

    private fun handle(message: JSONObject) {
        val method = message.optString("method", "")
        if (method.isNotEmpty()) {
            val params = message.optJSONArray("params") ?: JSONArray()
            when (method) {
                "mining.notify" -> listener.onJob(parseJob(params))
                "mining.set_extranonce" -> {
                    try {
                        if (params.length() != 2 || params.isNull(0) || params.isNull(1))
                            throw ProtocolException("Invalid mining.set_extranonce parameters")
                        val hexValue = params.get(0)
                        val sizeValue = params.get(1)
                        if (hexValue !is String || sizeValue !is Number)
                            throw ProtocolException("Invalid mining.set_extranonce value types")
                        val hex = hexValue
                        val sizeNumber = sizeValue.toDouble()
                        val size = sizeValue.toInt()
                        if (sizeNumber != size.toDouble() || size !in 1..16)
                            throw ProtocolException("Invalid extranonce2 size")
                        if (hex.length > 64 || hex.length % 2 != 0 || !hex.matches(Regex("[0-9a-fA-F]*")))
                            throw ProtocolException("Invalid extranonce1 encoding")
                        listener.onSubscribed(Util.hexToBytes(hex), size)
                    } catch (e: ProtocolException) {
                        throw e
                    } catch (e: Exception) {
                        throw ProtocolException("Invalid mining.set_extranonce notification: ${e.message}")
                    }
                }
                "mining.set_difficulty" -> {
                    val rawDiff = if (params.length() > 0) params.opt(0) else null
                    val diff = (rawDiff as? Number)?.toDouble() ?: Double.NaN
                    if (!diff.isFinite() || diff <= 0.0)
                        throw ProtocolException("Pool sent an invalid difficulty")
                    try { Util.difficultyToTarget(diff) }
                    catch (e: Exception) { throw ProtocolException("Pool difficulty cannot be represented: ${e.message}") }
                    listener.onDifficulty(diff)
                }
                "mining.ping" -> {
                    val response = JSONObject().put("id", message.opt("id")).put("result", JSONObject.NULL).put("error", JSONObject.NULL)
                    val out = writer ?: throw java.io.IOException("Pool socket is not ready")
                    out.write(response.toString()); out.write("\n"); out.flush()
                }
                "client.show_message" -> listener.onStatus("Pool: ${params.optString(0)}")
                else -> { }
            }
            return
        }

        when (val id = message.optInt("id", -1)) {
            1 -> {
                if (!message.isNull("error")) throw ProtocolException("Pool subscribe rejected: ${message.opt("error")}")
                val result = message.optJSONArray("result")
                    ?: throw ProtocolException("Pool subscribe response is not an array")
                if (result.length() != 3) throw ProtocolException("Incomplete pool subscribe response")
                try {
                    result.getJSONArray(0)
                    val extranonceValue = result.get(1)
                    val sizeValue = result.get(2)
                    if (extranonceValue !is String || sizeValue !is Number)
                        throw ProtocolException("Invalid subscribe response value types")
                    val extranonceHex = extranonceValue
                    val sizeNumber = sizeValue.toDouble()
                    val extranonce2Size = sizeValue.toInt()
                    if (sizeNumber != extranonce2Size.toDouble() || extranonce2Size !in 1..16)
                        throw ProtocolException("Invalid extranonce2 size")
                    if (extranonceHex.length > 64 || extranonceHex.length % 2 != 0 || !extranonceHex.matches(Regex("[0-9a-fA-F]*")))
                        throw ProtocolException("Invalid subscribe extranonce1")
                    val extranonce1 = Util.hexToBytes(extranonceHex)
                    listener.onSubscribed(extranonce1, extranonce2Size)
                } catch (e: ProtocolException) {
                    throw e
                } catch (e: Exception) {
                    throw ProtocolException("Invalid pool subscribe response: ${e.message}")
                }
                // CKPool ignores mining.suggest_difficulty until the client is authorised.
                send(2, "mining.authorize", JSONArray().put(user).put("x"))
            }
            2 -> {
                val result = message.opt("result")
                val error = if (message.isNull("error")) null else message.opt("error").toString()
                if (result !is Boolean || !result || error != null) {
                    throw ProtocolException("Pool authorization rejected; check BTC address/worker. ${error ?: "invalid result"}")
                }
                listener.onAuthorized()
                // Request low-difficulty feedback only after authorization succeeds.
                // The pool's mining.set_difficulty notification is the source of truth.
                send(3, "mining.suggest_difficulty", JSONArray().put(1))
            }
            else -> if (id >= 100 && pendingSubmits.remove(id)) {
                val result = message.opt("result")
                val error = if (message.isNull("error")) null else message.opt("error").toString()
                if (result !is Boolean) {
                    listener.onShareUnknown(1)
                    listener.onStatus("Pool returned malformed share response; outcome unknown")
                } else {
                    listener.onShareResult(result && error == null, error)
                }
            }
        }
    }

    private fun parseJob(params: JSONArray): Job {
        try {
            if (params.length() != 9) throw ProtocolException("mining.notify must contain exactly 9 fields")
            val jobId = params.getString(0)
            val prevHash = params.getString(1)
            val coinb1 = Util.hexToBytes(params.getString(2))
            val coinb2 = Util.hexToBytes(params.getString(3))
            val branchesJson = params.getJSONArray(4)
            if (branchesJson.length() > 32) throw ProtocolException("Too many merkle branches")
            val branches = ArrayList<ByteArray>(branchesJson.length())
            for (i in 0 until branchesJson.length()) {
                val branch = Util.hexToBytes(branchesJson.getString(i))
                if (branch.size != 32) throw ProtocolException("Merkle branch must be 32 bytes")
                branches.add(branch)
            }
            val version = params.getString(5)
            val nbits = params.getString(6)
            try { Util.nbitsToTarget(nbits) }
            catch (e: Exception) { throw ProtocolException("Invalid network target: ${e.message}") }
            val ntime = params.getString(7)
            val cleanValue = params.get(8)
            if (cleanValue !is Boolean) throw ProtocolException("clean_jobs must be boolean")
            return Job(
                id = jobId, prevHashHex = prevHash, coinb1 = coinb1, coinb2 = coinb2,
                merkle = branches, versionHex = version, nbitsHex = nbits,
                ntimeHex = ntime, clean = cleanValue
            )
        } catch (e: ProtocolException) {
            throw e
        } catch (e: Exception) {
            throw ProtocolException("Invalid mining.notify job: ${e.message}")
        }
    }

    private fun readBoundedLine(reader: BufferedReader, maxChars: Int): String? {
        val line = StringBuilder(minOf(1024, maxChars))
        while (true) {
            val ch = reader.read()
            if (ch == -1) return if (line.isEmpty()) null else line.toString()
            if (ch == '\n'.code) return line.toString().removeSuffix("\r")
            if (line.length >= maxChars) throw ProtocolException("Pool Stratum line exceeds 64 KiB")
            line.append(ch.toChar())
        }
    }

    private class ProtocolException(message: String) : java.io.IOException(message)
}
