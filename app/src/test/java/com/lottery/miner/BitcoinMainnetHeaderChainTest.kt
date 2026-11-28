package com.lottery.miner

import org.junit.Assert.*
import org.junit.Test

class BitcoinMainnetHeaderChainTest {
    private val payout = "bc1qe4xhqarfw9tqaxaad3cx6ayl0r93ehyqf5uses"

    @Test fun startsFromTheVerifiedMainnetGenesisCheckpoint() {
        val chain = BitcoinMainnetHeaderChain.fromGenesis()
        val tip = chain.tip()
        assertEquals(0L, tip.height)
        assertEquals(
            "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f",
            tip.hashDisplay
        )
        assertEquals(0x1d00ffffL, tip.bits)
        assertTrue(tip.cumulativeChainWork.signum() > 0)
    }

    @Test fun doesNotCreateCandidateUntilHeaderSyncMovesPastGenesis() {
        val chain = BitcoinMainnetHeaderChain.fromGenesis()
        assertThrows(IllegalArgumentException::class.java) {
            chain.buildCoinbaseCandidate(payout, byteArrayOf(1, 2), 1_800_000_000L)
        }
    }

    @Test fun returnsDefensiveCopyOfTipHash() {
        val chain = BitcoinMainnetHeaderChain.fromGenesis()
        val first = chain.tip()
        first.hashInternal.fill(0)
        val second = chain.tip()
        assertEquals(
            "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f",
            second.hashDisplay
        )
        assertFalse(second.hashInternal.all { it == 0.toByte() })
    }
}
