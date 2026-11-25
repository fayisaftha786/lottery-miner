package com.lottery.miner

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSON codec for the bounded block-candidate journal.
 *
 * Keeping this separate from SharedPreferences makes duplicate handling,
 * history trimming and delayed-response updates unit-testable on the JVM.
 */
internal object CandidateHistory {
    const val MAX_RECORDS = 100

    data class Record(
        val jobId: String,
        val extranonce2: String,
        val ntime: String,
        val nonce: String,
        val header: String,
        val hash: String,
        val networkTarget: String,
        val detectedAtMs: Long
    )

    data class AppendResult(val json: String, val duplicate: Boolean, val count: Int)
    data class UpdateResult(val json: String, val matched: Boolean)

    fun append(rawJson: String?, record: Record): AppendResult {
        val history = parse(rawJson)
        val exists = (0 until history.length()).any { index ->
            history.optJSONObject(index)?.optString("hash") == record.hash
        }
        if (exists) return AppendResult(history.toString(), duplicate = true, count = history.length())

        history.put(
            JSONObject()
                .put("job_id", record.jobId)
                .put("extranonce2", record.extranonce2)
                .put("ntime", record.ntime)
                .put("nonce", record.nonce)
                .put("header", record.header)
                .put("hash", record.hash)
                .put("network_target", record.networkTarget)
                .put("submission_state", "DETECTED_NOT_CONFIRMED")
                .put("detected_at_ms", record.detectedAtMs)
        )
        while (history.length() > MAX_RECORDS) history.remove(0)
        return AppendResult(history.toString(), duplicate = false, count = history.length())
    }

    fun updateState(
        rawJson: String?,
        candidateHash: String,
        state: String,
        responseAtMs: Long
    ): UpdateResult {
        val history = parse(rawJson)
        var matched = false
        for (index in 0 until history.length()) {
            val item = history.optJSONObject(index) ?: continue
            if (item.optString("hash") == candidateHash) {
                item.put("submission_state", state)
                item.put("last_response_at_ms", responseAtMs)
                matched = true
                break
            }
        }
        return UpdateResult(history.toString(), matched)
    }

    private fun parse(rawJson: String?): JSONArray {
        if (rawJson.isNullOrBlank()) return JSONArray()
        return try {
            JSONArray(rawJson)
        } catch (_: Exception) {
            JSONArray()
        }
    }
}
