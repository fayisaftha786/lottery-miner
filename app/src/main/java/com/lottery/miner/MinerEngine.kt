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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class MinerConfig(
    val address: String,
    val host: String,
    val port: Int,
    val threads: Int,
    val onlyCharging: Boolean,
    val maxTemp: Double,
    val maxBattery: Int
)

class MinerEngine(private val ctx: Context, private val cfg: MinerConfig) : StratumClient.Listener {

    companion object {
        private const val SPACE = 0x100000000L
    }

    @Volatile private var running = false
    @Volatile private var client: StratumClient? = null
    @Volatile private var current: Job? = null
    @Volatile private var en1 = ByteArray(0)
    @Volatile private var en2Size = 4
    private var hasExtranonceAllocation = false
    private val difficultyTargets = PoolDifficultyTargets()
    @Volatile private var shareTarget: ByteArray = difficultyTargets.activeTarget()
    @Volatile private var activeThreads = 0
    private val totalHashes = AtomicLong(0)
    private val jobGeneration = AtomicLong(0)
    private val workLock = Any()
    private val extranonceEpoch = AtomicLong(0)
    private val reconnectingForExhaustion = AtomicBoolean(false)
    private val adaptiveWorkerController = AdaptiveWorkerController()
    private val threads = ArrayList<Thread>()

    @Synchronized fun start() {
        threads.removeAll { !it.isAlive }
        if (running) return
        if (threads.any { it.isAlive }) {
            MinerState.status = "മുൻ worker-കൾ പൂർണ്ണമായി നിർത്തിയിട്ടില്ല"
            MinerState.log("പഴയ worker thread അവസാനിക്കുന്നതുവരെ restart തടഞ്ഞു")
            return
        }
        running = true
        MinerState.reset()
        MinerState.running = true
        MinerState.totalThreads = cfg.threads

        val hw = Native.initCore()
        MinerState.hwSha = hw == 1
        when (hw) {
            1 -> MinerState.log("ARM hardware SHA-256 ഓൺ")
            0 -> MinerState.log("software SHA-256 (hardware ഇല്ല)")
            else -> {
                MinerState.log("C കോർ self-test പരാജയപ്പെട്ടു, നിർത്തുന്നു")
                MinerState.status = "പിശക്: self-test"
                stop()
                return
            }
        }

        for (i in 0 until cfg.threads) {
            val t = Thread({ workerLoop(i) }, "miner-$i")
            threads.add(t)
            t.start()
        }
        val conn = Thread({ connectionLoop() }, "stratum")
        val pol = Thread({ policyLoop() }, "policy")
        threads.add(conn)
        threads.add(pol)
        conn.start()
        pol.start()
    }

    @Synchronized fun stop() {
        running = false
        synchronized(workLock) {
            current = null
            jobGeneration.incrementAndGet()
        }
        client?.close()
        val stopping = threads.toList()
        stopping.forEach { it.interrupt() }
        val deadline = System.nanoTime() + 3_000_000_000L
        for (t in stopping) {
            if (t !== Thread.currentThread()) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0L) break
                try { t.join(maxOf(1L, remainingNanos / 1_000_000L)) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
        }
        threads.removeAll { !it.isAlive }
        MinerState.running = false
        MinerState.connected = false
        MinerState.hashrate = 0.0
        // Do not leave the last rolling measurement on screen after stop.
        // The next session starts a fresh monotonic-time window in policyLoop.
        MinerState.localHashrate5m = 0.0
        MinerState.localHashrateWindowSeconds = 0.0
        MinerState.localHashrate30m = 0.0
        MinerState.localHashrate30mWindowSeconds = 0.0
        MinerState.activeThreads = 0
        if (MinerState.status != "പിശക്: self-test") MinerState.status = "നിർത്തി"
    }

