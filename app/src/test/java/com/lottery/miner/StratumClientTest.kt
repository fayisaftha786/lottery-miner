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
        @Volatile var submitted = 0
        @Volatile var versionMask = 0L
        val jobReceived = CountDownLatch(1)
        val authorizedLatch = CountDownLatch(1)
        val statuses = CopyOnWriteArrayList<String>()
        val shareEvents = CopyOnWriteArrayList<String>()
        @Volatile var onShareSubmittedHook: (() -> Unit)? = null

        override fun onSubscribed(extranonce1: ByteArray, extranonce2Size: Int) {
            subscribed = extranonce2Size == 4
            this.extranonce1 = extranonce1.copyOf()
            extranonceUpdates++
        }
        override fun onAuthorized() { authorized = true; authorizedLatch.countDown() }
        override fun onDifficulty(diff: Double) { difficulty = diff }
        override fun onJob(job: Job) { this.job = job; jobReceived.countDown() }
        override fun onShareSubmitted() { onShareSubmittedHook?.invoke(); submitted++; shareEvents.add("submitted") }
        override fun onShareResult(accepted: Boolean, reason: String?) {
            this.accepted = accepted
            shareEvents.add(if (accepted) "accepted" else "rejected")
        }
        override fun onShareUnknown(count: Int) { unknownShares += count }
        override fun onShareDropped(count: Int) { droppedShares += count }
        override fun onVersionMask(mask: Long) { versionMask = mask }
        override fun onStatus(msg: String) { statuses.add(msg) }
    }

    @Test fun negotiatesVersionRollingAndSubmitsTheExactVersionBits() {
        ServerSocket(0).use { server ->
            val serverFailure = AtomicReference<Throwable?>()
            val responseSent = CountDownLatch(1)
            val hookFailure = AtomicReference<Throwable?>()
            val worker = thread {
                try { server.accept().use { serve(it, responseSent) } }
                catch (t: Throwable) { serverFailure.set(t) }
            }
            val listener = Listener()
            listener.onShareSubmittedHook = {
                if (!responseSent.await(3, TimeUnit.SECONDS)) {
                    hookFailure.set(AssertionError("mock pool did not send the share response during submission callback"))
                }
            }
            val client = StratumClient("127.0.0.1", server.localPort, "1BoatSLRHtKNngkdXEeobR76b53LETtpyT", listener)
            val clientThread = thread { client.connectAndRun() }

            assertTrue("pool job was not received; server=${serverFailure.get()}; client=${listener.statuses}",
                listener.jobReceived.await(3, TimeUnit.SECONDS))
            val submitThread = thread {
                client.submit("test-job", "00000000", "495fab29", "00000001", versionBits = 0x00004000L)
            }
            submitThread.join(3000)
            assertTrue("submit callback did not finish", !submitThread.isAlive)
            clientThread.join(3000)
            worker.join(3000)

            assertEquals(null, hookFailure.get())
            assertTrue(listener.subscribed)
            assertTrue(listener.authorized)
            assertEquals(2.0, listener.difficulty, 0.0)
            assertEquals("test-job", listener.job?.id)
            assertEquals(1, listener.submitted)
            assertEquals(true, listener.accepted)
            assertEquals(listOf("submitted", "accepted"), listener.shareEvents.toList())
            assertEquals(2, listener.extranonceUpdates)
            assertEquals("cafe", Util.bytesToHex(listener.extranonce1))
            assertEquals(0x00006000L, listener.versionMask)
            assertEquals(0, listener.unknownShares)
        }
    }

    @Test fun fallsBackToFixedVersionWhenPoolDeclinesVersionRolling() {
        ServerSocket(0).use { server ->
            val serverFailure = AtomicReference<Throwable?>()
            val worker = thread {
                try { server.accept().use { serveAndDrop(it, rollingEnabled = false) } }
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

            assertEquals(0L, listener.versionMask)
            assertEquals(1, listener.submitted)
            assertEquals(1, listener.unknownShares)
            assertEquals(null, listener.accepted)
        }
    }

    @Test fun reportsSharesDroppedWhenPendingQueueIsFull() {
        val difficultyRequestReceived = CountDownLatch(1)
        ServerSocket(0).use { server ->
            val serverFailure = AtomicReference<Throwable?>()
            val worker = thread {
                try {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        val output = socket.getOutputStream().bufferedWriter()
                        val configure = JSONObject(input.readLine())
                        assertEquals("mining.configure", configure.getString("method"))
                        output.write(JSONObject().put("id", 10)
                            .put("result", JSONObject().put("version-rolling", false))
                            .put("error", JSONObject.NULL).toString() + "\n")
                        output.flush()
                        assertEquals("mining.subscribe", JSONObject(input.readLine()).getString("method"))
                        output.write(subscribeResponse() + "\n")
                        output.flush()
                        assertEquals("mining.authorize", JSONObject(input.readLine()).getString("method"))
                        output.write(authorizeResponse() + "\n")
                        output.flush()
                        val difficultyRequest = JSONObject(input.readLine())
                        assertEquals("mining.suggest_difficulty", difficultyRequest.getString("method"))
                        assertEquals(1, difficultyRequest.getJSONArray("params").getInt(0))
                        difficultyRequestReceived.countDown()
                        repeat(256) {
                            val line = input.readLine() ?: throw AssertionError("socket closed before all queued submits arrived")
                            assertEquals("mining.submit", JSONObject(line).getString("method"))
                        }
                        // Keep the socket open until the test closes the client; otherwise EOF can
                        // clear pending submissions before the 257th queue-capacity check.
                        while (input.readLine() != null) { }
                    }
                } catch (t: Throwable) { serverFailure.set(t) }
            }
            val listener = Listener()
            val client = StratumClient("127.0.0.1", server.localPort, "1BoatSLRHtKNngkdXEeobR76b53LETtpyT", listener)
            val clientFailure = AtomicReference<Throwable?>()
            val clientThread = thread {
                try { client.connectAndRun() }
                catch (failure: Throwable) { clientFailure.set(failure) }
            }
            assertTrue("pool authorization timed out", listener.authorizedLatch.await(3, TimeUnit.SECONDS))
            assertTrue("pool did not receive post-authorization difficulty request",
                difficultyRequestReceived.await(3, TimeUnit.SECONDS))
            repeat(257) { client.submit("test-job", "00000000", "495fab29", "%08x".format(it)) }
            assertEquals(1, listener.droppedShares)
            client.close()
            clientThread.join(3000)
            worker.join(3000)
            assertEquals(null, clientFailure.get())
            assertEquals(null, serverFailure.get())
            assertEquals(256, listener.unknownShares)
        }
    }

    private fun serveAndDrop(socket: Socket, rollingEnabled: Boolean) {
        val input = socket.getInputStream().bufferedReader()
        val output = socket.getOutputStream().bufferedWriter()
        val configure = JSONObject(input.readLine())
        assertEquals("mining.configure", configure.getString("method"))
        val result = JSONObject().put("version-rolling", rollingEnabled)
        if (rollingEnabled) result.put("version-rolling.mask", "00000003")
        output.write(JSONObject().put("id", 10).put("result", result)
            .put("error", JSONObject.NULL).toString() + "\n")
        output.flush()
        assertEquals("mining.subscribe", JSONObject(input.readLine()).getString("method"))
        output.write(subscribeResponse() + "\n")
        output.flush()
        assertEquals("mining.authorize", JSONObject(input.readLine()).getString("method"))
        output.write(authorizeResponse() + "\n")
        output.flush()
        assertEquals("mining.suggest_difficulty", JSONObject(input.readLine()).getString("method"))
        sendJob(output)
        output.flush()
        val submit = JSONObject(input.readLine())
        assertEquals("mining.submit", submit.getString("method"))
        assertEquals(if (rollingEnabled) 6 else 5, submit.getJSONArray("params").length())
        socket.close() // Deliberately omit the submit response.
    }

    private fun serve(socket: Socket, responseSent: CountDownLatch? = null) {
        val input = socket.getInputStream().bufferedReader()
        val output = socket.getOutputStream().bufferedWriter()
        val configure = JSONObject(input.readLine())
        assertEquals("mining.configure", configure.getString("method"))
        assertEquals("version-rolling", configure.getJSONArray("params").getJSONArray(0).getString(0))
        assertEquals("1fffe000", configure.getJSONArray("params").getJSONObject(1).getString("version-rolling.mask"))
        output.write(JSONObject().put("id", 10)
            .put("result", JSONObject().put("version-rolling", true).put("version-rolling.mask", "00006000"))
            .put("error", JSONObject.NULL).toString() + "\n")
        output.flush()
        assertEquals("mining.subscribe", JSONObject(input.readLine()).getString("method"))
        output.write(subscribeResponse() + "\n")
        output.flush()
        assertEquals("mining.authorize", JSONObject(input.readLine()).getString("method"))
        output.write(authorizeResponse() + "\n")
        output.flush()
        val difficultyRequest = JSONObject(input.readLine())
        assertEquals("mining.suggest_difficulty", difficultyRequest.getString("method"))
        assertEquals(1, difficultyRequest.getJSONArray("params").getInt(0))
        output.write(JSONObject().put("method", "mining.set_extranonce")
            .put("params", JSONArray().put("cafe").put(4)).toString() + "\n")
        output.write(JSONObject().put("method", "mining.set_difficulty")
            .put("params", JSONArray().put(2.0)).toString() + "\n")
        sendJob(output)
        output.flush()
        val submit = JSONObject(input.readLine())
        assertEquals("mining.submit", submit.getString("method"))
        assertEquals(6, submit.getJSONArray("params").length())
        assertEquals("00004000", submit.getJSONArray("params").getString(5))
        output.write(JSONObject().put("id", submit.getInt("id")).put("result", true)
            .put("error", JSONObject.NULL).toString() + "\n")
        output.flush()
        responseSent?.countDown()
    }

    private fun subscribeResponse(): String =
        JSONObject().put("id", 1)
            .put("result", JSONArray().put(JSONArray()).put("abcd").put(4))
            .put("error", JSONObject.NULL).toString()

    private fun authorizeResponse(): String =
        JSONObject().put("id", 2).put("result", true)
            .put("error", JSONObject.NULL).toString()

    private fun sendJob(output: java.io.BufferedWriter) {
        val job = JSONArray().put("test-job").put("00".repeat(32)).put("0102").put("0304")
            .put(JSONArray()).put("00000001").put("1d00ffff").put("495fab29").put(true)
        output.write(JSONObject().put("method", "mining.notify").put("params", job).toString() + "\n")
    }
}
