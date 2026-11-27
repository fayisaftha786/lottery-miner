package com.lottery.miner

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stratum V1 transport for CKPool Solo.
 *
 * This class deliberately reports only protocol facts received from the server.
 * It does not claim mining or accepted shares: a separate work assembler and
 * native share scanner must call submitShare() with a fully verified solution.
 */
internal class CkPoolStratumClient(
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    private val connectTimeoutMs: Int = 12_000,
    private val readTimeoutMs: Int = 30_000
) : AutoCloseable {
    companion object {
        const val DEFAULT_HOST = "stratum.ckpool.org"
        const val DEFAULT_PORT = 3333
        private const val MAX_LINE_CHARS = 1_048_576
    }

    data class Job(
        val jobId: String,
        val prevHash: String,
        val coinbase1: String,
        val coinbase2: String,
        val merkleBranches: List<String>,
        val version: String,
        val nBits: String,
        val nTime: String,
        val cleanJobs: Boolean
    )

    data class Subscription(val extranonce1: String, val extranonce2Size: Int)
    data class ShareResult(val accepted: Boolean, val error: String?)

    interface Listener {
        fun onConnected()
        fun onAuthorized()
        fun onDifficulty(value: Double)
        fun onJob(job: Job)
        fun onMessage(message: String)
        fun onDisconnected(reason: String)
        fun onError(reason: String)
    }

    private val ids = AtomicInteger(1)
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var reader: BufferedReader? = null
    @Volatile private var closed = false
    @Volatile private var authorized = false
    @Volatile private var subscription: Subscription? = null
    @Volatile private var listener: Listener? = null
    private val pending = HashMap<Int, (JSONObject) -> Unit>()
    private val pendingLock = Any()

    fun connect(payoutAddress: String, password: String = "x", callback: Listener) {
        require(Util.isValidBitcoinAddress(payoutAddress) && !payoutAddress.contains('.')) {
            "A valid Bitcoin mainnet payout address is required"
        }
        require(host.matches(Regex("[a-zA-Z0-9.-]{1,253}")) && port in 1..65535) {
            "Invalid Stratum endpoint"
        }
        listener = callback
        closed = false
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            s.soTimeout = readTimeoutMs
            socket = s
            reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))
            callback.onConnected()

            sendRequest("mining.subscribe", JSONArray().put("PHOENIX/1.0"), callback = { response ->
                if (response.opt("error") != null && !response.isNull("error")) {
                    throw IllegalStateException("CKPool subscribe rejected: " + response.opt("error").toString())
                }
                val result = response.optJSONArray("result")
                    ?: throw IllegalStateException("Malformed mining.subscribe response")
                val extranonce1 = result.optString(1)
                val extranonce2Size = result.optInt(2, -1)
                require(extranonce1.matches(Regex("(?:[0-9a-fA-F]{2})*")) && extranonce2Size in 1..16) {
                    "Invalid CKPool extranonce subscription"
                }
                subscription = Subscription(extranonce1, extranonce2Size)
                sendRequest("mining.authorize", JSONArray().put(payoutAddress).put(password), callback = { auth ->
                    if (auth.optBoolean("result", false)) {
                        authorized = true
                        callback.onAuthorized()
                    } else {
                        throw IllegalStateException("CKPool authorization rejected")
                    }
                })
            })

            while (!closed && !s.isClosed) {
                val line = reader?.readLine() ?: throw IllegalStateException("CKPool closed the connection")
                if (line.length > MAX_LINE_CHARS) throw IllegalStateException("Oversized Stratum message")
                handleMessage(JSONObject(line), callback)
            }
        } catch (e: Exception) {
            if (!closed) callback.onError(e.message ?: e.javaClass.simpleName)
        } finally {
            authorized = false
            closeSocket()
            if (!closed) callback.onDisconnected("Stratum connection ended")
        }
    }

    fun currentSubscription(): Subscription? = subscription
    fun isAuthorized(): Boolean = authorized

    @Synchronized
    fun submitShare(
        workerName: String,
        jobId: String,
        extraNonce2: String,
        nTime: String,
        nonce: String,
        callback: (ShareResult) -> Unit
    ) {
        check(authorized) { "Cannot submit share before CKPool authorization" }
        require(workerName.isNotBlank() && jobId.isNotBlank())
        require(extraNonce2.matches(Regex("(?:[0-9a-fA-F]{2})+")))
        require(nTime.matches(Regex("[0-9a-fA-F]{8}")) && nonce.matches(Regex("[0-9a-fA-F]{8}")))
        sendRequest("mining.submit", JSONArray().put(workerName).put(jobId)
            .put(extraNonce2.lowercase()).put(nTime.lowercase()).put(nonce.lowercase())) { response ->
            val error = if (response.isNull("error")) null else response.opt("error").toString()
            callback(ShareResult(response.optBoolean("result", false), error))
        }
    }

    private fun handleMessage(message: JSONObject, callback: Listener) {
        if (message.has("id") && !message.isNull("id")) {
            val id = message.optInt("id", -1)
            val handler = synchronized(pendingLock) { pending.remove(id) }
            if (handler != null) handler(message)
            return
        }
        when (message.optString("method")) {
            "mining.set_difficulty" -> {
                val p = message.optJSONArray("params") ?: return
                val d = p.optDouble(0, Double.NaN)
                if (d.isFinite() && d > 0.0) callback.onDifficulty(d)
                else callback.onError("Invalid pool difficulty notification")
            }
            "mining.notify" -> {
                val p = message.optJSONArray("params") ?: return
                try {
                    val branches = p.optJSONArray(4) ?: JSONArray()
                    val job = Job(
                        jobId = p.getString(0),
                        prevHash = p.getString(1),
                        coinbase1 = p.getString(2),
                        coinbase2 = p.getString(3),
                        merkleBranches = (0 until branches.length()).map { branches.getString(it) },
                        version = p.getString(5),
                        nBits = p.getString(6),
                        nTime = p.getString(7),
                        cleanJobs = p.optBoolean(8, false)
                    )
                    require(job.jobId.isNotBlank())
                    callback.onJob(job)
                } catch (e: Exception) {
                    callback.onError("Malformed mining.notify: " + (e.message ?: "invalid fields"))
                }
            }
            "client.reconnect" -> callback.onError("CKPool requested reconnect")
            else -> callback.onMessage(message.toString())
        }
    }

    @Synchronized
    private fun sendRequest(method: String, params: JSONArray, callback: (JSONObject) -> Unit) {
        check(!closed && writer != null) { "Stratum socket is not connected" }
        val id = ids.getAndIncrement()
        synchronized(pendingLock) { pending[id] = callback }
        try {
            writer!!.apply {
                write(JSONObject().put("id", id).put("method", method).put("params", params).toString())
                write("\n")
                flush()
            }
        } catch (e: Exception) {
            synchronized(pendingLock) { pending.remove(id) }
            throw e
        }
    }

    override fun close() {
        closed = true
        authorized = false
        closeSocket()
        synchronized(pendingLock) { pending.clear() }
    }

    private fun closeSocket() {
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        reader = null
        writer = null
        socket = null
    }
}
