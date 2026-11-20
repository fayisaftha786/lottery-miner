package com.lottery.miner

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class StratumClientTest {
    private class Listener : StratumClient.Listener {
        var subscribed = false
        var extranonce1 = byteArrayOf()
        var extranonceUpdates = 0
        var unknownShares = 0
        var authorized = false
        var difficulty = 0.0
        var job: Job? = null
        var accepted: Boolean? = null
        override fun onSubscribed(extranonce1: ByteArray, extranonce2Size: Int) {
            subscribed = extranonce2Size == 4
            this.extranonce1 = extranonce1.copyOf()
            extranonceUpdates++
        }
        override fun onAuthorized() { authorized = true }
        override fun onDifficulty(diff: Double) { difficulty = diff }
        override fun onJob(job: Job) { this.job = job }
        override fun onShareResult(accepted: Boolean, reason: String?) { this.accepted = accepted }
        override fun onShareUnknown(count: Int) { unknownShares += count }
        override fun onStatus(msg: String) { }
    }

    @Test fun exchangesSubscribeAuthorizeJobAndShareWithMockPool() {
        ServerSocket(0).use { server ->
            val worker = thread { server.accept().use { serve(it) } }
            val listener = Listener()
            StratumClient("127.0.0.1", server.localPort, "1BoatSLRHtKNngkdXEeobR76b53LETtpyT", listener).connectAndRun()
            worker.join(3000)
            assertTrue(listener.subscribed)
            assertTrue(listener.authorized)
            assertEquals(2.0, listener.difficulty, 0.0)
            assertEquals("test-job", listener.job?.id)
            assertEquals(true, listener.accepted)
            assertEquals(2, listener.extranonceUpdates)
            assertEquals("cafe", Util.bytesToHex(listener.extranonce1))
        }
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream().bufferedReader()
        val output = socket.getOutputStream().bufferedWriter()
        assertEquals("mining.subscribe", JSONObject(input.readLine()).getString("method"))
        output.write(JSONObject().put("id", 1).put("result", JSONArray().put(JSONArray()).put("abcd").put(4).toString()).put("error", JSONObject.NULL).toString() + "\n")
        output.flush()
        assertEquals("mining.suggest_difficulty", JSONObject(input.readLine()).getString("method"))
        assertEquals("mining.authorize", JSONObject(input.readLine()).getString("method"))
        output.write(JSONObject().put("id", 2).put("result", true).put("error", JSONObject.NULL).toString() + "\n")
        output.write(JSONObject().put("method", "mining.set_extranonce").put("params", JSONArray().put("cafe").put(4)).toString() + "\\n")
        output.write(JSONObject().put("method", "mining.set_difficulty").put("params", JSONArray().put(2.0)).toString() + "\n")
        val job = JSONArray().put("test-job").put("00".repeat(32)).put("0102").put("0304")
            .put(JSONArray()).put("00000001").put("1d00ffff").put("495fab29").put(true)
        output.write(JSONObject().put("method", "mining.notify").put("params", job).toString() + "\n")
        output.flush()
        val submit = JSONObject(input.readLine())
        assertEquals("mining.submit", submit.getString("method"))
        output.write(JSONObject().put("id", submit.getInt("id")).put("result", true).put("error", JSONObject.NULL).toString() + "\n")
        output.flush()
    }
}
