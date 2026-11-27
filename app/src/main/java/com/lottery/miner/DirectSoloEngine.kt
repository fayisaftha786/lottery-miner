package com.lottery.miner

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.math.BigInteger
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class DirectSoloConfig(
    val payoutAddress: String,
    val gatewayUrl: String,
    val apiToken: String,
    val threads: Int,
    val onlyCharging: Boolean,
    val maxTemp: Double,
    val maxBattery: Int
)

/**
 * Direct Bitcoin solo miner. Work is built from Bitcoin Core GBT by the Phoenix
 * gateway; the phone hashes the actual network target and submits full candidates.
 */
internal class DirectSoloEngine(private val context: Context, private val config: DirectSoloConfig) {
    companion object {
        private const val CHUNK = 1 shl 15
        private const val U32_SPACE = 0x1_0000_0000L
        private const val WORK_POLL_MS = 8_000L
    }

    @Volatile private var running = false
    @Volatile private var activeWorkers = 0
    private val client = DirectNodeClient(config.gatewayUrl, config.apiToken)
    private val workRef = AtomicReference<DirectWork?>(null)
    private val nonceCursor = AtomicLong(0L)
    private val nonceSpaceExhausted = AtomicBoolean(false)
    private val totalHashes = AtomicLong(0L)
    private val threads = ArrayList<Thread>()
    private val extraNonce = AtomicLong(SecureRandom().nextLong())
    private val workerController = AdaptiveWorkerController()
    private var lastTuningStatus = ""

    @Synchronized
    fun start() {
        if (running) return
        require(Util.isValidBitcoinAddress(config.payoutAddress) && !config.payoutAddress.contains('.')) {
            "A plain Bitcoin mainnet payout address is required"
        }
        require(config.threads in 1..Runtime.getRuntime().availableProcessors()) { "Invalid worker count" }
        running = true
        MinerState.reset()
        MinerState.running = true
        MinerState.directMode = true
        MinerState.totalThreads = config.threads
        MinerState.status = "Direct Bitcoin node gateway പരിശോധിക്കുന്നു…"

        val hw = Native.initCore()
        MinerState.hwSha = hw == 1
        if (hw < 0) {
            MinerState.status = "പിശക്: native SHA-256d self-test"
            MinerState.log("Native SHA-256d self-test failed; direct mining stopped")
            stop()
            return
        }
        MinerState.log(if (hw == 1) "ARM SHA-256 hardware path passed independent self-tests" else "Validated software SHA-256 path selected")

        repeat(config.threads) { index ->
            Thread({ workerLoop(index) }, "phoenix-direct-" + index).also {
                threads.add(it)
                it.start()
            }
        }
        Thread({ gatewayLoop() }, "phoenix-gateway").also { threads.add(it); it.start() }
        Thread({ policyLoop() }, "phoenix-direct-policy").also { threads.add(it); it.start() }
    }