    override fun onSubscribed(extranonce1: ByteArray, extranonce2Size: Int) {
        synchronized(workLock) {
            if (extranonce2Size !in 1..16 || extranonce1.size > 32) {
                MinerState.status = "Pool invalid extranonce അയച്ചു"
                client?.close()
                return
            }
            val allocationChanged = !hasExtranonceAllocation || !ExtranonceAllocation(en1, en2Size).matches(extranonce1, extranonce2Size)
            if (reconnectingForExhaustion.get() && !allocationChanged) {
                MinerState.status = "Pool reused exhausted extranonce; mining stopped to prevent duplicate work"
                MinerState.pauseReason = "Pool വീണ്ടും അതേ exhausted extranonce നൽകി; duplicate shares ഒഴിവാക്കാൻ mining നിർത്തി"
                MinerState.running = false
                running = false
                MinerState.activeThreads = 0
                MinerState.log("Pool reused the exhausted extranonce allocation; stopped safely")
                client?.close()
                return
            }
            if (allocationChanged) {
                // A changed extranonce allocation invalidates old coinbase/header work.
                // An identical repeat must not interrupt a valid active job.
                current = null
                jobGeneration.incrementAndGet()
                MinerState.activeThreads = 0
                if (MinerState.connected) MinerState.status = "Pool extranonce മാറി; പുതിയ job കാത്തിരിക്കുന്നു"
                en1 = extranonce1.copyOf()
                en2Size = extranonce2Size
                hasExtranonceAllocation = true
                extranonceEpoch.incrementAndGet()
            }
            reconnectingForExhaustion.set(false)
            val allocationStatus = if (allocationChanged) "പുതിയത്" else "തുടരുന്നു"
            MinerState.log("subscribe ശരി (extranonce2 = $extranonce2Size ബൈറ്റ്; allocation $allocationStatus)")
        }
    }
    override fun onAuthorized() {
        MinerState.connected = true
        MinerState.authorizedAt = System.currentTimeMillis()
        MinerState.lastDisconnectReason = ""
        MinerState.activeThreads = 0
        MinerState.status = "Pool അംഗീകരിച്ചു; ജോബിനായി കാത്തിരിക്കുന്നു"
        MinerState.log("Pool authorization ശരി")
    }

    override fun onDifficulty(diff: Double) {
        synchronized(workLock) {
            // Stratum V1 difficulty changes take effect on the next mining.notify.
            // Keep the current job and its target active until that job arrives.
            difficultyTargets.stage(diff)
            // Keep the displayed/active difficulty aligned with the current job.
            // The staged value becomes active only when the next job arrives.
            MinerState.log("പൂൾ difficulty = $diff ലഭിച്ചു; അടുത്ത job മുതൽ പ്രാബല്യത്തിൽ വരും")
        }
    }
    override fun onJob(job: Job) {
        synchronized(workLock) {
            MinerState.lastJobAt = System.currentTimeMillis()
            current = null
            jobGeneration.incrementAndGet()
            shareTarget = difficultyTargets.activateForNextJob()
            MinerState.poolDiff = difficultyTargets.activeDifficulty()
            MinerState.netDiff = Util.networkDifficulty(job.nbitsHex)
            MinerState.status = "പുതിയ job ലഭിച്ചു; workers ആരംഭിക്കുന്നു"
            if (job.clean) MinerState.log("പുതിയ ബ്ലോക്ക് ജോബ് (job ${job.id})")
            current = job
        }
    }
    override fun onShareSubmitted() {
        MinerState.submittedShares.incrementAndGet()
        MinerState.lastShareSubmittedAt = System.currentTimeMillis()
        MinerState.log("Share pool-ലേക്ക് അയച്ചു; response കാത്തിരിക്കുന്നു")
    }

    override fun onShareUnknown(count: Int) {
        if (count > 0) {
            MinerState.unknownShares.addAndGet(count)
            MinerState.log("കണക്ഷൻ നഷ്ടപ്പെട്ടു: $count share response അറിയില്ല; accepted ആയി കണക്കാക്കിയിട്ടില്ല")
        }
    }

    override fun onShareDropped(count: Int) {
        if (count > 0) {
            MinerState.droppedShares.addAndGet(count)
            MinerState.log("$count share(s) not submitted: pool response queue full")
        }
    }
    override fun onShareResult(accepted: Boolean, reason: String?) {
        MinerState.lastShareResponseAt = System.currentTimeMillis()
        if (accepted) {
            MinerState.accepted.incrementAndGet()
            MinerState.log("share സ്വീകരിച്ചു ✓")
        } else {
            MinerState.rejected.incrementAndGet()
            MinerState.log("share നിരസിച്ചു ✗ ${reason ?: ""}")
        }
    }

