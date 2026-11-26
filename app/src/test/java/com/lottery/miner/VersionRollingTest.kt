package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionRollingTest {
    @Test fun acceptsOnlyEightDigitMasksWithinAdvertisedBits() {
        assertEquals(0x1fffe000L, VersionRolling.parseMask("1fffe000"))
        assertEquals(0x00002000L, VersionRolling.parseMask("00002000"))
        assertNull(VersionRolling.parseMask(null))
        assertNull(VersionRolling.parseMask("20000000"))
        assertEquals(0L, VersionRolling.parseMask("00000000"))
        assertNull(VersionRolling.parseMask("1fffe00"))
        assertNull(VersionRolling.parseMask("zzzzzzzz"))
    }

    @Test fun initialNegotiationRequiresAtLeastOneVersionBitButMidSessionMayUseZeroMask() {
        assertTrue(VersionRolling.meetsRequestedMinimumBitCount(0x00002000L))
        assertTrue(!VersionRolling.meetsRequestedMinimumBitCount(0L))
        assertTrue(!VersionRolling.meetsRequestedMinimumBitCount(0x20000000L))
        assertEquals(0L, VersionRolling.parseMask("00000000"))
        assertEquals(1L, VersionRolling.variantCount(0L))
    }

    @Test fun fixedVersionHasExactlyOneVariant() {
        assertEquals(1L, VersionRolling.variantCount(0L))
        assertEquals(0L, VersionRolling.variantAt(0L, 0L))
    }

    @Test fun enumeratesEverySubmaskExactlyOnce() {
        val mask = 0x0000a000L
        val values = (0L until VersionRolling.variantCount(mask))
            .map { VersionRolling.variantAt(mask, it) }
            .toSet()

        assertEquals(4, values.size)
        assertTrue(values.containsAll(setOf(0x0000L, 0x2000L, 0x8000L, 0xa000L)))
    }

    @Test fun fullAdvertisedMaskProducesTheExpectedNumberOfVariants() {
        assertEquals(65536L, VersionRolling.variantCount(VersionRolling.REQUESTED_MASK))
        assertEquals(0x1fffe000L, VersionRolling.variantAt(VersionRolling.REQUESTED_MASK, 65535L))
    }

    @Test fun rejectsInvalidMasksAndOrdinals() {
        assertTrue(runCatching { VersionRolling.variantCount(0x20000000L) }.isFailure)
        assertTrue(runCatching { VersionRolling.variantAt(1L, -1L) }.isFailure)
        assertTrue(runCatching { VersionRolling.variantAt(1L, 2L) }.isFailure)
    }
}
