package com.lottery.miner

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class BitcoinProfitSnapshot(
    val difficulty: Double,
    val networkHashrateHps: Double,
    val blockSubsidyBtc: Double,
    val btcPriceInr: Double,
    val fetchedAtMillis: Long
) {
    val dailySubsidyBtc: Double
        get() = blockSubsidyBtc * AVERAGE_BLOCKS_PER_DAY

    companion object {
        const val AVERAGE_BLOCKS_PER_DAY = 144.0
    }
}

/**
 * Low-frequency, user-triggered read-only Bitcoin market/network data source.
 * No key, wallet address, pool credentials, or miner telemetry is sent.
 */
object BitcoinMarketData {
    private const val DIFFICULTY_URL = "https://blockchain.info/q/getdifficulty?cors=true"
    private const val BLOCK_REWARD_URL = "https://blockchain.info/q/bcperblock?cors=true"
    private const val TICKER_URL = "https://blockchain.info/ticker"
    private const val TIMEOUT_MS = 8_000
    private const val MAX_RESPONSE_BYTES = 64 * 1024

    fun fetchSnapshot(): BitcoinProfitSnapshot {
        // Timestamp the oldest request, not completion of the last endpoint.
        // The three public APIs are sequential and do not expose one atomic snapshot time.
        val snapshotStartedAt = System.currentTimeMillis()
        val difficulty = readText(DIFFICULTY_URL).trim().toDoubleOrNull()
            ?: throw IOException("Bitcoin difficulty response is invalid")
        val subsidySatoshis = readText(BLOCK_REWARD_URL).trim().toLongOrNull()
            ?: throw IOException("Bitcoin block subsidy response is invalid")
        val ticker = JSONObject(readText(TICKER_URL))
        val priceInr = parseBtcInr(ticker.toString())

        require(difficulty.isFinite() && difficulty > 0.0) { "Bitcoin difficulty is not positive" }
        require(subsidySatoshis > 0L) { "Bitcoin block subsidy is not positive" }
        require(priceInr.isFinite() && priceInr > 0.0) { "BTC/INR price is not positive" }

        return BitcoinProfitSnapshot(
            difficulty = difficulty,
            networkHashrateHps = networkHashrateFromDifficulty(difficulty),
            blockSubsidyBtc = satoshisToBtc(subsidySatoshis.toDouble()),
            btcPriceInr = priceInr,
            fetchedAtMillis = snapshotStartedAt
        )
    }

    internal fun networkHashrateFromDifficulty(difficulty: Double): Double {
        require(difficulty.isFinite() && difficulty > 0.0)
        // Bitcoin difficulty 1 represents 2^32 expected hashes per block,
        // with the protocol target interval of 600 seconds.
        return difficulty * 4_294_967_296.0 / 600.0
    }

    internal fun satoshisToBtc(satoshis: Double): Double {
        require(satoshis.isFinite() && satoshis >= 0.0)
        return satoshis / 100_000_000.0
    }

    internal fun parseBtcInr(tickerJson: String): Double {
        val ticker = JSONObject(tickerJson)
        return ticker.getJSONObject("INR").getDouble("last")
    }

    private fun readText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/json,text/plain")
            setRequestProperty("User-Agent", "LotteryMiner-Android/1.0")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Market data HTTP $status")
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) {
                        throw IOException("Market data response exceeds 64 KiB safety limit")
                    }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }
}
