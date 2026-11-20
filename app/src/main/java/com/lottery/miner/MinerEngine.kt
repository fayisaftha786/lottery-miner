package com.lottery.miner

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
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
        private const val CHUNK = 1L shl 18
    }

    @Volatile private var running = false
    @Volatile private var client: StratumClient? = null
    @Volatile private var current: Job? = null
    @Volatile private var en1 = ByteArray(0)
    @Volatile private var en2Size = 4
    @Volatile private var shareTarget: ByteArray = Util.difficultyToTarget(1.0)
    @Volatile private var activeThreads = 0
    private val totalHashes = AtomicLong(0)
    private val jobGeneration = AtomicLong(0)
    private val extranonceEpoch = AtomicLong(0)
    private val reconnectingForExhaustion = AtomicBoolean(false)
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
        jobGeneration.incrementAndGet()
        client?.close()
        val stopping = threads.toList()
        stopping.forEach { it.interrupt() }
        for (t in stopping) {
            if (t !== Thread.currentThread()) {
                try { t.join(2000) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
        }
        threads.removeAll { !it.isAlive }
        MinerState.running = false
        MinerState.connected = false
        MinerState.hashrate = 0.0
        MinerState.activeThreads = 0
        if (MinerState.status != "പിശക്: self-test") MinerState.status = "നിർത്തി"
    }

    override fun onSubscribed(extranonce1: ByteArray, extranonce2Size: Int) {
        if (extranonce2Size !in 1..16 || extranonce1.size > 32) {
            MinerState.status = "Pool invalid extranonce അയച്ചു"
            client?.close()
            return
        }
        current = null
        jobGeneration.incrementAndGet()
        en1 = extranonce1.copyOf()
        en2Size = extranonce2Size
        extranonceEpoch.incrementAndGet()
        reconnectingForExhaustion.set(false)
        MinerState.log("subscribe ശരി (extranonce2 = $extranonce2Size ബൈറ്റ്)")
    }

    override fun onAuthorized() {
        MinerState.connected = true
        MinerState.status = "Pool അംഗീകരിച്ചു; ജോബിനായി കാത്തിരിക്കുന്നു"
        MinerState.log("Pool authorization ശരി")
    }

    override fun onDifficulty(diff: Double) {
        shareTarget = Util.difficultyToTarget(diff)
        MinerState.poolDiff = diff
        MinerState.log("പൂൾ difficulty = $diff")
    }

    override fun onJob(job: Job) {
        jobGeneration.incrementAndGet()
        MinerState.netDiff = Util.networkDifficulty(job.nbitsHex)
        MinerState.status = if (activeThreads > 0) "മൈനിംഗ് നടക്കുന്നു" else "നിർത്തിവെച്ചു"
        if (job.clean) MinerState.log("പുതിയ ബ്ലോക്ക് ജോബ് (job ${job.id})")
        current = job
    }

    override fun onShareUnknown(count: Int) {
        if (count > 0) {
            MinerState.unknownShares.addAndGet(count)
            MinerState.log("കണക്ഷൻ നഷ്ടപ്പെട്ടു: $count share response അറിയില്ല; accepted ആയി കണക്കാക്കിയിട്ടില്ല")
        }
    }

    override fun onShareResult(accepted: Boolean, reason: String?) {
        if (accepted) {
            MinerState.accepted.incrementAndGet()
            MinerState.log("share സ്വീകരിച്ചു ✓")
        } else {
            MinerState.rejected.incrementAndGet()
            MinerState.log("share നിരസിച്ചു ✗ ${reason ?: ""}")
        }
    }

    override fun onStatus(msg: String) {
        MinerState.log(msg)
    }

    private fun sleepMs(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { }
    }

    private fun connectionLoop() {
        var retryDelay = 5000L
        while (running) {
            val c = StratumClient(cfg.host, cfg.port, cfg.address, this)
            client = c
            try {
                c.connectAndRun()
                retryDelay = 5000L
            } catch (e: Exception) {
                MinerState.log("കണക്ഷൻ പിശക്: ${e.message}")
            }
            current = null
            MinerState.connected = false
            if (!running) break
            MinerState.status = "വീണ്ടും കണക്ട് ചെയ്യുന്നു…"
            sleepMs(retryDelay)
            retryDelay = minOf(30000L, retryDelay * 2)
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
                val count = minOf(CHUNK, SPACE - start).toInt()
                val nf = Native.scan(header, start.toInt(), count, target, found, best)
                if (nf < 0 || nf > found.size) {
                    MinerState.status = "Native scan error; mining stopped"
                    MinerState.log("Native scan returned invalid result: $nf")
                    running = false
                    client?.close()
                    break
                }
                totalHashes.addAndGet(count.toLong())
                if (best[0] > MinerState.bestDiff) MinerState.noteBest(best[0])
                if (best[2] > 0.0) MinerState.log("Native result buffer overflow: ${best[2].toLong()} additional pool-target hits")
                for (k in 0 until nf) handleShare(j, generation, target, header, found[k], en2)
                start += count
            }
        }
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
        if (isBlock) {
            MinerState.blockCandidateDetected = true
            MinerState.log("Local network-target candidate detected; submitting to pool (not confirmed)")
        }
        if (running && current === j && jobGeneration.get() == generation) {
            client?.submit(j.id, Util.bytesToHex(en2), j.ntimeHex, nonceHex)
        }
    }

    private fun policyLoop() {
        var lastHashes = 0L
        var lastTime = System.nanoTime()
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val thermal = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
                if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) {
                    target = 0; reason = "Android thermal protection: high temperature"
                } else if (thermal >= PowerManager.THERMAL_STATUS_MODERATE) {
                    target = minOf(target, maxOf(1, cfg.threads / 2)); reason = "Android thermal status moderate; worker reduced"
                }
            }
            activeThreads = target
            MinerState.activeThreads = target
            MinerState.pauseReason = reason
            // The status line must tell the truth: never say "mining" while no thread is hashing.
            if (target == 0 && MinerState.connected) MinerState.status = "നിർത്തിവെച്ചു"
            else if (target > 0 && MinerState.status == "നിർത്തിവെച്ചു") {
                MinerState.status = if (current != null) "മൈനിംഗ് നടക്കുന്നു" else "ജോബിനായി കാത്തിരിക്കുന്നു"
            }

            sleepMs(2000)

            val now = System.nanoTime()
            val total = totalHashes.get()
            val elapsed = (now - lastTime) / 1e9
            val rate = if (elapsed > 0.0) (total - lastHashes) / elapsed else 0.0
            MinerState.hashrate = if (MinerState.hashrate == 0.0) rate else 0.6 * MinerState.hashrate + 0.4 * rate
            MinerState.totalHashes = total
            lastHashes = total
            lastTime = now
        }
    }
}
