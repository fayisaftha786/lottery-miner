package com.lottery.miner

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Scans one supplied coinbase-only candidate across disjoint uint32 nonce lanes.
 * A cancelled scan reports the exact number of hashes completed before returning.
 */
class EightLaneSha256dScanner(
    private val workerCount: Int = 8,
    private val batchSize: Int = 32_768
) {
    data class Progress(
        val totalHashes: Long,
        val hashesPerSecond: Double,
        val elapsedSeconds: Double,
        val workerHashes: List<Long>,
        val nonceSpaceCompleted: Boolean
    )

    data class FoundBlock(
        val nonce: Long,
        val blockBytes: ByteArray,
        val totalHashes: Long,
        val elapsedSeconds: Double,
        val hashesPerSecond: Double
    )

    private val stopRequested = AtomicBoolean(false)

    init {
        require(workerCount in 1..8) { "phone scanner supports 1..8 workers" }
        require(batchSize in 1..1_000_000)
    }

    fun cancel() {
        stopRequested.set(true)
    }

    fun scan(candidate: BitcoinCoinbaseTemplate.Candidate, onProgress: (Progress) -> Unit = {}): FoundBlock? {
        check(Native.initCore() >= 0) { "native SHA256d self-test failed" }
        stopRequested.set(false)
        val lanes = NonceLanes(workerCount)
        val total = AtomicLong(0L)
        val workerHashes = LongArray(workerCount)
        val found = AtomicReference<FoundBlock?>(null)
        val failed = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(workerCount)
        val startedAt = System.nanoTime()
        val pool = Executors.newFixedThreadPool(workerCount) { runnable ->
            Thread(runnable, "p2p-sha256d-worker").apply {
                isDaemon = false
                priority = Thread.NORM_PRIORITY - 1
            }
        }
        val target = candidate.target.toUnsigned32()
        val header = candidate.headerWithoutNonce.copyOf()

        repeat(workerCount) { workerId ->
            pool.execute {
                try {
                    var ordinal = 0L
                    val foundNonces = IntArray(8)
                    val best = DoubleArray(3)
                    var lastReportOrdinal = -16L
                    while (!stopRequested.get() && found.get() == null) {
                        val batch = lanes.nextBatch(workerId, ordinal, batchSize) ?: break
                        val countFound = Native.scanStrided(
                            header, batch.startNonce, batch.stride, batch.count,
                            target, foundNonces, best
                        )
                        check(countFound in 0..foundNonces.size) { "native strided scan failed: " + countFound }
                        val doneHashes = total.addAndGet(batch.count.toLong())
                        workerHashes[workerId] += batch.count.toLong()
                        ordinal += batch.count.toLong()

                        if (countFound > 0) {
                            val nonce = foundNonces[0].toLong() and 0xffffffffL
                            val block = candidate.serializeBlock(nonce)
                            val elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0
                            val rate = if (elapsed > 0.0) doneHashes / elapsed else 0.0
                            found.compareAndSet(null, FoundBlock(nonce, block, doneHashes, elapsed, rate))
                            stopRequested.set(true)
                            break
                        }

                        if (ordinal - lastReportOrdinal >= batchSize.toLong() * 16L) {
                            lastReportOrdinal = ordinal
                            val elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0
                            try {
                                onProgress(Progress(
                                    doneHashes,
                                    if (elapsed > 0.0) doneHashes / elapsed else 0.0,
                                    elapsed,
                                    workerHashes.toList(),
                                    false
                                ))
                            } catch (_: Exception) { }
                        }
                    }
                } catch (t: Throwable) {
                    failed.compareAndSet(null, t)
                    stopRequested.set(true)
                } finally {
                    done.countDown()
                }
            }
        }

        try {
            while (!done.await(1, TimeUnit.SECONDS)) {
                if (Thread.currentThread().isInterrupted) {
                    stopRequested.set(true)
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } finally {
            if (stopRequested.get()) pool.shutdownNow() else pool.shutdown()
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) pool.shutdownNow()
        }
        failed.get()?.let { throw IllegalStateException("SHA256d lane failed", it) }
        val result = found.get()
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0
        val count = total.get()
        try {
            onProgress(Progress(
                count,
                if (elapsed > 0.0) count / elapsed else 0.0,
                elapsed,
                workerHashes.toList(),
                !stopRequested.get()
            ))
        } catch (_: Exception) { }
        if (result != null) {
            return result.copy(
                totalHashes = count,
                elapsedSeconds = elapsed,
                hashesPerSecond = if (elapsed > 0.0) count / elapsed else 0.0
            )
        }
        if (stopRequested.get()) return null
        return null
    }

    private fun java.math.BigInteger.toUnsigned32(): ByteArray {
        require(signum() > 0 && bitLength() <= 256) { "invalid proof-of-work target" }
        var raw = toByteArray()
        if (raw.size == 33 && raw[0] == 0.toByte()) raw = raw.copyOfRange(1, raw.size)
        require(raw.size <= 32)
        return ByteArray(32).also { raw.copyInto(it, 32 - raw.size) }
    }
}
