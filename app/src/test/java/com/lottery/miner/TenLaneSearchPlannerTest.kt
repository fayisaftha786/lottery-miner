package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TenLaneSearchPlannerTest {
    @Test fun tenLanesCoverFiniteCounterSpaceExactlyOnce() {
        val max = 4095L
        val all = (0 until 10).flatMap { worker ->
            val lane = TenLaneSearchPlanner.lane(worker, 10, max)
            (0 until lane.capacity()).mapNotNull { lane.counterAt(it.toLong()) }
        }
        assertEquals(max + 1L, all.size.toLong())
        assertEquals(max + 1L, all.toSet().size.toLong())
        assertEquals((0L..max).toSet(), all.toSet())
    }

    @Test fun eachLaneHasNoDuplicatesAndStopsAtItsCapacity() {
        for (workers in 1..10) {
            for (worker in 0 until workers) {
                val lane = TenLaneSearchPlanner.lane(worker, workers, 255L)
                val values = (0 until lane.capacity()).mapNotNull { lane.counterAt(it) }
                assertEquals(values.size, values.toSet().size)
                assertTrue(values.all { it % workers == worker.toLong() })
                assertNull(lane.counterAt(lane.capacity()))
            }
        }
    }

    @Test fun lanesUseDifferentTraversalOrdersWithoutChangingCoverage() {
        val a = TenLaneSearchPlanner.lane(0, 10, 999L)
        val b = TenLaneSearchPlanner.lane(1, 10, 999L)
        val aValues = (0 until 10).mapNotNull { a.counterAt(it.toLong()) }
        val bValues = (0 until 10).mapNotNull { b.counterAt(it.toLong()) }
        assertNotEquals(aValues, bValues)
    }

    @Test fun rejectsMoreThanTenLanes() {
        assertTrue(runCatching { TenLaneSearchPlanner.lane(0, 11, 100L) }.isFailure)
    }
}
