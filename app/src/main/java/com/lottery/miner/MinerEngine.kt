package com.lottery.miner

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import java.math.BigInteger
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
    private val threads = ArrayList<Thread>()

    fun start() {
        if (running) return
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

    fun stop() {
        running = false
        client?.close()
        for (t in threads) t.interrupt()
        threads.clear()
        MinerState.running = false
        MinerState.connected = false
        MinerState.hashrate = 0.0
        MinerState.activeThreads = 0
        if (MinerState.status != "പിശക്: self-test") MinerState.status = "നിർത്തി"
    }

    override fun onSubscribed(extranonce1: ByteArray, extranonce2Size: Int) {
        en1 = extranonce1
        en2Size = extranonce2Size
        MinerState.connected = true
        MinerState.log("subscribe ശരി (extranonce2 = $extranonce2Size ബൈറ്റ്)")
    }

    override fun onDifficulty(diff: Double) {
        shareTarget = Util.difficultyToTarget(diff)
        MinerState.poolDiff = diff
        MinerState.log("പൂൾ difficulty = $diff")
    }

    override fun onJob(job: Job) {
        MinerState.netDiff = Util.networkDifficulty(job.nbitsHex)
        MinerState.status = "മൈനിംഗ് നടക്കുന്നു"
        if (job.clean) MinerState.log("പുതിയ ബ്ലോക്ക് ജോബ് (job ${job.id})")
        current = job
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
        val best = DoubleArray(2)
        var counter = idx.toLong()
        while (running) {
            if (idx >= activeThreads) { sleepMs(500); continue }
            val j = current
            if (j == null) { sleepMs(300); continue }

            val en2 = encodeEn2(counter, en2Size)
            counter += cfg.threads
            val header = j.buildHeader(en1, en2)
            val target = shareTarget

            var start = 0L
            while (running && current === j && idx < activeThreads && start < SPACE) {
                val count = minOf(CHUNK, SPACE - start).toInt()
                val nf = Native.scan(header, start.toInt(), count, target, found, best)
                totalHashes.addAndGet(count.toLong())
                if (best[0] > MinerState.bestDiff) MinerState.noteBest(best[0])
                for (k in 0 until nf) handleShare(j, header, found[k], en2)
                start += count
            }
        }
    }

    private fun handleShare(j: Job, header: ByteArray, nonce: Int, en2: ByteArray) {
        val h = header.copyOf()
        h[76] = nonce.toByte()
        h[77] = (nonce ushr 8).toByte()
        h[78] = (nonce ushr 16).toByte()
        h[79] = (nonce ushr 24).toByte()

        val digest = Native.sha256dHeader(h)
        val value = BigInteger(1, digest.reversedArray())
        if (value > BigInteger(1, shareTarget)) return
        val nonceHex = String.format("%08x", nonce)
        val isBlock = value <= Util.nbitsToTarget(j.nbitsHex)
        if (isBlock) {
            MinerState.blockFound = true
            MinerState.log("🎉🎉 ബ്ലോക്ക് സോൾവ് ചെയ്തു! nonce $nonceHex, ബ്ലോക്ക് പൂളിലേക്ക് അയച്ചു")
        }
        client?.submit(j.id, Util.bytesToHex(en2), j.ntimeHex, nonceHex)
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
            if (bi != null) {
                val level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) pct = level * 100 / scale
                plugged = bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                temp = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
            }
            MinerState.battery = pct
            MinerState.plugged = plugged
            MinerState.tempC = temp

            if (temp >= cfg.maxTemp) hot = true else if (temp <= cfg.maxTemp - 4.0) hot = false
            if (pct >= cfg.maxBattery) full = true else if (pct < cfg.maxBattery - 2) full = false

            var target = cfg.threads
            var reason = ""
            if (cfg.onlyCharging && !plugged) {
                target = 0; reason = "ചാർജിൽ അല്ല"
            } else if (full) {
                target = 0; reason = "ബാറ്ററി പരിധി എത്തി"
            } else if (hot) {
                target = 0; reason = "ഫോൺ ചൂടായി, തണുക്കാൻ കാത്തിരിക്കുന്നു"
            } else if (temp >= cfg.maxTemp - 3.0) {
                target = maxOf(1, cfg.threads / 2); reason = "ചൂട് കൂടുന്നു, ത്രെഡ് കുറച്ചു"
            }
            activeThreads = target
            MinerState.activeThreads = target
            MinerState.pauseReason = reason

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