    @Synchronized
    fun stop() {
        running = false
        workRef.set(null)
        client.cancelPendingRequests()
        threads.forEach { it.interrupt() }
        val deadline = SystemClock.elapsedRealtime() + 3_000L
        for (thread in threads) {
            if (thread !== Thread.currentThread()) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                try { thread.join(remaining) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        threads.removeAll { !it.isAlive }
        activeWorkers = 0
        MinerState.running = false
        MinerState.connected = false
        MinerState.hashrate = 0.0
        MinerState.activeThreads = 0
        MinerState.workerTuningStatus = "Direct mining stopped"
        MinerState.status = "നിർത്തി"
    }

    private fun gatewayLoop() {
        var lastJobId = ""
        var longPollId: String? = null
        while (running) {
            try {
                if (nonceSpaceExhausted.compareAndSet(true, false)) {
                    extraNonce.incrementAndGet()
                    nonceCursor.set(0L)
                    workRef.set(null)
                    lastJobId = ""
                    MinerState.log("Nonce space exhausted; new unique coinbase extranonce requested")
                }
                val work = client.fetchWork(config.payoutAddress, extraNonceHex(), longPollId)
                longPollId = work.longPollId
                if (!running) break
                if (work.jobId != lastJobId) {
                    workRef.set(work)
                    nonceCursor.set(0L)
                    lastJobId = work.jobId
                    MinerState.directHeight = work.height
                    MinerState.directJobId = work.jobId
                    MinerState.directTransactionCount = work.transactionCount
                    MinerState.netDiff = runCatching { Util.networkDifficulty(work.bits) }.getOrDefault(0.0)
                    MinerState.lastJobAt = System.currentTimeMillis()
                    MinerState.log("Bitcoin Core template received: height " + work.height + ", " + work.transactionCount + " transactions")
                }
                MinerState.connected = true
                MinerState.status = "Direct node connected · Bitcoin Core template active"
                Thread.sleep(WORK_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                workRef.set(null)
                longPollId = null
                MinerState.connected = false
                activeWorkers = 0
                MinerState.activeThreads = 0
                MinerState.status = "Direct node unavailable: " + (e.message ?: e.javaClass.simpleName)
                MinerState.lastDisconnectReason = e.message ?: e.javaClass.simpleName
                MinerState.log("Direct node gateway error: " + MinerState.lastDisconnectReason)
                sleep(8_000L)
            }
        }
    }

    private fun workerLoop(index: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE)
        val found = IntArray(64)
        val best = DoubleArray(3)
        while (running) {
            if (index >= activeWorkers) { sleep(250L); continue }
            val work = workRef.get()
            if (work == null) { sleep(300L); continue }
            val start = nonceCursor.getAndAdd(CHUNK.toLong())
            if (start >= U32_SPACE) {
                nonceSpaceExhausted.set(true)
                sleep(200L)
                continue
            }
            val count = minOf(CHUNK.toLong(), U32_SPACE - start).toInt()
            if (workRef.get() !== work) continue

            best.fill(0.0)
            val matches = Native.scan(work.header, start.toInt(), count, work.target, found, best)
            if (matches < 0 || matches > found.size || best[2] > 0.0) {
                failClosed("Native scanner returned an invalid or overflowing network-target result")
                break
            }
            totalHashes.addAndGet(count.toLong())
            if (best[0].isFinite() && best[0] > MinerState.bestDiff) MinerState.noteBest(best[0])

            for (i in 0 until matches) {
                if (!running || workRef.get() !== work) break
                val nonce = found[i]
                val header = work.header.copyOf()
                header[76] = nonce.toByte()
                header[77] = (nonce ushr 8).toByte()
                header[78] = (nonce ushr 16).toByte()
                header[79] = (nonce ushr 24).toByte()
                val digest = Native.sha256dHeader(header)
                val value = BigInteger(1, digest.reversedArray())
                if (value > BigInteger(1, work.target)) {
                    failClosed("Native candidate failed independent network-target verification")
                    break
                }
                submitCandidate(work, nonce)
            }
        }
    }

    private fun submitCandidate(work: DirectWork, nonce: Int) {
        if (!running || workRef.get() !== work) return
        MinerState.blockCandidateDetected = true
        MinerState.directCandidates.incrementAndGet()
        MinerState.log("Bitcoin network-target candidate found; submitting complete block to Bitcoin Core")
        try {
            val result = client.submitCandidate(work.jobId, nonce)
            MinerState.directLastResponse = result.response
            if (result.accepted) {
                MinerState.directAccepted.incrementAndGet()
                MinerState.status = "Bitcoin Core accepted block submission · awaiting chain confirmation"
                MinerState.log("Node accepted submitted block " + result.blockHash + "; chain confirmation not yet proven")
            } else {
                MinerState.directRejected.incrementAndGet()
                MinerState.status = "Node checked candidate: " + result.response
                MinerState.log("Node rejected candidate at " + result.stage + ": " + result.response)
            }
        } catch (e: Exception) {
            MinerState.directRejected.incrementAndGet()
            MinerState.directLastResponse = e.message ?: e.javaClass.simpleName
            MinerState.status = "Candidate submission failed: " + MinerState.directLastResponse
            MinerState.log("Candidate submission failed; no payout is inferred")
        }
    }

    private fun policyLoop() {
        var lastHashes = 0L
        var lastTime = System.nanoTime()
        var hot = false
        var batteryLimitReached = false

        while (running) {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            var battery = -1
            var charging = false
            var temperatureC = Double.NaN
            var batteryAvailable = false
            var temperatureAvailable = false
            if (batteryIntent != null) {
                val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) { battery = level * 100 / scale; batteryAvailable = true }
                charging = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                val raw = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                if (raw > 0) { temperatureC = raw / 10.0; temperatureAvailable = true }
            }
            MinerState.battery = battery.coerceAtLeast(0)
            MinerState.batteryAvailable = batteryAvailable
            MinerState.plugged = charging
            MinerState.tempAvailable = temperatureAvailable
            MinerState.tempC = temperatureC

            if (temperatureAvailable && temperatureC >= config.maxTemp) hot = true
            else if (temperatureAvailable && temperatureC <= config.maxTemp - 4.0) hot = false
            if (config.maxBattery >= 100) batteryLimitReached = false
            else if (batteryAvailable && battery >= config.maxBattery) batteryLimitReached = true
            else if (batteryAvailable && battery < config.maxBattery - 2) batteryLimitReached = false

            var cap = config.threads
            var reason = ""
            if (config.onlyCharging && !charging) { cap = 0; reason = "ചാർജിൽ അല്ല" }
            else if (!batteryAvailable) { cap = 0; reason = "Battery telemetry unavailable; paused safely" }
            else if (batteryLimitReached) { cap = 0; reason = "Battery safety limit reached" }
            else if (hot) { cap = 0; reason = "Battery temperature safety pause" }
            else if (!temperatureAvailable) { cap = 1.coerceAtMost(config.threads); reason = "Temperature unavailable; one worker safety cap" }
            else if (temperatureC >= config.maxTemp - 3.0) {
                cap = maxOf(1, config.threads / 2)
                reason = "Temperature approaching limit; worker cap applied"
            }

            val thermalStatus: Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
                }.getOrElse { ThermalWorkerPolicy.UNKNOWN }
            } else null
            MinerState.androidThermalStatus = ThermalWorkerPolicy.label(thermalStatus)
            if (cap > 0 && thermalStatus != null) {
                val decision = ThermalWorkerPolicy.evaluate(config.threads, cap, thermalStatus)
                cap = decision.workerCount
                if (decision.reason.isNotBlank()) reason = decision.reason
            }

            val hasWork = MinerState.connected && workRef.get() != null
            val target = workerController.selectWorkerCount(
                safetyWorkerLimit = if (hasWork) cap else 0,
                currentWorkerCount = activeWorkers,
                nowMillis = SystemClock.elapsedRealtime(),
                currentHashrate = MinerState.hashrate
            )
            activeWorkers = target
            MinerState.activeThreads = target
            MinerState.pauseReason = reason
            MinerState.workerTuningStatus = workerController.statusMessage
            if (lastTuningStatus != workerController.statusMessage) {
                MinerState.log("Adaptive direct workers: " + workerController.statusMessage)
                lastTuningStatus = workerController.statusMessage
            }

            val now = System.nanoTime()
            val total = totalHashes.get()
            val elapsed = (now - lastTime) / 1e9
            if (elapsed > 0.0) {
                val rate = (total - lastHashes).coerceAtLeast(0L) / elapsed
                MinerState.hashrate = if (!rate.isFinite() || rate <= 0.0) 0.0
                    else if (MinerState.hashrate == 0.0) rate else 0.6 * MinerState.hashrate + 0.4 * rate
            }
            MinerState.totalHashes = total
            lastHashes = total
            lastTime = now
            if (!hasWork) MinerState.status = "Waiting for a valid Bitcoin Core block template"
            else if (target == 0) MinerState.status = "Direct mining paused by device safety policy"
            else if (!MinerState.blockCandidateDetected) MinerState.status = "Direct SHA-256d mining · height " + MinerState.directHeight
            sleep(2_000L)
        }
    }

    private fun extraNonceHex(): String {
        return java.lang.Long.toUnsignedString(extraNonce.get(), 16).padStart(16, '0').takeLast(16)
    }

    private fun failClosed(message: String) {
        MinerState.log(message)
        MinerState.status = "Mining stopped safely: " + message
        MinerState.pauseReason = message
        running = false
        MinerState.running = false
        MinerState.connected = false
        activeWorkers = 0
        MinerState.activeThreads = 0
        workRef.set(null)
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }
}
