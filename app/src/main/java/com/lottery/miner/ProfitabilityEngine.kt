package com.lottery.miner

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class MiningMarketSnapshot(
    val btcHashpriceUsdPerPhDay: Double,
    val btcPriceUsd: Double?,
    val usdInr: Double,
    val hashpriceUpdatedAtMillis: Long?,
    val fxUpdatedAtMillis: Long,
    val downloadedAtMillis: Long,
    val hashpriceSource: String = ProfitabilityMarketData.HASHPRICE_URL,
    val fxSource: String = ProfitabilityMarketData.FX_URL
)

data class BitcoinProfitEstimate(
    val grossRevenueUsdPerDay: Double,
    val revenueAfterPoolFeeUsdPerDay: Double,
    val revenueAfterPoolFeeInrPerDay: Double,
    val energyKwhPerDay: Double,
    val electricityCostInrPerDay: Double,
    val expectedNetInrPerDay: Double,
    val breakEvenHashrateHps: Double
)

/**
 * Expected-value calculator for Bitcoin SHA-256 hashrate.
 *
 * This is not a payout estimator. In CKPool solo mining, expected value is
 * probabilistic; ordinary accepted shares do not pay out.
 */
object ProfitabilityEngine {
    private const val HPS_PER_PHPS = 1_000_000_000_000_000.0
    private const val HOURS_PER_DAY = 24.0
    private const val WH_PER_KWH = 1000.0

    fun estimateBitcoin(
        hashrateHps: Double,
        powerWatts: Double,
        tariffInrPerKwh: Double,
        usdInr: Double,
        hashpriceUsdPerPhDay: Double,
        poolFeePercent: Double
    ): BitcoinProfitEstimate {
        require(hashrateHps.isFinite() && hashrateHps >= 0.0) {
            "Hashrate must be finite and non-negative"
        }
        require(powerWatts.isFinite() && powerWatts in 0.0..2_000.0) {
            "Power draw must be between 0 and 2000 W"
        }
        require(tariffInrPerKwh.isFinite() && tariffInrPerKwh in 0.0..100_000.0) {
            "Electricity tariff is outside the accepted range"
        }
        require(usdInr.isFinite() && usdInr in 1.0..10_000.0) {
            "USD/INR rate is invalid"
        }
        require(hashpriceUsdPerThDay.isFinite() && hashpriceUsdPerThDay in 0.0..1_000.0) {
            "BTC hashprice is invalid"
        }
        require(poolFeePercent.isFinite() && poolFeePercent in 0.0..100.0) {
            "Pool fee must be between 0 and 100%"
        }

        val grossUsd = (hashrateHps / HPS_PER_PHPS) * hashpriceUsdPerThDay
        val afterFeeUsd = grossUsd * (1.0 - poolFeePercent / 100.0)
        val afterFeeInr = afterFeeUsd * usdInr
        val energyKwh = powerWatts * HOURS_PER_DAY / WH_PER_KWH
        val electricityInr = energyKwh * tariffInrPerKwh
        val netInr = afterFeeInr - electricityInr

        val contributionPerHashrateInr =
            (hashpriceUsdPerThDay * (1.0 - poolFeePercent / 100.0) * usdInr) /
                HPS_PER_THPS
        val breakEven = if (contributionPerHashrateInr > 0.0) {
            electricityInr / contributionPerHashrateInr
        } else {
            Double.POSITIVE_INFINITY
        }

        return BitcoinProfitEstimate(
            grossRevenueUsdPerDay = grossUsd,
            revenueAfterPoolFeeUsdPerDay = afterFeeUsd,
            revenueAfterPoolFeeInrPerDay = afterFeeInr,
            energyKwhPerDay = energyKwh,
            electricityCostInrPerDay = electricityInr,
            expectedNetInrPerDay = netInr,
            breakEvenHashrateHps = breakEven
        )
    }
}

/**
 * Low-frequency public market-data adapter. The BTC hashprice provider documents
 * a 100-request/day free tier; the app calls it only on explicit user refresh.
 * The FX source updates daily and requires attribution in the app UI.
 */
object ProfitabilityMarketData {
    const val HASHPRICE_URL = "https://pro.startmining.io/api/market-summary"
    const val FX_URL = "https://open.er-api.com/v6/latest/USD"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    fun fetch(): MiningMarketSnapshot {
        val hashJson = getJson(HASHPRICE_URL)
        val market = hashJson.optJSONObject("data")
            ?: hashJson.optJSONObject("market")
            ?: hashJson
        val hashprice = firstPositiveNumber(
            market,
            "hashpriceUsd",
            "hashPriceUsd",
            "hashprice_usd"
        ) ?: throw IOException("BTC hashprice field missing from market response")
        val btcPrice = firstPositiveNumber(market, "btcPrice", "btcPriceUsd", "bitcoinPriceUsd")
        val hashFetchedAt = firstTimestamp(market, "updatedAt", "timestamp", "updated_at")
            ?: System.currentTimeMillis()

        val fxJson = getJson(FX_URL)
        if (!fxJson.optString("result").equals("success", ignoreCase = true)) {
            throw IOException("USD/INR reference rate provider did not return success")
        }
        val rates = fxJson.optJSONObject("rates") ?: throw IOException("FX rates missing")
        val usdInr = rates.optDouble("INR", Double.NaN)
        if (!usdInr.isFinite() || usdInr !in 1.0..10_000.0) {
            throw IOException("USD/INR reference rate is invalid")
        }
        val fxTimestamp = fxJson.optLong("time_last_update_unix", 0L)
            .takeIf { it > 0L }?.times(1000L)
            ?: System.currentTimeMillis()

        return MiningMarketSnapshot(
            btcHashpriceUsdPerPhDay = hashprice,
            btcPriceUsd = btcPrice,
            usdInr = usdInr,
            hashpriceUpdatedAtMillis = hashFetchedAt,
            fxUpdatedAtMillis = fxTimestamp,
            downloadedAtMillis = System.currentTimeMillis()
        )
    }

    private fun getJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "LotteryMiner-Android/1.0")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Market-data HTTP $code")
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8)
                .use(BufferedReader::readText)
            if (body.length > 256_000) throw IOException("Market-data response too large")
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun firstPositiveNumber(json: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            val value = json.optDouble(key, Double.NaN)
            if (value.isFinite() && value > 0.0) return value
        }
        return null
    }

    private fun firstTimestamp(json: JSONObject, vararg keys: String): Long? {
        for (key in keys) {
            val value = json.opt(key) ?: continue
            val millis = when (value) {
                is Number -> {
                    val n = value.toLong()
                    if (n in 1_000_000_000L..9_999_999_999L) n * 1000L else n
                }
                is String -> value.toLongOrNull()?.let { n ->
                    if (n in 1_000_000_000L..9_999_999_999L) n * 1000L else n
                }
                else -> null
            }
            if (millis != null &&
                millis in 1_000_000_000_000L..(System.currentTimeMillis() + 86_400_000L)
            ) return millis
        }
        return null
    }
}