    override fun onStatus(msg: String) {
        MinerState.lastPoolEvent = msg
        MinerState.lastPoolEventAt = System.currentTimeMillis()
        MinerState.log(msg)
    }

    private fun sleepMs(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { }
    }

    private fun connectionLoop() {
        val reconnectBackoff = ReconnectBackoff()
        while (running) {
            val c = StratumClient(cfg.host, cfg.port, cfg.address, this)
            client = c
            val attemptStartedAt = System.nanoTime()
            val attemptStartedWall = System.currentTimeMillis()
            MinerState.connectionAttempts.incrementAndGet()
            MinerState.lastConnectAttemptAt = attemptStartedWall
            try {
                c.connectAndRun()
                if (running) MinerState.log("Pool socket അടഞ്ഞു; വീണ്ടും കണക്ട് ചെയ്യും")
            } catch (e: Exception) {
                MinerState.lastDisconnectReason = e.message ?: e.javaClass.simpleName
                if (running) MinerState.log("കണക്ഷൻ പിശക്: ${e.message}")
            } finally {
                if (client === c) client = null
                synchronized(workLock) {
                    current = null
                    jobGeneration.incrementAndGet()
                    // A new Stratum session must not inherit the previous pool's
                    // share target or report its difficulty while awaiting new work.
                    difficultyTargets.reset()
                    shareTarget = difficultyTargets.activeTarget()
                    MinerState.poolDiff = 0.0
                    MinerState.netDiff = 0.0
                    MinerState.connected = false
                    MinerState.activeThreads = 0
                    if (MinerState.lastDisconnectReason.isBlank()) MinerState.lastDisconnectReason = "Pool socket closed"
                }
            }
            if (!running) break

            // A long-lived but unauthenticated/socket-only session is not healthy.
            // Reset backoff only after an authenticated session has also received a job.
            val sessionMs = (System.nanoTime() - attemptStartedAt) / 1_000_000L
            val healthySession =
                MinerState.authorizedAt >= attemptStartedWall &&
                MinerState.lastJobAt >= attemptStartedWall
            val retryDelay = reconnectBackoff.nextDelayAfter(sessionMs, healthySession)
            MinerState.status = "Pool ബന്ധം നഷ്ടപ്പെട്ടു; ${retryDelay / 1000}s കഴിഞ്ഞ് വീണ്ടും കണക്ട് ചെയ്യും"
            sleepMs(retryDelay)
        }
    }

    private fun encodeEn2(counter: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        for (i in 0 until size) {
            val shift = 8 * (size - 1 - i)
            out[i] = if (shift >= 64) 0.toByte() else ((counter ushr shift) and 0xffL).toByte()
        }
        return out
    }

