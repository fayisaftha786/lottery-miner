package com.lottery.miner

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class StratumClientTest {
    private class Listener : StratumClient.Listener {
        @Volatile var subscribed = false
        @Volatile var authorized = false
        @Volatile var difficulty = 0.0
        @Volatile var job: Job? = null
        @Volatile var accepted: Boolean? = null
        var client: StratumClient? = null

        override fun onSubscribed(extranonce1: ByteArray, extranonce2Size: Int) {
            subscribed = extranonce1.contentEquals(byteArrayOf(0xab.toByte(), 0xcd.toByte())) &&
                extranonce2Size == 4
        }
        override fun onAuthorized() { authorized = true }
        override fun onDifficulty(diff: Double) { difficulty = diff }
        override fun onJob(job: Job) {
            this.job = job
            // Trigger a real submit from the test client so the mock server can respond.
            client?.submit(job.id, "00000001", job.ntimeHex, "00000001")
        }
        override fun onShareResult(accepted: Boolean, reason: String?) { this.accepted = accepted }
        override fun onStatus(msg: String) { }
    }

    @Test fun exchangesSubscribeAuthorizeJobAndShareWithMockPool() {
        ServerSocket(0).use { server ->
            val serverError = AtomicReference<Throwable?>(null)
            val worker = thread {
                try {
                    server.accept().use { serve(it) }
                } catch (t: Throwable) {
                    serverError.set(t)
                }
            }
            val listener = Listener()
            val client = StratumClient(
                "127.0.0.1", server.localPort, "1BoatSLRHtKNngkdXEeobR76b53LETtpyT", listener
            )
            listener.client = client

            client.connectAndRun()
            worker.join(3000)

            assertFalse("Mock pool thread should finish", worker.isAlive)
            serverError.get()?.let { throw AssertionError("Mock pool failed", it) }
            assertTrue(listener.subscribed)
            assertTrue(listener.authorized)
            assertEquals(2.0, listener.difficulty, 0.0)
            assertEquals("test-job", listener.job?.id)
            assertEquals(true, listener.accepted)
        }
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream().bufferedReader()
        val output = socket.getOutputStream().bufferedWriter()
        assertEquals("mining.subscribe", JSONObject(input.readLine()).getString("method"))
        output.write(
            JSONObject()
                .put("id", 1)
                .put("result", JSONArray().put(JSONArray()).put("abcd").put(4))
                .put("error", JSONObject.NULL)
                .toString() + "\n"
        )
        output.flush()
        assertEquals("mining.suggest_difficulty", JSONObject(input.readLine()).getString("method"))
        assertEquals("mining.authorize", JSONObject(input.readLine()).getString("method"))
        output.write(JSONObject().put("id", 2).put("result", true).put("error", JSONObject.NULL).toString() + "\n")
        output.write(JSONObject().put("method", "mining.set_difficulty").put("params", JSONArray().put(2.0)).toString() + "\n")
        val job = JSONArray().put("test-job").put("00".repeat(32)).put("0102").put("0304")
            .put(JSONArray()).put("00000001").put("1d00ffff").put("495fab29").put(true)
        output.write(JSONObject().put("method", "mining.notify").put("params", job).toString() + "\n")
        output.flush()
        val submit = JSONObject(input.readLine() ?: error("Client closed before submitting a share"))
        assertEquals("mining.submit", submit.getString("method"))
        assertEquals("1BoatSLRHtKNngkdXEeobR76b53LETtpyT", submit.getJSONArray("params").getString(0))
        assertEquals("test-job", submit.getJSONArray("params").getString(1))
        output.write(
            JSONObject().put("id", submit.getInt("id")).put("result", true)
                .put("error", JSONObject.NULL).toString() + "\n"
        )
        output.flush()
    }
}
