package com.lottery.miner

import org.junit.Assert.*
import org.junit.Test

class NonceLanesTest {
    @Test fun eightWorkersStartAtDistinctNoncesAndUseEightStride() {
        val lanes = NonceLanes(8)
        val first = (0 until 8).map { lanes.nextBatch(it, 0L, 128)!! }
        assertEquals((0L..7L).toList(), first.map { it.startNonce })
        assertTrue(first.all { it.stride == 8L && it.count == 128 })
        val visited = first.flatMap { batch ->
            (0 until batch.count).map { batch.startNonce + it * batch.stride }
        }
        assertEquals(visited.size, visited.toSet().size)
        assertEquals(1024, visited.size)
    }

    @Test fun lanesCoverAdjacentNonceValuesWithoutGapsOrOverlap() {
        val lanes = NonceLanes(8)
        val firstTen = (0 until 8).flatMap { worker ->
            (0 until 10).map { ordinal -> lanes.nonceAt(worker, ordinal.toLong()) }
        }.sorted()
        assertEquals((0L..79L).toList(), firstTen)
    }

    @Test fun finalBatchNeverWrapsPastUint32Max() {
        val lanes = NonceLanes(8)
        for (worker in 0 until 8) {
            val total = lanes.nonceCount(worker)
            val batch = lanes.nextBatch(worker, total - 1L, 100)
            assertNotNull(batch)
            assertEquals(1, batch!!.count)
            assertEquals(NonceLanes.MAX_NONCE - ((7 - worker).toLong()), batch.startNonce)
            assertEquals(NonceLanes.MAX_NONCE - ((7 - worker).toLong()), batch.lastNonce)
            assertNull(lanes.nextBatch(worker, total, 100))
        }
    }

    @Test fun rejectsInvalidWorkerAndBatchInputs() {
        val lanes = NonceLanes(8)
        assertThrows(IllegalArgumentException::class.java) { lanes.nonceAt(8, 0) }
        assertThrows(IllegalArgumentException::class.java) { lanes.nextBatch(0, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { NonceLanes(0) }
    }
}
