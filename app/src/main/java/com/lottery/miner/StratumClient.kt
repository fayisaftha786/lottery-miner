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

/** Minimal Stratum v1 client (plain TCP, e.g. solo.ckpool.org:3333). */
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
                if (line.isBlank()) continue
                try {
                    handle(JSONObject(line))
                } catch (e: Exception) {
                    listener.onStatus("Stratum സന്ദേശ പിശക്: ${e.message}")
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
        } catch (_: Exception) {
            throw java.net.UnknownHostException("Host resolve ചെയ്യാനായില്ല: $host")
        }
        var lastError: Exception? = null
        for (address in addresses) {
            val s = Socket()
            try {
                listener.onStatus("കണക്ട് ചെയ്യുന്നു $host (${address.hostAddress}):$port …")
                s.connect(InetSocketAddress(address, port), 10000)
                return s
            } catch (e: Exception) {
                lastError = e
                try { s.close() } catch (_: Exception) { }
            }
        }
        throw lastError ?: java.net.ConnectException("Pool-ലേക്ക് കണക്റ്റ് ചെയ്യാൻ address ഇല്ല")
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) { }
    }

    fun submit(jobId: String, en2Hex: String, ntimeHex: String, nonceHex: String) {
        val id = nextId.incrementAndGet()
        pendingSubmits.add(id)
        try {
            send(id, "mining.submit", JSONArray().put(user).put(jobId).put(en2Hex).put(ntimeHex).put(nonceHex))
        } catch (e: Exception) {
            pendingSubmits.remove(id)
            listener.onStatus("Share അയയ്ക്കാനായില്ല: ${e.message}")
        }
    }

    @Synchronized
    private fun send(id: Int, method: String, params: JSONArray) {
        val o = JSONObject()
        o.put("id", id)
        o.put("method", method)
        o.put("params", params)
        val w = writer ?: throw java.io.IOException("Pool socket തയ്യാറല്ല")
        w.write(o.toString())
        w.write("\n")
        w.flush()
    }

    private fun handle(m: JSONObject) {
        val method = m.optString("method", "")
        if (method.isNotEmpty()) {
            val p = m.optJSONArray("params") ?: JSONArray()
            when (method) {
                "mining.notify" -> listener.onJob(parseJob(p))
                "mining.set_difficulty" -> {
                    val diff = p.optDouble(0, Double.NaN)
                    if (diff.isFinite() && diff > 0.0) listener.onDifficulty(diff)
                    else listener.onStatus("പൂളിൽ നിന്ന് അസാധുവായ difficulty ലഭിച്ചു")
                }
                "client.show_message" -> listener.onStatus("പൂൾ സന്ദേശം: " + p.optString(0))
                else -> { }
            }
            return
        }

        val id = m.optInt("id", -1)
        when (id) {
            1 -> {
                val res = m.optJSONArray("result")
                    ?: throw ProtocolException("Pool subscribe നിരസിച്ചു: ${m.opt("error")}")
                if (res.length() < 3) throw ProtocolException("Pool-ന്റെ subscribe മറുപടി അപൂർണ്ണമാണ്")
                val extranonce1 = Util.hexToBytes(res.getString(1))
                val extranonce2Size = res.getInt(2)
                if (extranonce2Size !in 1..16) throw ProtocolException("Pool extranonce2 വലുപ്പം അസാധുവാണ്")
                listener.onSubscribed(extranonce1, extranonce2Size)
                send(3, "mining.suggest_difficulty", JSONArray().put(1))
                send(2, "mining.authorize", JSONArray().put(user).put("x"))
            }
            2 -> {
                if (!m.optBoolean("result", false)) {
                    throw ProtocolException("Pool authorization നിരസിച്ചു; address/worker ശരിയാണോ പരിശോധിക്കുക. ${m.opt("error")}")
                }
                listener.onAuthorized()
            }
            else -> if (id >= 100 && pendingSubmits.remove(id)) {
                val ok = m.optBoolean("result", false)
                val err = if (m.isNull("error") || m.opt("error") == null) null else m.get("error").toString()
                listener.onShareResult(ok, err)
            }
        }
    }

    private fun parseJob(p: JSONArray): Job {
        if (p.length() < 9) throw ProtocolException("Pool mining.notify job അപൂർണ്ണമാണ്")
        val branches = ArrayList<ByteArray>()
        val br = p.getJSONArray(4)
        for (i in 0 until br.length()) branches.add(Util.hexToBytes(br.getString(i)))
        return Job(
            id = p.getString(0),
            prevHashHex = p.getString(1),
            coinb1 = Util.hexToBytes(p.getString(2)),
            coinb2 = Util.hexToBytes(p.getString(3)),
            merkle = branches,
            versionHex = p.getString(5),
            nbitsHex = p.getString(6),
            ntimeHex = p.getString(7),
            clean = p.optBoolean(8, false)
        )
    }

    private class ProtocolException(message: String) : java.io.IOException(message)
}
