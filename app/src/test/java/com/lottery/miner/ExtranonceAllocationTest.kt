package com.lottery.miner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtranonceAllocationTest {
    @Test fun identicalAllocationIsRecognizedAsRepeat() {
        val allocation = ExtranonceAllocation(byteArrayOf(1, 2, 3), 4)

        assertTrue(allocation.matches(byteArrayOf(1, 2, 3), 4))
    }

    @Test fun changedExtranonce1InvalidatesAllocation() {
        val allocation = ExtranonceAllocation(byteArrayOf(1, 2, 3), 4)

        assertFalse(allocation.matches(byteArrayOf(1, 2, 9), 4))
    }

    @Test fun changedExtranonce2SizeInvalidatesAllocation() {
        val allocation = ExtranonceAllocation(byteArrayOf(1, 2, 3), 4)

        assertFalse(allocation.matches(byteArrayOf(1, 2, 3), 5))
    }

    @Test fun allocationDefensivelyCopiesInputBytes() {
        val input = byteArrayOf(1, 2, 3)
        val allocation = ExtranonceAllocation(input, 4)
        input[0] = 9

        assertTrue(allocation.matches(byteArrayOf(1, 2, 3), 4))
        assertFalse(allocation.matches(input, 4))
    }
}
