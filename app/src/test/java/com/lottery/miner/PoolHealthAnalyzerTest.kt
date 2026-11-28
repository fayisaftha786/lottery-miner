package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoolHealthAnalyzerTest {
    private fun analyze(
        connected: Boolean = true,
        authorized: Long = 10_000L,
        job: Long = 95_000L,
        response: Long = 98_000L,
        now: Long = 100_000L,
        submitted: Int = 12,
        accepted: Int = 10,
        rejected: Int = 1,
        unknown: Int = 1,
        dropped: Int = 0,
        rate: Double = 40_000_000.0,
        workers: Int = 4
    ) = PoolHealthAnalyzer.analyze(
        connected, authorized, job, response, now,
        submitted, accepted, rejected, unknown, dropped, rate, workers
    )

    @Test fun reportsHealthySessionAndRealShareRatios() {
        val s = analyze()
        assertEquals("POOL RESPONSE INCOMPLETE", s.state)
        assertEquals(5L, s.jobAgeSeconds)
        assertEquals(2L, s.responseAgeSeconds)
        assertEquals(90.9090909, s.acceptancePercent!!, 1e-6)
        assertEquals(91.6666667, s.responseCoveragePercent!!, 1e-6)
        assertEquals(10_000_000.0, s.hashratePerActiveWorker!!, 0.0)
    }

    @Test fun identifiesDisconnectedAndStaleJobs() {
        assertEquals("DISCONNECTED", analyze(connected = false).state)
        assertEquals("JOB MAY BE STALE", analyze(job = 1_000L).state)
    }

    @Test fun doesNotInventRatiosWhenNoSharesHaveResponses() {
        val s = analyze(submitted = 0, accepted = 0, rejected = 0, unknown = 0, response = 0L)
        assertNull(s.acceptancePercent)
        assertNull(s.responseCoveragePercent)
    }

    @Test fun invalidCounterRelationshipsAreRejected() {
        assertTrue(runCatching {
            analyze(submitted = 1, accepted = 2, rejected = 0, unknown = 0)
        }.isFailure)
    }

    @Test fun unknownResponsesRemainVisibleAndAreNotCountedAsRejected() {
        val s = analyze(submitted = 5, accepted = 3, rejected = 1, unknown = 1)
        assertEquals(1, s.unknown)
        assertEquals(1, s.rejected)
        assertEquals(4, s.accepted + s.rejected)
        assertEquals(80.0, s.responseCoveragePercent!!, 0.0)
    }
}
