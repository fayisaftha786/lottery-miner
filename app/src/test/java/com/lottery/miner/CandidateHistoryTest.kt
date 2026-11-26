package com.lottery.miner

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateHistoryTest {
    private fun record(hash: String, detectedAt: Long = 100L) = CandidateHistory.Record(
        jobId = "job-$hash",
        extranonce2 = "01020304",
        ntime = "65a0b1c2",
        nonce = "01000000",
        versionBits = "00000001",
        header = "ab".repeat(80),
        hash = hash,
        networkTarget = "00".repeat(32),
        detectedAtMs = detectedAt
    )

    @Test fun appendsAndPreservesTheExactCandidateTuple() {
        val result = CandidateHistory.append(null, record("hash-a"))
        assertFalse(result.duplicate)
        val item = JSONArray(result.json).getJSONObject(0)
        assertEquals("job-hash-a", item.getString("job_id"))
        assertEquals("01020304", item.getString("extranonce2"))
        assertEquals("65a0b1c2", item.getString("ntime"))
        assertEquals("01000000", item.getString("nonce"))
        assertEquals("00000001", item.getString("version_bits"))
        assertEquals("ab".repeat(80), item.getString("header"))
        assertEquals("hash-a", item.getString("hash"))
        assertEquals(100L, item.getLong("detected_at_ms"))
    }

    @Test fun suppressesDuplicateHashesWithoutAddingAnotherRecord() {
        val first = CandidateHistory.append(null, record("same"))
        val second = CandidateHistory.append(first.json, record("same", 200L))
        assertTrue(second.duplicate)
        assertEquals(1, JSONArray(second.json).length())
        assertEquals(100L, JSONArray(second.json).getJSONObject(0).getLong("detected_at_ms"))
    }

    @Test fun delayedResponseUpdatesOnlyTheMatchingHistoryEntry() {
        var json = CandidateHistory.append(null, record("older")).json
        json = CandidateHistory.append(json, record("newer", 200L)).json
        val updated = CandidateHistory.updateState(json, "older", "POOL_REJECTED", 300L)
        assertTrue(updated.matched)
        val history = JSONArray(updated.json)
        assertEquals("POOL_REJECTED", history.getJSONObject(0).getString("submission_state"))
        assertEquals(300L, history.getJSONObject(0).getLong("last_response_at_ms"))
        assertEquals("DETECTED_NOT_CONFIRMED", history.getJSONObject(1).getString("submission_state"))
        assertFalse(CandidateHistory.updateState(updated.json, "missing", "RESPONSE_UNKNOWN", 400L).matched)
    }

    @Test fun retainsOnlyTheNewestOneHundredCandidates() {
        var json: String? = null
        repeat(105) { index ->
            json = CandidateHistory.append(json, record("hash-$index", index.toLong())).json
        }
        val history = JSONArray(requireNotNull(json))
        assertEquals(100, history.length())
        assertEquals("hash-5", history.getJSONObject(0).getString("hash"))
        assertEquals("hash-104", history.getJSONObject(99).getString("hash"))
    }

    @Test fun malformedStoredJsonRecoversWithoutCrashing() {
        val result = CandidateHistory.append("{not-json", record("recovered"))
        assertFalse(result.duplicate)
        assertEquals(1, JSONArray(result.json).length())
    }
}
