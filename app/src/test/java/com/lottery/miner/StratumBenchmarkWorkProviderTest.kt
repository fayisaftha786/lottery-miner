package com.lottery.miner

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class StratumBenchmarkWorkProviderTest {
    @Test fun fetchesRealStratumHeaderAndTargetWithoutSubmittingAnyShare() {
        ServerSocket(0).use { server ->
            val serverFailure = AtomicReference<Throwable?>()
            val submitSeen = AtomicReference(false)
            val serverThread = thread(name = "mock-stratum-pool") {
                try {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        val output = socket.getOutputStream().bufferedWriter()

                        val subscribe = JSONObject(input.readLine())
                        assertEquals("mining.subscribe", subscribe.getString("method"))
                        output.write(
                            JSONObject().put("id", 1)
                                .put("result", JSONArray().put(JSONArray()).put("cafe").put(4))
                                .put("error", JSONObject.NULL).toString() + "\n"
                        )
                        output.flush()

                        val authorize = JSONObject(input.readLine())
                        assertEquals("mining.authorize", authorize.getString("method"))
                        output.write(
                            JSONObject().put("id", 2).put("result", true)
                                .put("error", JSONObject.NULL).toString() + "\n"
                        )
                        output.flush()

                        val suggest = JSONObject(input.readLine())
                        assertEquals("mining.suggest_difficulty", suggest.getString("method"))

                        output.write(
                            JSONObject().put("method", "mining.set_difficulty")
                                .put("params", JSONArray().put(2.0)).toString() + "\n"
                        )
                        val job = JSONArray()
                            .put("job-real-1")
                            .put("00".repeat(32))
                            .put("0102")
                            .put("0304")
                            .put(JSONArray())
                            .put("00000001")
                            .put("1d00ffff")
                            .put("495fab29")
                            .put(true)
                        output.write(
                            JSONObject().put("method", "mining.notify")
                                .put("params", job).toString() + "\n"
                        )
                        output.flush()

                        while (true) {
                            val line = input.readLine() ?: break
                            if (JSONObject(line).optString("method") == "mining.submit") {
                                submitSeen.set(true)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    serverFailure.set(t)
                }
            }

            val work = StratumBenchmarkWorkProvider.fetch(
                host = "127.0.0.1",
                port = server.localPort,
                address = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
                timeoutMillis = 3_000L
            )
            serverThread.join(3_000L)

            assertEquals("job-real-1", work.jobId)
            assertEquals("127.0.0.1", work.poolHost)
            assertEquals(server.localPort, work.poolPort)
            assertEquals(2.0, work.poolDifficulty, 0.0)
            assertArrayEquals(Util.difficultyToTarget(2.0), work.shareTarget)
            assertTrue(work.fetchedAtMillis > 0L)

            val expectedCoinbase = Util.hexToBytes("0102cafe000000000304")
            assertArrayEquals(
                Util.sha256d(expectedCoinbase),
                work.header.copyOfRange(36, 68)
            )
            assertFalse("Benchmark provider must never send mining.submit", submitSeen.get())
            assertNull(serverFailure.get())
            assertFalse("Mock pool socket should close after work acquisition", serverThread.isAlive)
        }
    }
}
