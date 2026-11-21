package com.lottery.miner

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Read-only OCEAN public API monitor. It reports miners already hashing for this wallet;
 * it does not provision, rent, or create external hashrate.
 */
data class ExternalWorker(val name: String, val hashrate: Double, val hashrate5m: Double)
data class ExternalMiningSnapshot(
    val workers: List<ExternalWorker>,
    val totalHashrate: Double,
    val fetchedAtMillis: Long
)

object OceanStats {
    private const val BASE_URL = "https://api.ocean.xyz/v1/user_hashrate_full/"

    fun fetch(address: String): ExternalMiningSnapshot {
        require(Util.isValidBitcoinAddress(address)) { "Invalid Bitcoin mainnet address" }
        val safeAddress = java.net.URLEncoder.encode(address, Charsets.UTF_8.name())
        val connection = (URL(BASE_URL + safeAddress).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "LotteryMiner-Android")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("OCEAN API HTTP $code")
            return parseResponse(body)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseResponse(body: String): ExternalMiningSnapshot {
        val root = JSONObject(body)
        val workersJson = root.optJSONArray("workers")
            ?: throw IllegalStateException("OCEAN response missing workers array")
        val workers = buildList {
            for (index in 0 until workersJson.length()) {
                val item = workersJson.optJSONObject(index) ?: continue
                val rate60 = firstNumber(item, "hashrate_60s") ?: Double.NaN
                val rate300 = firstNumber(item, "hashrate_300s") ?: Double.NaN
                val name = firstString(item, "worker", "worker_name", "name", "username")
                    .ifBlank { "worker-${index + 1}" }
                if (rate60.isFinite() && rate60 >= 0.0 && rate300.isFinite() && rate300 >= 0.0) {
                    add(ExternalWorker(name, rate60, rate300))
                }
            }
        }
        // OCEAN publishes a wallet-wide aggregate separately from the worker rows.
        // Prefer that documented total; fall back to worker sum only if absent.
        val userTotal = root.optJSONObject("user_hashrate")
            ?.let { firstNumber(it, "hashrate_60s") }
        val total = if (userTotal != null && userTotal.isFinite() && userTotal >= 0.0) {
            userTotal
        } else {
            workers.sumOf { it.hashrate }
        }
        return ExternalMiningSnapshot(workers, total, System.currentTimeMillis())
    }

    private fun firstNumber(item: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            if (item.has(key) && !item.isNull(key)) {
                val value = item.optDouble(key, Double.NaN)
                if (value.isFinite()) return value
            }
        }
        return null
    }

    private fun firstString(item: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = item.optString(key, "").trim()
            if (value.isNotEmpty() && value != "null") return value
        }
        return ""
    }
}
