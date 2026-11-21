package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OceanStatsTest {
    @Test fun parsesWorkerRatesAndWalletAggregateInHashesPerSecond() {
        val snapshot = OceanStats.parseResponse(
            """{
              "user_hashrate":{"hashrate_60s":300000000000.0},
              "workers":[
                {"name":"asic-a","hashrate_60s":100000000000.0,"hashrate_300s":90000000000.0},
                {"name":"asic-b","hashrate_60s":200000000000.0,"hashrate_300s":180000000000.0}
              ]
            }"""
        )
        assertEquals(2, snapshot.workers.size)
        assertEquals("asic-a", snapshot.workers[0].name)
        assertEquals(100000000000.0, snapshot.workers[0].hashrate, 0.1)
        assertEquals(90000000000.0, snapshot.workers[0].hashrate5m, 0.1)
        assertEquals(300000000000.0, snapshot.totalHashrate, 0.1)
    }

    @Test fun fallsBackToSumOfWorkersWhenWalletAggregateMissing() {
        val snapshot = OceanStats.parseResponse(
            """{"workers":[
              {"worker":"rig-1","hashrate_60s":12.0,"hashrate_300s":10.0},
              {"worker":"rig-2","hashrate_60s":8.0,"hashrate_300s":7.0}
            ]}"""
        )
        assertEquals(20.0, snapshot.totalHashrate, 0.001)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsUnexpectedApiShapeInsteadOfShowingFalseZero() {
        OceanStats.parseResponse("""{"message":"temporarily unavailable"}""")
    }

    @Test fun handlesEmptyWorkersAsNoExternalMining() {
        val snapshot = OceanStats.parseResponse(
            """{"user_hashrate":{"hashrate_60s":0},"workers":[]}"""
        )
        assertTrue(snapshot.workers.isEmpty())
        assertEquals(0.0, snapshot.totalHashrate, 0.0)
    }
    @Test fun treatsInactiveUserErrorAsValidEmptyWorkerResponse() {
        val snapshot = OceanStats.parseResponse("""{"error":"User appears to be inactive."}""")
        assertTrue(snapshot.workers.isEmpty())
        assertEquals(0.0, snapshot.totalHashrate, 0.0)
        assertEquals("User appears to be inactive.", snapshot.notice)
    }

    @Test fun treatsNoActiveWorkersAsValidEmptyWorkerResponse() {
        val snapshot = OceanStats.parseResponse(
            """{"error":"No such user or user has no active workers"}"""
        )
        assertTrue(snapshot.workers.isEmpty())
        assertEquals(0.0, snapshot.totalHashrate, 0.0)
        assertTrue(snapshot.notice?.contains("no active workers") == true)
    }

    @Test fun treatsNoSuchUserOrWorkerAsValidEmptyWorkerResponse() {
        val snapshot = OceanStats.parseResponse(
            """{"error":"No such user/worker"}"""
        )
        assertTrue(snapshot.workers.isEmpty())
        assertEquals(0.0, snapshot.totalHashrate, 0.0)
        assertEquals("No such user/worker", snapshot.notice)
    }

    @Test(expected = IllegalStateException::class)
    fun doesNotHideUnexpectedOceanApiErrorsAsInactiveWallets() {
        OceanStats.parseResponse("""{"error":"database unavailable"}""")
    }

}
