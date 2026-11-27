package com.lottery.miner

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

internal data class DirectWork(
    val jobId: String,
    val height: Int,
    val header: ByteArray,
    val target: ByteArray,
    val bits: String,
    val previousBlockHash: String,
    val transactionCount: Int,
    val longPollId: String?
)

internal data class DirectSubmitResult(
    val accepted: Boolean,
    val stage: String,
    val response: String,
    val blockHash: String
)

/** Authenticated HTTPS client for the Phoenix gateway; Bitcoin Core RPC credentials never reach Android. */
internal class DirectNodeClient(
    gatewayUrl: String,
    private val apiToken: String
) {
    private val baseUrl: String
    private val activeConnections = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    init {
        val normalized = gatewayUrl.trim().trimEnd('/')
        val uri = java.net.URI(normalized)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "Direct Node Gateway must use HTTPS"
        }
        require(uri.path.isNullOrEmpty() || uri.path == "/") {
            "Gateway URL must point to the gateway root path"
        }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Gateway URL must not contain credentials, query, or fragment"
        }
        require(apiToken.toByteArray(Charsets.UTF_8).size >= 32) {
            "Gateway API token must contain at least 32 bytes"
        }
        baseUrl = normalized
    }

    fun fetchWork(payoutAddress: String, extranonce: String, longPollId: String?): DirectWork {
        val requestBody = JSONObject()
            .put("payoutAddress", payoutAddress)
            .put("extranonce", extranonce)
        if (!longPollId.isNullOrBlank()) requestBody.put("longpollId", longPollId)
        val response = request("/v1/work", requestBody)
        require(response.getString("network") == "main") {
            "Gateway is not connected to Bitcoin mainnet"
        }
        val header = Util.hexToBytes(response.getString("header"))
        val target = Util.hexToBytes(response.getString("target"))
        require(header.size == 80) { "Gateway returned an invalid Bitcoin block header" }
        require(target.size == 32) { "Gateway returned an invalid network target" }

        val bits = response.getString("bits")
        require(bits.matches(Regex("[0-9a-fA-F]{8}"))) { "Gateway returned invalid compact bits" }
        val headerBits = ((header[75].toInt() and 0xff) shl 24) or
            ((header[74].toInt() and 0xff) shl 16) or
            ((header[73].toInt() and 0xff) shl 8) or
            (header[72].toInt() and 0xff)
        require(headerBits.toUInt().toString(16).padStart(8, '0').equals(bits, ignoreCase = true)) {
            "Gateway header bits do not match the template"
        }
        val calculatedTarget = Util.toBytes32(Util.nbitsToTarget(bits))
        require(calculatedTarget.contentEquals(target)) {
            "Gateway target does not match compact bits or Bitcoin mainnet proof-of-work limit"
        }

        val previousBlockHash = response.getString("previousBlockHash")
        require(previousBlockHash.matches(Regex("[0-9a-fA-F]{64}"))) {
            "Gateway returned an invalid previous block hash"
        }
        require(Util.hexToBytes(previousBlockHash).reversedArray().contentEquals(header.copyOfRange(4, 36))) {
            "Block header previous hash does not match the node template"
        }
        val jobId = response.getString("jobId")
        require(jobId.matches(Regex("[0-9a-fA-F]{64}"))) { "Gateway returned invalid job id" }
        val height = response.getInt("height")
        val transactionCount = response.getInt("transactionCount")
        require(height > 0 && transactionCount >= 1 && transactionCount <= 100_001) {
            "Gateway returned invalid block template metadata"
        }
        return DirectWork(
            jobId = jobId,
            height = height,
            header = header,
            target = target,
            bits = bits,
            previousBlockHash = previousBlockHash,
            transactionCount = transactionCount,
            longPollId = response.optString("longpollid").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    fun submitCandidate(jobId: String, nonce: Int): DirectSubmitResult {
        val nonceHex = String.format(java.util.Locale.US, "%08x", nonce.toLong() and 0xffff_ffffL)
        val response = request(
            "/v1/submit",
            JSONObject().put("jobId", jobId).put("nonce", nonceHex)
        )
        return DirectSubmitResult(
            accepted = response.optBoolean("accepted", false),
            stage = response.optString("stage", "unknown"),
            response = response.optString("response", "No node response"),
            blockHash = response.optString("blockHash", "")
        )
    }

    fun cancelPendingRequests() {
        activeConnections.toList().forEach { connection ->
            runCatching { connection.disconnect() }
        }
        activeConnections.clear()
    }

    private fun request(path: String, body: JSONObject?, method: String = "POST"): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = if (path == "/v1/work") 0 else 30_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $apiToken")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        activeConnections.add(connection)
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val payload = runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("Gateway HTTP " + code + " returned invalid JSON")
            }
            if (code !in 200..299) {
                throw IllegalStateException(payload.optString("error").ifBlank { "Gateway HTTP " + code })
            }
            return payload
        } finally {
            activeConnections.remove(connection)
            connection.disconnect()
        }
    }
}
