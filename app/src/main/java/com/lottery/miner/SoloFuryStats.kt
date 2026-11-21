package com.lottery.miner

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Read-only SoloFury BTC worker statistics. This does not provide extra hashpower. */
data class ExternalWorker(val name: String, val hashrate: Double, val lastSeen: String)
data class ExternalMiningSnapshot(
    val workers: List<ExternalWorker>,
    val totalHashrate: Double,
    val fetchedAtMillis: Long
)

object SoloFuryStats {
    private const val BASE_URL = "https://solofury.com/api-btc/client/"

    fun fetch(address: String): ExternalMiningSnapshot {
        require(Util.isValidBitcoinAddress(address)) { "Invalid Bitcoin mainnet address" }
        val connection = (URL(BASE_URL + address).openConnection() as HttpURLConnection).apply {
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
            if (code !in 200..299) throw IllegalStateException("SoloFury API HTTP $code")
            val root = JSONObject(body)
            val workersJson = root.optJSONArray("workers")
            val workers = buildList {
                if (workersJson != null) {
                    for (index in 0 until workersJson.length()) {
                        val item = workersJson.optJSONObject(index) ?: continue
                        val rate = item.optDouble("hashRate", 0.0)
                        if (rate.isFinite() && rate >= 0.0) {
                            add(ExternalWorker(
                                name = item.optString("name", "worker-" + (index + 1)),
                                hashrate = rate,
                                lastSeen = item.optString("lastSeen", "")
                            ))
                        }
                    }
                }
            }
            return ExternalMiningSnapshot(
                workers = workers,
                totalHashrate = workers.sumOf { it.hashrate },
                fetchedAtMillis = System.currentTimeMillis()
            )
        } finally {
            connection.disconnect()
        }
    }
}
