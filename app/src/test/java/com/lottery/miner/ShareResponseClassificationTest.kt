package com.lottery.miner

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareResponseClassificationTest {
    @Test fun acceptsOnlyTrueResultWithoutError() {
        val response = JSONObject("""{"id":101,"result":true,"error":null}""")
        val classified = classifyShareResponse(response)
        assertEquals(true, classified.accepted)
        assertEquals(null, classified.reason)
    }

    @Test fun classifiesNullResultWithJsonRpcErrorAsRejected() {
        val response = JSONObject("""{"id":102,"result":null,"error":[23,"Low difficulty share",null]}""")
        val classified = classifyShareResponse(response)
        assertEquals(false, classified.accepted)
        assertEquals("""[23,"Low difficulty share",null]""", classified.reason)
    }

    @Test fun classifiesFalseResultWithoutErrorAsRejected() {
        val response = JSONObject("""{"id":103,"result":false,"error":null}""")
        val classified = classifyShareResponse(response)
        assertEquals(false, classified.accepted)
        assertEquals("Pool rejected share (result=false)", classified.reason)
    }

    @Test fun classifiesTrueResultWithErrorAsRejected() {
        val response = JSONObject("""{"id":104,"result":true,"error":[21,"Job not found",null]}""")
        val classified = classifyShareResponse(response)
        assertEquals(false, classified.accepted)
        assertEquals("""[21,"Job not found",null]""", classified.reason)
    }

    @Test fun treatsNullResultWithoutErrorAsUnknown() {
        val response = JSONObject("""{"id":105,"result":null,"error":null}""")
        val classified = classifyShareResponse(response)
        assertEquals(null, classified.accepted)
        assertEquals("Malformed pool share response", classified.reason)
    }
}
