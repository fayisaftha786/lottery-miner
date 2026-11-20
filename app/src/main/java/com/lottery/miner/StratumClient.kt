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
        fun onShareResult(accepted: Boolean, reason: String?)
        fun onStatus(msg: String)
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var writer: OutputStreamWriter? = null
    private val nextId = AtomicInteger(100)
    private val pendingSubmits: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    fun connectAndRun() {
        require(port in 1..65535) { "Pool port must be 1..65535" }
        val s = connectSocket()
        socket = s
        try {
            s.soTimeout = 180000
            s.tcpNoDelay = true
            s.keepAlive = true
            writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            send(1, "mining.subscribe", JSONArray().put("LotteryMiner/1.0"))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.length > 65536) throw ProtocolException("Pool Stratum line exceeds 64 KiB")
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
            pendingSubmits.clear()
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
            val candidate = Socket()
            try {
                listener.onStatus("Connecting to $host (${address.hostAddress}):$port")
                candidate.connect(InetSocketAddress(address, port), 10000)
                return candidate
            } catch (e: Exception) {
                lastError = e
                try { candidate.close() } catch (_: Exception) { }
            }
        }
        throw lastError ?: java.net.ConnectException("Pool host resolved to no addresses")
    }

    fun close() { try { socket?.close() } catch (_: Exception) { } }

    fun submit(jobId: String, en2Hex: String, ntimeHex: String, nonceHex: String) {
        val id = nextId.incrementAndGet()
        pendingSubmits.add(id)
        try {
            send(id, "mining.submit", JSONArray().put(user).put(jobId).put(en2Hex).put(ntimeHex).put(nonceHex))
        } catch (e: Exception) {
            pendingSubmits.remove(id)
            listener.onStatus("Share send failed: ${e.message}")
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
                "mining.set_difficulty" -> {
                    val diff = params.optDouble(0, Double.NaN)
                    if (diff.isFinite() && diff > 0.0) listener.onDifficulty(diff)
                    else throw ProtocolException("Pool sent an invalid difficulty")
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
                val result = message.optJSONArray("result")
                    ?: throw ProtocolException("Pool subscribe rejected: ${message.opt("error")}")
                if (result.length() < 3) throw ProtocolException("Incomplete pool subscribe response")
                val extranonce1 = Util.hexToBytes(result.getString(1))
                val extranonce2Size = result.getInt(2)
                if (extranonce2Size !in 1..16) throw ProtocolException("Invalid extranonce2 size")
                listener.onSubscribed(extranonce1, extranonce2Size)
                send(3, "mining.suggest_difficulty", JSONArray().put(1))
                send(2, "mining.authorize", JSONArray().put(user).put("x"))
            }
            2 -> {
                if (!message.optBoolean("result", false)) {
                    throw ProtocolException("Pool authorization rejected; check BTC address/worker. ${message.opt("error")}")
                }
                listener.onAuthorized()
            }
            else -> if (id >= 100 && pendingSubmits.remove(id)) {
                val error = if (message.isNull("error") || message.opt("error") == null) null else message.get("error").toString()
                listener.onShareResult(message.optBoolean("result", false), error)
            }
        }
    }

    private fun parseJob(params: JSONArray): Job {
        if (params.length() < 9) throw ProtocolException("Incomplete mining.notify job")
        val branchesJson = params.getJSONArray(4)
        val branches = ArrayList<ByteArray>(branchesJson.length())
        for (i in 0 until branchesJson.length()) branches.add(Util.hexToBytes(branchesJson.getString(i)))
        return try {
            Job(
                id = params.getString(0), prevHashHex = params.getString(1),
                coinb1 = Util.hexToBytes(params.getString(2)), coinb2 = Util.hexToBytes(params.getString(3)),
                merkle = branches, versionHex = params.getString(5), nbitsHex = params.getString(6),
                ntimeHex = params.getString(7), clean = params.optBoolean(8, false)
            )
        } catch (e: Exception) {
            throw ProtocolException("Invalid mining.notify job: ${e.message}")
        }
    }

    private class ProtocolException(message: String) : java.io.IOException(message)
}
