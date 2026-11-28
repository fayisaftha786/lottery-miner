package com.lottery.miner

import org.junit.Assert.*
import org.junit.Test

class BitcoinMainnetHeaderRulesTest {
    @Test fun medianTimePastUsesSortedMiddleEntry() {
        assertEquals(30L, BitcoinMainnetHeaderRules.medianTimePast(listOf(50, 10, 40, 20, 30)))
        assertEquals(30L, BitcoinMainnetHeaderRules.medianTimePast(listOf(40, 10, 30, 20)))
    }

    @Test fun rejectsTimestampAtOrBeforeMedianAndMoreThanTwoHoursAhead() {
        val history = listOf(100L, 110L, 120L, 130L, 140L)
        assertThrows(IllegalArgumentException::class.java) {
            BitcoinMainnetHeaderRules.checkTimestamp(120L, history, 1_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BitcoinMainnetHeaderRules.checkTimestamp(9_001L, history, 1_800L)
        }
        BitcoinMainnetHeaderRules.checkTimestamp(121L, history, 1_000L)
    }

    @Test fun mainnetCarriesDifficultyOutsideRetargetBoundary() {
        assertEquals(
            0x1d00ffffL,
            BitcoinMainnetHeaderRules.expectedBits(2_017, 0x1d00ffffL, 1_700_000_000L)
        )
    }

    @Test fun mainnetRetargetsAt2016BlockBoundaryWithTwoWeekTarget() {
        assertEquals(
            0x1d00ffffL,
            BitcoinMainnetHeaderRules.expectedBits(
                2_016, 0x1d00ffffL, 1_700_000_000L, 1_698_790_400L
            )
        )
    }

    @Test fun boundaryRequiresTheCorrectAncestorTimestampInput() {
        assertThrows(IllegalArgumentException::class.java) {
            BitcoinMainnetHeaderRules.expectedBits(2_016, 0x1d00ffffL, 1_700_000_000L)
        }
    }

    @Test fun appliesMainnetBuriedVersionFloorsAtActivationHeights() {
        assertEquals(1L, BitcoinMainnetHeaderRules.minimumBlockVersion(227_930))
        assertEquals(2L, BitcoinMainnetHeaderRules.minimumBlockVersion(227_931))
        assertEquals(2L, BitcoinMainnetHeaderRules.minimumBlockVersion(363_724))
        assertEquals(3L, BitcoinMainnetHeaderRules.minimumBlockVersion(363_725))
        assertEquals(3L, BitcoinMainnetHeaderRules.minimumBlockVersion(388_380))
        assertEquals(4L, BitcoinMainnetHeaderRules.minimumBlockVersion(388_381))
    }

    @Test fun rejectsInvalidTimestampHistory() {
        assertThrows(IllegalArgumentException::class.java) {
            BitcoinMainnetHeaderRules.medianTimePast(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            BitcoinMainnetHeaderRules.medianTimePast(listOf(1L, -1L))
        }
    }
}
