package com.lottery.miner

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Acquires one real Stratum V1 job for a local scanner benchmark.
 * This client subscribes and authorizes only; it never sends mining.submit.
 */
internal data class PoolBenchmarkWork(
    val header: ByteArray,
    val shareTarget: ByteArray,
    val poolHost: String,
    val poolPort: Int,
    val jobId: String,
    val poolDifficulty: Double,
    val fetchedAtMillis: Long
)

internal object StratumBenchmarkWorkProvider {
    fun fetch(
        host: String,
        port: Int,
        address: String,
        timeoutMillis: Long = 20_000L
    ): PoolBenchmarkWork {
        require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() } && !host.contains('/')) {
            "Invalid pool hostname"
        }
        require(port in 1..65535) { "Pool port must be 1..65535" }
        require(Util.isValidBitcoinAddress(address)) { "A valid Bitcoin mainnet payout address is required" }
        require(timeoutMillis in 1_000L..60_000L) { "Pool work timeout must be 1–60 seconds" }

        val completed = CountDownLatch(1)
        val work = AtomicReference<PoolBenchmarkWork?>(null)
        val failure = AtomicReference<Throwable?>(null)

        // Listener callbacks run in the single Stratum reader thread.
        var subscribed = false
        var extranonce1 = byteArrayOf()
        var extranonce2Size = 0
        var authorized = false
        var pendingDifficulty: Double? = null
        var activeDifficulty: Double? = null
        var currentJob: Job? = null
        var lastStatus = "Pool closed before a usable job arrived"

        fun completeIfReady() {
            if (work.get() != null || !subscribed || !authorized) return
            val difficulty = activeDifficulty ?: return
            val job = currentJob ?: return
            try {
                val header = job.buildHeader(extranonce1.copyOf(), ByteArray(extranonce2Size))
                val target = Util.difficultyToTarget(difficulty)
                work.compareAndSet(
                    null,
                    PoolBenchmarkWork(
                        header = header,
                        shareTarget = target,
                        poolHost = host,
                        poolPort = port,
                        jobId = job.id,
                        poolDifficulty = difficulty,
                        fetchedAtMillis = System.currentTimeMillis()
                    )
                )
                if (work.get() != null) completed.countDown()
            } catch (e: Exception) {
                failure.compareAndSet(null, IOException("Could not build the real pool job header", e))
                completed.countDown()
            }
        }

        val listener = object : StratumClient.Listener {
            override fun onSubscribed(extranonce1Value: ByteArray, extranonce2SizeValue: Int) {
                if (subscribed) currentJob = null // a new extranonce allocation invalidates the previous header
                subscribed = true
                extranonce1 = extranonce1Value.copyOf()
                extranonce2Size = extranonce2SizeValue
                completeIfReady()
            }

            override fun onAuthorized() {
                authorized = true
                completeIfReady()
            }

            override fun onDifficulty(diff: Double) {
                pendingDifficulty = diff
            }

            override fun onJob(job: Job) {
                pendingDifficulty?.let {
                    activeDifficulty = it
                    pendingDifficulty = null
                }
                currentJob = job
                completeIfReady()
            }

            override fun onShareSubmitted() {
                // This provider never submits shares.
            }

            override fun onShareResult(accepted: Boolean, reason: String?) {
                // This provider never submits shares.
            }

            override fun onShareUnknown(count: Int) {
                // This provider never submits shares.
            }

            override fun onShareDropped(count: Int) {
                // This provider never submits shares.
            }

            override fun onStatus(msg: String) {
                lastStatus = msg
            }
        }

        val client = StratumClient(host, port, address, listener)
        val connection = Thread({
            try {
                client.connectAndRun()
            } catch (e: Exception) {
                failure.compareAndSet(null, e)
            } finally {
                if (work.get() == null) {
                    failure.compareAndSet(null, IOException(lastStatus))
                    completed.countDown()
                }
            }
        }, "pool-benchmark-connect")

        connection.start()
        try {
            if (!completed.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw IOException("Timed out waiting for a real authorized Stratum job from $host:$port")
            }
            return work.get() ?: throw IOException(
                "No authorized pool job available: ${failure.get()?.message ?: lastStatus}",
                failure.get()
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for pool work", e)
        } finally {
            client.close()
            connection.interrupt()
            try {
                connection.join(2_000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
