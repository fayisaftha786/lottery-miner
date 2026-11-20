package com.lottery.miner

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class StratumClientTest {
    private class Listener : StratumClient.Listener {
        @Volatile var subscribed = false
        @Volatile var extranonce1 = byteArrayOf()
        @Volatile var extranonceUpdates = 0
        @Volatile var unknownShares = 0
        @Volatile var droppedShares = 0
        @Volatile var authorized = false
        @Volatile var difficulty = 0.0
        @Volatile var job: Job? = null
        @Volatile var accepted: Boolean? = null
        val jobReceived = CountDownLatch(1)
        val statuses = CopyOnWriteArrayList<String>()

        override fun onSubscribed(extranonce1: ByteArray, extranonce2Size: Int) {
            subscribed = extranonce2Size == 4
            this.extranonce1 = extranonce1.copyOf()
            extranonceUpdates++
        }
        override fun onAuthorized() { authorized = true }
        override fun onDifficulty(diff: Double) { difficulty = diff }
        override fun onJob(job: Job) { this.job = job; jobReceived.countDown() }
        override fun onShareResult(accepted: Boolean, reason: String?) { this.accepted = accepted }
        override fun onShareUnknown(count: Int) { unknownShares += count }
        override fun onShareDropped(count: Int) { droppedShares += count }
        override fun onStatus(msg: String) { statuses.add(msg) }
    }

    @Test fun exchangesSubscribeAuthorizeJobAndShareWithMockPool() {
        ServerSocket(0).use { server ->
            val serverFailure = AtomicReference<Throwable?>()
            val worker = thread {
                try { server.accept().use { serve(it) } }
                catch (t: Throwable) { serverFailure.set(t) }
            }
            val listener = Listener()
            val client = StratumClient("127.0.0.1", server.localPort, "1BoatSLRHtKNngkdXEeobR76b53LETtpyT", listener)
            val clientThread = thread { client.connectAndRun() }

            assertTrue("pool job was not received; server=${serverFailure.get()}; client=${listener.statuses}",
                listener.jobReceived.await(3, TimeUnit.SECONDS))
            client.submit("test-job", "00000000", "495fab29", "00000001")
            clientThread.join(3000)
            worker.join(3000)

            assertTrue(listener.subscribed)
            assertTrue(listener.authorized)
            assertEquals(2.0, listener.difficulty, 0.0)
            assertEquals("test-job", listener.job?.id)
            assertEquals(true, listener.accepted)
            assertEquals(2, listener.extranonceUpdates)
            assertEquals("cafe", Util.bytesToHex(listener.extranonce1))
            assertEquals(0, listener.unknownShares)
        }
    }

    @Test fun marksPendingShareUnknownWhenPoolDisconnectsBeforeResponse() {
        ServerSocket(0).use { server ->
            val serverFailure = AtomicReference<Throwable?>()
            val worker = thread {
                try { server.accept().use { serveAndDrop(it) } }
                catch (t: Throwable) { serverFailure.set(t) }
            }
            val listener = Listener()
            val client = StratumClient("127.0.0.1", server.localPort, "1BoatSLRHtKNngkdXEeobR76b53LETtpyT", listener)
            val clientThread = thread { client.connectAndRun() }

            assertTrue("pool job was not received; server=${serverFailure.get()}; client=${listener.statuses}",
                listener.jobReceived.await(3, TimeUnit.SECONDS))
            client.submit("test-job", "00000000", "495fab29", "00000001")
            clientThread.join(3000)
            worker.join(3000)

            assertEquals(1, listener.unknownShares)
            assertEquals(null, listener.accepted)
        }
    }

    private fun serveAndDrop(socket: Socket) {
        val input = socket.getInputStream().bufferedReader()
        val output = socket.getOutputStream().bufferedWriter()
        assertEquals("mining.subscribe", JSONObject(input.readLine()).getString("method"))
        output.write(JSONObject().put("id", 1).put("result", JSONArray().put(JSONArray()).put("abcd").put(4)).put("error", JSONObject.NULL).toString() + "\n")
        output.flush()
        input.readLine() // suggest difficulty
        input.readLine() // authorize request
        output.write(JSONObject().put("id", 2).put("result", true).put("error", JSONObject.NULL).toString() + "\n")
        val job = JSONArray().put("test-job").put("00".repeat(32)).put("0102").put("0304")
            .put(JSONArray()).put("00000001").put("1d00ffff").put("495fab29").put(true)
        output.write(JSONObject().put("method", "mining.notify").put("params", job).toString() + "\n")
        output.flush()
        val submit = JSONObject(input.readLine())
        assertEquals("mining.submit", submit.getString("method"))
        socket.close() // Deliberately omit the submit response.
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream().bufferedReader()
        val output = socket.getOutputStream().bufferedWriter()
        assertEquals("mining.subscribe", JSONObject(input.readLine()).getString("method"))
        output.write(JSONObject().put("id", 1).put("result", JSONArray().put(JSONArray()).put("abcd").put(4)).put("error", JSONObject.NULL).toString() + "\n")
        output.flush()
        assertEquals("mining.suggest_difficulty", JSONObject(input.readLine()).getString("method"))
        assertEquals("mining.authorize", JSONObject(input.readLine()).getString("method"))
        output.write(JSONObject().put("id", 2).put("result", true).put("error", JSONObject.NULL).toString() + "\n")
        output.write(JSONObject().put("method", "mining.set_extranonce").put("params", JSONArray().put("cafe").put(4)).toString() + "\n")
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