    private fun workerLoop(idx: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE)
        val found = IntArray(8)
        val best = DoubleArray(3)
        // Each CPU worker tunes its own contiguous scan batch using measured native call duration.
        val scanChunks = AdaptiveScanChunkController()
        var counter = idx.toLong()
        var seenExtranonceEpoch = extranonceEpoch.get()
        while (running) {
            val epoch = extranonceEpoch.get()
            if (epoch != seenExtranonceEpoch) {
                counter = idx.toLong()
                seenExtranonceEpoch = epoch
            }
            if (idx >= activeThreads) { sleepMs(500); continue }
            val j = current
            if (j == null) { sleepMs(300); continue }

            val size = en2Size
            val maxCounter = if (size < 8) (1L shl (size * 8)) - 1L else Long.MAX_VALUE
            if (counter < 0L || counter > maxCounter) {
                MinerState.status = "Extranonce2 പരിധി തീർന്നു; pool reconnect ചെയ്യുന്നു"
                if (reconnectingForExhaustion.compareAndSet(false, true)) client?.close()
                sleepMs(500)
                continue
            }
            val generation = jobGeneration.get()
            val en1Snapshot = en1.copyOf()
            val en2 = encodeEn2(counter, size)
            counter = if (counter > Long.MAX_VALUE - cfg.threads) Long.MIN_VALUE else counter + cfg.threads
            val header = j.buildHeader(en1Snapshot, en2)
            val target = shareTarget.copyOf()

            var start = 0L
            while (running && current === j && jobGeneration.get() == generation && idx < activeThreads && start < SPACE) {
                val count = minOf(scanChunks.currentChunkSize().toLong(), SPACE - start).toInt()
                val scanStartedAt = System.nanoTime()
                scanRange(j, generation, target, header, en2, start, count, found, best)
                scanChunks.observeElapsedNanos(System.nanoTime() - scanStartedAt)
                start += count
            }
        }
    }

    /**
     * If the native result buffer overflows, discard the partial result and rescan
     * the range as smaller non-overlapping chunks so every pool-target hit is submitted.
     * Hash accounting includes the extra real work performed by retry scans.
     */
    private fun scanRange(
        j: Job, generation: Long, target: ByteArray, header: ByteArray, en2: ByteArray,
        start: Long, count: Int, found: IntArray, best: DoubleArray
    ) {
        if (!running || current !== j || jobGeneration.get() != generation || count <= 0) return
        val nf = Native.scan(header, start.toInt(), count, target, found, best)
        if (nf < 0 || nf > found.size) {
            MinerState.status = "Native scan error; mining stopped"
            MinerState.log("Native scan returned invalid result: $nf")
            running = false
            MinerState.running = false
            MinerState.activeThreads = 0
            MinerState.pauseReason = "Native scan error"
            client?.close()
            return
        }
        totalHashes.addAndGet(count.toLong())
        if (best[0] > MinerState.bestDiff) MinerState.noteBest(best[0])
        if (best[2] > 0.0) {
            if (count == 1) {
                MinerState.log("Native scan reported impossible overflow for a one-nonce range")
                running = false
                MinerState.running = false
                client?.close()
                return
            }
            MinerState.log("Native result buffer full; splitting range to submit every hit")
            val firstCount = count / 2
            scanRange(j, generation, target, header, en2, start, firstCount, found, best)
            scanRange(j, generation, target, header, en2, start + firstCount, count - firstCount, found, best)
            return
        }
        for (k in 0 until nf) handleShare(j, generation, target, header, found[k], en2)
    }

    private fun handleShare(j: Job, generation: Long, target: ByteArray, header: ByteArray, nonce: Int, en2: ByteArray) {
        if (!running || current !== j || jobGeneration.get() != generation) return
        val h = header.copyOf()
        h[76] = nonce.toByte()
        h[77] = (nonce ushr 8).toByte()
        h[78] = (nonce ushr 16).toByte()
        h[79] = (nonce ushr 24).toByte()

        val digest = Native.sha256dHeader(h)
        val value = BigInteger(1, digest.reversedArray())
        if (value > BigInteger(1, target)) return
        val nonceHex = String.format(java.util.Locale.US, "%08x", nonce)
        val isBlock = value <= Util.nbitsToTarget(j.nbitsHex)
        synchronized(workLock) {
            if (!running || current !== j || jobGeneration.get() != generation) return
            if (isBlock) {
                MinerState.blockCandidateDetected = true
                MinerState.log("Local network-target candidate detected; submitting to pool (not confirmed)")
            }
            client?.submit(j.id, Util.bytesToHex(en2), j.ntimeHex, nonceHex)
        }
    }
    private fun policyLoop() {
        val batteryManager = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryEnergyMonitor = BatteryEnergyMonitor()
        val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val headroomThresholds: Map<Int, Float> = if (Build.VERSION.SDK_INT >= 35 && powerManager != null) {
            runCatching { powerManager.thermalHeadroomThresholds }.getOrDefault(emptyMap())
        } else emptyMap()
        var lastHeadroomSampleAtMillis = 0L
        var latestHeadroomForecast: Double? = null
        var lastHashes = 0L
        var lastTime = System.nanoTime()
        val sustainedPerformance = SustainedPerformanceMonitor()
        var hot = false
        var full = false
        while (running) {
            val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            var pct = MinerState.battery
            var plugged = MinerState.plugged
            var temp = MinerState.tempC
            var batteryAvailable = false
            var tempAvailable = false
            if (bi != null) {
                val level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) { pct = level * 100 / scale; batteryAvailable = true }
                plugged = bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                val rawTemp = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                if (rawTemp > 0) { temp = rawTemp / 10.0; tempAvailable = true }
            }
            MinerState.battery = pct
            MinerState.batteryAvailable = batteryAvailable
            MinerState.plugged = plugged
            MinerState.tempAvailable = tempAvailable
            MinerState.tempC = if (tempAvailable) temp else Double.NaN

            // Read the Android fuel-gauge's signed battery current. Positive is
            // net charging; negative is net discharge. Prefer hardware-averaged
            // current and fall back to instantaneous current only when unavailable.
            fun validBatteryCurrent(value: Long?): Boolean =
                value != null && value != Long.MIN_VALUE && value in -10_000_000L..10_000_000L
            val averageCurrent = runCatching {
                batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            }.getOrNull()
            val instantaneousCurrent = if (validBatteryCurrent(averageCurrent)) null else runCatching {
                batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            }.getOrNull()
            val batteryCurrent = averageCurrent.takeIf { validBatteryCurrent(it) }
                ?: instantaneousCurrent.takeIf { validBatteryCurrent(it) }
            val voltageMilliVolts = bi?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            val energySnapshot = batteryEnergyMonitor.update(
                currentMicroAmps = batteryCurrent,
                voltageMilliVolts = voltageMilliVolts,
                nowNanos = System.nanoTime()
            )
            MinerState.batteryEnergyAvailable = energySnapshot.available
            MinerState.batteryCurrentMicroAmps = energySnapshot.currentMicroAmps ?: Long.MIN_VALUE
            MinerState.batteryVoltageMilliVolts = energySnapshot.voltageMilliVolts ?: 0
            MinerState.batteryNetPowerWatts = energySnapshot.netPowerWatts ?: Double.NaN
            MinerState.batteryDischargedMilliWattHours = energySnapshot.dischargedMilliWattHours
            MinerState.batteryEnergySampleCount = energySnapshot.validSampleCount

            if (tempAvailable && temp >= cfg.maxTemp) hot = true else if (tempAvailable && temp <= cfg.maxTemp - 4.0) hot = false
            // A limit of 100 means "no battery limit"; otherwise pause at the limit and resume 2% below it.
            if (cfg.maxBattery >= 100) full = false
            else if (pct >= cfg.maxBattery) full = true
            else if (pct < cfg.maxBattery - 2) full = false

            var target = cfg.threads
            var reason = ""
            if (cfg.onlyCharging && !plugged) {
                target = 0; reason = "ചാർജിൽ അല്ല"
            } else if (!batteryAvailable) {
                target = 0; reason = "ബാറ്ററി നില ലഭ്യമല്ല; സുരക്ഷയ്ക്കായി mining pause ചെയ്തു"
            } else if (full) {
                target = 0; reason = "ബാറ്ററി പരിധി എത്തി"
            } else if (hot) {
                target = 0; reason = "ഫോൺ ചൂടായി, തണുക്കാൻ കാത്തിരിക്കുന്നു"
            } else if (!tempAvailable) {
                target = minOf(1, cfg.threads); reason = "താപനില ലഭ്യമല്ല; സുരക്ഷയ്ക്കായി ഒരു worker മാത്രം"
            } else if (temp >= cfg.maxTemp - 3.0) {
                target = maxOf(1, cfg.threads / 2); reason = "ചൂട് കൂടുന്നു, ത്രെഡ് കുറച്ചു"
            }
            // Apply Android thermal caps only when another safety rule has not
            // already paused mining. Otherwise a moderate thermal status would
            // overwrite the real pause reason (for example, "not charging")
            // while the effective worker count remains zero.
            val thermalStatus: Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
                runCatching { powerManager.currentThermalStatus }.getOrElse { ThermalWorkerPolicy.UNKNOWN }
            } else null
            MinerState.androidThermalStatus = ThermalWorkerPolicy.label(thermalStatus)

            // Android recommends polling this slow-moving estimate no more than about once per second.
            // Ten-second sampling reduces overhead and uses device-provided thresholds only.
            val elapsedNow = SystemClock.elapsedRealtime()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && powerManager != null &&
                (lastHeadroomSampleAtMillis == 0L || elapsedNow - lastHeadroomSampleAtMillis >= 10_000L)
            ) {
                latestHeadroomForecast = runCatching {
                    powerManager.getThermalHeadroom(10).toDouble()
                }.getOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
                lastHeadroomSampleAtMillis = elapsedNow
            }
            MinerState.thermalHeadroomForecast = latestHeadroomForecast ?: Double.NaN
            MinerState.thermalHeadroomThresholdsAvailable = headroomThresholds.isNotEmpty()
            if (target > 0 && thermalStatus != null) {
                val thermalDecision = ThermalWorkerPolicy.evaluate(
                    configuredWorkers = cfg.threads,
                    requestedWorkers = target,
                    thermalStatus = thermalStatus
                )
                target = thermalDecision.workerCount
                if (thermalDecision.reason.isNotBlank()) reason = thermalDecision.reason
            }
            if (target > 0 && headroomThresholds.isNotEmpty()) {
                val headroomDecision = ThermalHeadroomPolicy.evaluate(
                    configuredWorkers = cfg.threads,
                    requestedWorkers = target,
                    forecastHeadroom = latestHeadroomForecast,
                    manufacturerThresholds = headroomThresholds
                )
                target = headroomDecision.workerCount
                if (headroomDecision.reason.isNotBlank()) reason = headroomDecision.reason
            }
            // Do not ramp while waiting for pool authorization or a mining job.
            // Start conservatively only when real work is ready.
            val hasReadyWork = MinerState.connected && current != null
            val safetyWorkerLimit = if (hasReadyWork) target else 0

            // Thermal/battery reductions apply immediately. Worker increases are
            // limited to one every 2 seconds while an authorized job is active.
            target = adaptiveWorkerController.selectWorkerCount(
                safetyWorkerLimit = safetyWorkerLimit,
                currentWorkerCount = activeThreads,
                nowMillis = SystemClock.elapsedRealtime()
            )
            if (target > 0 && target < safetyWorkerLimit && reason.isBlank()) {
                reason = "Adaptive worker ramp: $target/$safetyWorkerLimit"
            }

            activeThreads = target
            MinerState.activeThreads = if (target > 0 && hasReadyWork) target else 0
            MinerState.pauseReason = reason
            // The UI reports eligible workers only when an authorized pool job is actually active.
            if (target == 0 && MinerState.connected) MinerState.status = "നിർത്തിവെച്ചു"
            else if (MinerState.connected && current == null) {
                MinerState.status = "ജോബിനായി കാത്തിരിക്കുന്നു"
            } else if (target > 0 && current != null && MinerState.connected) {
                MinerState.status = "മൈനിംഗ് നടക്കുന്നു"
            }

            sleepMs(2000)

            val now = System.nanoTime()
            val total = totalHashes.get()
            val elapsed = (now - lastTime) / 1e9
            val rate = if (elapsed > 0.0) (total - lastHashes) / elapsed else 0.0
            MinerState.hashrate = if (rate <= 0.0) 0.0 else if (MinerState.hashrate == 0.0) rate else 0.6 * MinerState.hashrate + 0.4 * rate
            MinerState.totalHashes = total
            val sustained = sustainedPerformance.record(total, now)
            MinerState.localHashrate5m = sustained.fiveMinuteRateHps
            MinerState.localHashrateWindowSeconds = sustained.fiveMinuteWindowSeconds
            MinerState.localHashrate30m = sustained.thirtyMinuteRateHps
            MinerState.localHashrate30mWindowSeconds = sustained.thirtyMinuteWindowSeconds
            lastHashes = total
            lastTime = now
        }
    }
}
