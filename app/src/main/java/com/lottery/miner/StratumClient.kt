package com.lottery.miner

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
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
        fun onDifficulty(diff: Double)
        fun onJob(job: Job)
        fun onShareResult(accepted: Boolean, reason: String?)
        fun onStatus(msg: String)
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var writer: OutputStreamWriter? = null
    private val nextId = AtomicInteger(100)
    private val pendingSubmits: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** Blocks until the connection ends. */
    fun connectAndRun() {
        val s = Socket()
        socket = s
        try {
            listener.onStatus("കണക്ട് ചെയ്യുന്നു $host:$port …")
            s.connect(InetSocketAddress(host, port), 15000)
            s.soTimeout = 180000
            s.tcpNoDelay = true
            writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            send(1, "mining.subscribe", JSONArray().put("LotteryMiner/1.0"))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                try {
                    handle(JSONObject(line))
                } catch (e: Exception) {
                    listener.onStatus("സന്ദേശ പിശക്: ${e.message}")
                }
            }
        } finally {
            try { s.close() } catch (e: Exception) { /* ignore */ }
            socket = null
            writer = null
        }
    }

    fun close() {
        try { socket?.close() } catch (e: Exception) { /* ignore */ }
    }

    fun submit(jobId: String, en2Hex: String, ntimeHex: String, nonceHex: String) {
        val id = nextId.incrementAndGet()
        pendingSubmits.add(id)
        try {
            send(id, "mining.submit", JSONArray().put(user).put(jobId).put(en2Hex).put(ntimeHex).put(nonceHex))
        } catch (e: Exception) {
            pendingSubmits.remove(id)
        }
    }

    @Synchronized
    private fun send(id: Int, method: String, params: JSONArray) {
        val o = JSONObject()
        o.put("id", id)
        o.put("method", method)
        o.put("params", params)
        val w = writer ?: return
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
                "mining.set_difficulty" -> listener.onDifficulty(p.optDouble(0, 1.0))
                "client.show_message" -> listener.onStatus("പൂൾ സന്ദേശം: " + p.optString(0))
                else -> { /* ignored */ }
            }
            return
        }
        val id = m.optInt("id", -1)
        when {
            id == 1 -> {
                val res = m.optJSONArray("result")
                if (res == null) {
                    listener.onStatus("subscribe പരാജയപ്പെട്ടു")
                    return
                }
                listener.onSubscribed(Util.hexToBytes(res.getString(1)), res.getInt(2))
                send(3, "mining.suggest_difficulty", JSONArray().put(1))
                send(2, "mining.authorize", JSONArray().put(user).put("x"))
            }
            id == 2 -> {
                val ok = m.optBoolean("result", false)
                listener.onStatus(if (ok) "authorize ശരി, ജോബിനായി കാത്തിരിക്കുന്നു" else "authorize നിരസിച്ചു (വിലാസം പരിശോധിക്കുക)")
            }
            id >= 100 -> {
                if (pendingSubmits.remove(id)) {
                    val ok = m.optBoolean("result", false)
                    val err = if (m.isNull("error")) null else m.get("error").toString()
                    listener.onShareResult(ok, err)
                }
            }
        }
    }

    private fun parseJob(p: JSONArray): Job {
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
}
