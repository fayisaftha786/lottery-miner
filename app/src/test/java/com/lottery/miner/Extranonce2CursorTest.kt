package com.lottery.miner

import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Extranonce2CursorTest {
    @Test fun emitsFixedWidthValuesAndPreservesLeadingZeroes() {
        val cursor = Extranonce2Cursor(3)

        assertArrayEquals(byteArrayOf(0, 0, 0), cursor.allocate())
        assertArrayEquals(byteArrayOf(0, 0, 1), cursor.allocate())
        assertArrayEquals(byteArrayOf(0, 0, 2), cursor.allocate())
    }

    @Test fun carriesAcrossBytesWithoutDuplicates() {
        val cursor = Extranonce2Cursor(2, BigInteger("255"))

        assertArrayEquals(byteArrayOf(0, -1), cursor.allocate())
        assertArrayEquals(byteArrayOf(1, 0), cursor.allocate())
    }

    @Test fun supportsEightByteValuesAboveSignedLongBoundary() {
        val firstUnsignedHighBitValue = BigInteger("8000000000000000", 16)
        val cursor = Extranonce2Cursor(8, firstUnsignedHighBitValue)

        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 0, 0, 0),
            cursor.allocate()
        )
        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 0, 0, 1),
            cursor.allocate()
        )
    }

    @Test fun supportsTheFullSixteenByteWidth() {
        val first = BigInteger("80000000000000000000000000000000", 16)
        val cursor = Extranonce2Cursor(16, first)
        val expected = ByteArray(16).also { it[0] = 0x80.toByte() }

        assertArrayEquals(expected, cursor.allocate())
        expected[15] = 1
        assertArrayEquals(expected, cursor.allocate())
    }

    @Test fun returnsNullOnlyAfterTheNegotiatedSpaceIsExhausted() {
        val cursor = Extranonce2Cursor(1, BigInteger("fe", 16))

        assertArrayEquals(byteArrayOf(0xfe.toByte()), cursor.allocate())
        assertArrayEquals(byteArrayOf(0xff.toByte()), cursor.allocate())
        assertNull(cursor.allocate())
        assertNull(cursor.allocate())
    }

    @Test fun resetStartsAnewNegotiatedAllocationAtZero() {
        val cursor = Extranonce2Cursor(1)
        assertArrayEquals(byteArrayOf(0), cursor.allocate())
        cursor.reset(2)

        assertArrayEquals(byteArrayOf(0, 0), cursor.allocate())
        assertArrayEquals(byteArrayOf(0, 1), cursor.allocate())
    }

    @Test fun rejectsInvalidWidthsAndOutOfRangeInitialValues() {
        assertFalse(runCatching { Extranonce2Cursor(0) }.isSuccess)
        assertFalse(runCatching { Extranonce2Cursor(17) }.isSuccess)
        assertFalse(runCatching { Extranonce2Cursor(1, BigInteger("100", 16)) }.isSuccess)
        assertFalse(runCatching { Extranonce2Cursor(2, BigInteger.valueOf(-1)) }.isSuccess)
    }

    @Test fun concurrentWorkersReceiveUniqueValues() {
        val cursor = Extranonce2Cursor(2)
        val values = ConcurrentHashMap.newKeySet<String>()
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val workers = (0 until 8).map {
            thread(start = true) {
                ready.countDown()
                start.await()
                repeat(100) {
                    val next = cursor.allocate() ?: error("unexpected exhaustion")
                    val hex = next.joinToString("") { byte ->
                        "%02x".format(byte.toInt() and 0xff)
                    }
                    values.add(hex)
                }
            }
        }

        ready.await()
        start.countDown()
        workers.forEach { it.join() }

        assertEquals(800, values.size)
        assertTrue(values.contains("0000"))
        assertTrue(values.contains("031f"))
    }
}
