package com.lottery.miner

import org.junit.Assert.*
import org.junit.Test

class BitcoinCoinbaseTemplateTest {
    private val payout = "bc1qe4xhqarfw9tqaxaad3cx6ayl0r93ehyqf5uses"

    @Test fun computesSubsidyAtHalvingBoundaries() {
        assertEquals(5_000_000_000L, BitcoinCoinbaseTemplate.subsidyForHeight(0))
        assertEquals(2_500_000_000L, BitcoinCoinbaseTemplate.subsidyForHeight(210_000))
        assertEquals(1_250_000_000L, BitcoinCoinbaseTemplate.subsidyForHeight(420_000))
        assertEquals(0L, BitcoinCoinbaseTemplate.subsidyForHeight(210_000L * 64L))
    }

    @Test fun decodesMainnetNativeSegwitPayoutScript() {
        val script = BitcoinCoinbaseTemplate.payoutScriptForAddress(payout)
        assertEquals(22, script.size)
        assertEquals(0, script[0].toInt())
        assertEquals(20, script[1].toInt())
    }

    @Test fun buildsBip34CoinbaseAndSingleTransactionMerkleRoot() {
        val candidate = BitcoinCoinbaseTemplate.build(
            height = 900_000,
            previousHashInternal = ByteArray(32),
            version = 0x20000000L,
            timestamp = 1_800_000_000L,
            bits = 0x1d00ffffL,
            payoutAddress = payout,
            extraNonce = byteArrayOf(1, 2, 3, 4)
        )
        assertEquals(80, candidate.headerWithoutNonce.size)
        assertTrue(candidate.coinbaseTransaction.size > 80)
        // 900000 = 0x0dbba0, minimally pushed little-endian (03 a0 bb 0d).
        val needle = byteArrayOf(3, 0xa0.toByte(), 0xbb.toByte(), 0x0d)
        assertTrue(candidate.coinbaseTransaction.indices.any { start ->
            start + needle.size <= candidate.coinbaseTransaction.size &&
                candidate.coinbaseTransaction.copyOfRange(start, start + needle.size).contentEquals(needle)
        })
    }

    @Test fun rejectsTargetsEasierThanTheMainnetProofOfWorkLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            BitcoinCoinbaseTemplate.build(
                900_000, ByteArray(32), 0x20000000L, 1_800_000_000L,
                0x1e00ffffL, payout, byteArrayOf(1)
            )
        }
    }

    @Test fun rejectsBadAddressesAndInvalidBlockMetadata() {
        assertThrows(IllegalArgumentException::class.java) {
            BitcoinCoinbaseTemplate.payoutScriptForAddress("bc1qnotavalidaddress")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BitcoinCoinbaseTemplate.build(900_000, ByteArray(31), 1, 1, 0x1d00ffffL, payout, byteArrayOf(1))
        }
    }
}
