package com.lottery.miner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import java.math.BigDecimal
import java.math.RoundingMode
import org.junit.Test

class UtilTest {
    @Test fun acceptsUpperAndLowerAsciiHex() {
        assertArrayEquals(byteArrayOf(0x00, 0xaf.toByte(), 0xFF.toByte()), Util.hexToBytes("00aFfF"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnicodeCharactersThatLookLikeHexDigits() {
        Util.hexToBytes("ＦＦ")
    }

    @Test fun difficultyOneProducesPositiveTarget() {
        val target = Util.difficultyToTarget(1.0)
        assertTrue(target.any { it.toInt() != 0 })
    }

    @Test fun nonIntegerDifficultyUsesItsCanonicalDecimalValueForTargetRounding() {
        val difficulty = 1.1
        val expectedInteger = BigDecimal(Util.DIFF1)
            .divide(BigDecimal("1.1"), 0, RoundingMode.FLOOR)
            .toBigInteger()
        assertArrayEquals(Util.toBytes32(expectedInteger), Util.difficultyToTarget(difficulty))
    }

    @Test fun poolDifficultyTargetDoesNotRoundUpAtFractionalDifficulty() {
        val difficulty = 1.3
        val expectedInteger = BigDecimal(Util.DIFF1)
            .divide(BigDecimal("1.3"), 0, RoundingMode.FLOOR)
            .toBigInteger()
        val actual = Util.difficultyToTarget(difficulty)
        assertArrayEquals(Util.toBytes32(expectedInteger), actual)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDifficultyThatRoundsTargetToZero() {
        Util.difficultyToTarget(Double.MAX_VALUE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCompactTargetThatRoundsToZero() {
        Util.nbitsToTarget("01000001")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeCompactTarget() {
        Util.nbitsToTarget("1d80ffff")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTargetAboveBitcoinMainnetPowLimit() {
        Util.nbitsToTarget("207fffff")
    }
}
