package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BenchmarkMathTest {
    @Test fun medianUsesMiddleSampleForOddCount() {
        assertEquals(4.0, BenchmarkMath.median(listOf(8.0, 2.0, 4.0)), 0.0)
    }

    @Test fun medianAveragesMiddleSamplesForEvenCount() {
        assertEquals(5.0, BenchmarkMath.median(listOf(9.0, 1.0, 7.0, 3.0)), 0.0)
    }

    @Test fun recommendationPrefersFewestWorkersWithinOnePointFivePercentOfPeak() {
        val rates = mapOf(1 to 10.0, 2 to 18.0, 4 to 19.8, 8 to 20.0)
        assertEquals(4, BenchmarkMath.recommendWorkers(rates))
    }

    @Test fun recommendationUsesPeakWhenNoLowerWorkerRetainsThreshold() {
        val rates = mapOf(1 to 5.0, 2 to 10.0, 4 to 15.0, 8 to 20.0)
        assertEquals(8, BenchmarkMath.recommendWorkers(rates))
    }

    @Test fun recommendationValidatesInputs() {
        assertThrows(IllegalArgumentException::class.java) {
            BenchmarkMath.median(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            BenchmarkMath.median(listOf(1.0, Double.NaN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BenchmarkMath.recommendWorkers(mapOf(1 to 1.0), 1.1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BenchmarkMath.recommendWorkers(mapOf(0 to 1.0))
        }
    }
}
