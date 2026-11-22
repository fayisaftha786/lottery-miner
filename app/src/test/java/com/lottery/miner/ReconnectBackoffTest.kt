package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectBackoffTest {
    @Test fun repeatedShortAuthenticatedSessionsBackOffInsteadOfResettingAtSixtySeconds() {
        val backoff = ReconnectBackoff()

        assertEquals(5_000L, backoff.nextDelayAfter(150_000L, healthySession = true))
        assertEquals(10_000L, backoff.nextDelayAfter(150_000L, healthySession = true))
        assertEquals(20_000L, backoff.nextDelayAfter(150_000L, healthySession = true))
        assertEquals(30_000L, backoff.nextDelayAfter(150_000L, healthySession = true))
    }

    @Test fun onlyLongAuthenticatedSessionThatReceivedAJobResetsBackoff() {
        val backoff = ReconnectBackoff()

        assertEquals(5_000L, backoff.nextDelayAfter(150_000L, healthySession = false))
        assertEquals(10_000L, backoff.nextDelayAfter(420_000L, healthySession = false))
        assertEquals(20_000L, backoff.nextDelayAfter(299_999L, healthySession = true))
        assertEquals(5_000L, backoff.nextDelayAfter(300_000L, healthySession = true))
        assertEquals(10_000L, backoff.nextDelayAfter(150_000L, healthySession = false))
    }

    @Test fun retryDelayNeverExceedsThirtySeconds() {
        val backoff = ReconnectBackoff()

        repeat(8) {
            backoff.nextDelayAfter(1_000L, healthySession = false)
        }
        assertEquals(30_000L, backoff.nextDelayAfter(1_000L, healthySession = false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeSessionDuration() {
        ReconnectBackoff().nextDelayAfter(-1L, healthySession = false)
    }
}
