package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BitcoinMarketDataTest {
    @Test fun convertsDifficultyToExpectedNetworkHashrate() {
        val rate = BitcoinMarketData.networkHashrateFromDifficulty(1.0)
        assertEquals(4_294_967_296.0 / 600.0, rate, 1e-9)
        assertTrue(rate > 7_000_000.0)
    }

    @Test fun convertsCurrentBlockSubsidySatoshisToBtc() {
        assertEquals(3.125, BitcoinMarketData.satoshisToBtc(312_500_000.0), 1e-12)
    }

    @Test fun readsInrTickerField() {
        val price = BitcoinMarketData.parseBtcInr(
            """{"INR":{"15m":7000000.0,"last":7012345.67,"buy":7000000.0,"sell":7020000.0,"symbol":"₹"}}"""
        )
        assertEquals(7_012_345.67, price, 1e-8)
    }

    @Test fun rejectsInvalidDifficultyAndNegativeSatoshis() {
        assertTrue(runCatching { BitcoinMarketData.networkHashrateFromDifficulty(0.0) }.isFailure)
        assertTrue(runCatching { BitcoinMarketData.satoshisToBtc(-1.0) }.isFailure)
    }

    @Test fun dailySubsidyUsesCurrentBlockSubsidyAnd144BlockEstimate() {
        val snapshot = BitcoinProfitSnapshot(
            difficulty = 1.0,
            networkHashrateHps = 7_158_278.826,
            blockSubsidyBtc = 3.125,
            btcPriceInr = 7_000_000.0,
            fetchedAtMillis = 1L
        )
        assertEquals(450.0, snapshot.dailySubsidyBtc, 1e-9)
    }
}
