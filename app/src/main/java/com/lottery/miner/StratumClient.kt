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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Classifies a Stratum mining.submit response.
 *
 * Pools commonly reject a share with result=null and a non-null JSON-RPC
 * error array. That is a definite rejection, not a missing/unknown response.
 */
internal data class ShareResponseClassification(
    val accepted: Boolean?,
    val reason: String?
)

internal fun classifyShareResponse(message: JSONObject): ShareResponseClassification {
    val result = message.opt("result")
    val error = message.opt("error")
        .takeUnless { it == null || it == JSONObject.NULL }
        ?.toString()
    return when {
        result == true && error == null ->
            ShareResponseClassification(accepted = true, reason = null)
        result == false ->
            ShareResponseClassification(accepted = false, reason = error ?: "Pool rejected share (result=false)")
        (result == null || result == JSONObject.NULL) && error != null ->
            ShareResponseClassification(accepted = false, reason = error)
        result == true && error != null ->
            ShareResponseClassification(accepted = false, reason = error)
        else ->
            ShareResponseClassification(accepted = null, reason = "Malformed pool share response")
    }
}

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
        // Candidate-only submits are not ordinary pool feedback shares.
        fun onCandidateSubmitted(candidateId: String) {}
        fun onCandidateResult(candidateId: String, accepted: Boolean, reason: String?) {}
        fun onCandidateUnknown(candidateId: String) {}
        fun onCandidateDropped(candidateId: String) {}
        /** Current BIP310 version-rolling mask; zero means fixed-version mining. */
        fun onVersionMask(mask: Long, enabled: Boolean) {}
        fun onStatus(msg: String)
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var closed = false
    @Volatile private var writer: OutputStreamWriter? = null
    private val nextId = AtomicInteger(100)
    private data class VersionRollingState(val enabled: Boolean, val mask: Long)
    @Volatile private var versionRollingState = VersionRollingState(enabled = false, mask = 0L)
    private val pendingSubmits: MutableMap<Int, PendingSubmit> = ConcurrentHashMap()

    private data class PendingSubmit(val isShare: Boolean, val candidateId: String?)

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
            // BIP310 configure is deliberately the first Stratum request. A pool
            // that declines or ignores the optional extension continues in the
            // original fixed-version mode after the ordinary subscribe response.
            val configureParameters = JSONArray()
                .put(JSONArray().put("version-rolling"))
                .put(
                    JSONObject()
                        .put("version-rolling.mask", String.format(Locale.US, "%08x", VersionRolling.REQUESTED_MASK))
                        .put("version-rolling.min-bit-count", 1)
                )
            send(10, "mining.configure", configureParameters)
            send(1, "mining.subscribe", JSONArray().put("LotteryMiner/1.0"))
            while (true) {
                val line = try {
                    readBoundedLine(reader, MAX_LINE_CHARS)
                } catch (e: java.net.SocketException) {
                    // Closing the socket is the normal way to stop this blocking read.
                    // Suppress only the expected close exception; unexpected socket errors
                    // still reach the reconnect loop.
                    if (closed) break else throw e
                } ?: break
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
            val unresolved = pendingSubmits.values.toList()
            pendingSubmits.clear()
            val shares = unresolved.count { it.isShare }
            if (shares > 0) listener.onShareUnknown(shares)
            unresolved.mapNotNull { it.candidateId }.forEach(listener::onCandidateUnknown)
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

    @Synchronized fun submit(
        jobId: String,
        en2Hex: String,
        ntimeHex: String,
        nonceHex: String,
        isShare: Boolean = true,
        candidateId: String? = null,
        versionBits: Long = 0L,
        versionMask: Long = 0L
    ) {
        // One immutable snapshot prevents an enabled flag from being observed
        // with a mask from a different negotiation/update.
        val rollingState = versionRollingState
        val rollingEnabled = rollingState.enabled
        val allowedMask = rollingState.mask
        val workMatchesNegotiation = if (rollingEnabled) {
            versionMask == allowedMask &&
                versionBits in 0L..0xffff_ffffL &&
                (versionBits and allowedMask.inv()) == 0L
        } else {
            versionMask == 0L && versionBits == 0L
        }
        if (!workMatchesNegotiation) {
            if (isShare) listener.onShareDropped(1)
            candidateId?.let(listener::onCandidateDropped)
            listener.onStatus(
                "Stratum submit not sent: scanned header uses a stale or unsupported BIP310 version mask"
            )
            return
        }
        if (pendingSubmits.size >= MAX_PENDING_SUBMITS) {
            if (isShare) listener.onShareDropped(1)
            candidateId?.let(listener::onCandidateDropped)
            listener.onStatus("Stratum submit not sent: pending-response queue is full")
            return
        }
        val id = nextId.incrementAndGet()
        pendingSubmits[id] = PendingSubmit(isShare, candidateId)
        try {
            val parameters = JSONArray()
                .put(user).put(jobId).put(en2Hex).put(ntimeHex).put(nonceHex)
            if (rollingEnabled) {
                parameters.put(String.format(Locale.US, "%08x", versionBits))
            }
            send(id, "mining.submit", parameters)
            if (isShare) listener.onShareSubmitted()
            candidateId?.let(listener::onCandidateSubmitted)
        } catch (e: Exception) {
            pendingSubmits.remove(id)
            if (isShare) listener.onShareUnknown(1)
            candidateId?.let(listener::onCandidateUnknown)
            listener.onStatus("Stratum submit send failed; outcome is unknown: ${e.message}")
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
                "mining.set_version_mask" -> {
                    val state = versionRollingState
                    if (!state.enabled) {
                        listener.onStatus("Ignored mining.set_version_mask before BIP310 negotiation")
                    } else {
                        if (params.length() != 1 || params.isNull(0) || params.get(0) !is String)
                            throw ProtocolException("Invalid mining.set_version_mask parameters")
                        val mask = VersionRolling.parseMask(params.getString(0))
                            ?: throw ProtocolException("Pool sent an invalid/out-of-range version mask")
                        // Invalidate the engine's current header before publishing
                        // the new submit mask, so no old header can be paired with it.
                        listener.onVersionMask(mask, enabled = true)
                        versionRollingState = state.copy(mask = mask)
                        listener.onStatus("BIP310 version mask updated: ${String.format(Locale.US, "%08x", mask)}")
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
            10 -> {
                val errorValue = message.opt("error")
                val error = errorValue.takeUnless { it == null || it == JSONObject.NULL }
                val result = message.optJSONObject("result")
                val enabled = error == null && result?.opt("version-rolling") == true
                val maskValue = result?.opt("version-rolling.mask") as? String
                val parsedMask = if (enabled) VersionRolling.parseMask(maskValue) else null
                if (enabled && parsedMask == null) {
                    // The server has activated the extension and will require a
                    // sixth submit parameter. Continuing in fixed-version mode
                    // would silently generate invalid submits, so reconnect safely.
                    throw ProtocolException("Pool activated BIP310 version rolling without a valid supported mask")
                }
                val state = VersionRollingState(
                    enabled = enabled,
                    mask = parsedMask ?: 0L
                )
                // Invalidate fixed-version/in-flight work before publishing the
                // negotiated submit format to worker threads.
                listener.onVersionMask(state.mask, state.enabled)
                versionRollingState = state
                listener.onStatus(
                    if (state.enabled) {
                        "BIP310 version rolling enabled; mask=${String.format(Locale.US, "%08x", state.mask)}"
                    } else {
                        "BIP310 version rolling unavailable; fixed-version mining remains enabled"
                    }
                )
            }
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
            else -> if (id >= 100) handleShareResponse(id, message)
        }
    }

    // Only share-response processing shares submit()'s monitor. Do not synchronize
    // the full message handler: job notifications acquire the engine work lock,
    // while worker submission can hold that lock before entering submit().
    @Synchronized
    private fun handleShareResponse(id: Int, message: JSONObject) {
        val pending = pendingSubmits.remove(id)
        if (pending == null) {
            listener.onStatus("Ignored unmatched or duplicate pool response id=$id")
            return
        }
        val classification = classifyShareResponse(message)
        when (classification.accepted) {
            true -> {
                if (pending.isShare) listener.onShareResult(true, null)
                pending.candidateId?.let { listener.onCandidateResult(it, true, null) }
            }
            false -> {
                if (pending.isShare) listener.onShareResult(false, classification.reason)
                pending.candidateId?.let { listener.onCandidateResult(it, false, classification.reason) }
            }
            null -> {
                if (pending.isShare) listener.onShareUnknown(1)
                pending.candidateId?.let(listener::onCandidateUnknown)
                listener.onStatus("Pool returned malformed Stratum submit response; outcome unknown")
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
