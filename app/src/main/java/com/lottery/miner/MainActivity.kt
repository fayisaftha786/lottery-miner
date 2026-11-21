package com.lottery.miner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val DEFAULT_POOL_HOST = "stratum.ckpool.org"
        const val DEFAULT_POOL_PORT = 3333
        private const val LEGACY_DEFAULT_POOL_HOST = "solo.ckpool.org"
        private const val EXTERNAL_REFRESH_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var etAddress: EditText
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etThreads: EditText
    private lateinit var etTemp: EditText
    private lateinit var etBatt: EditText
    private lateinit var cbCharging: CheckBox
    private lateinit var btn: Button
    private lateinit var btnPoolStats: Button
    private lateinit var tvStats: TextView
    private lateinit var tvExternalStats: TextView
    private lateinit var tvLog: TextView
    @Volatile private var externalPollInFlight = false
    private var lastExternalPollAt = 0L
    private var externalSnapshot: ExternalMiningSnapshot? = null
    private var externalSnapshotAddress: String? = null
    private var externalError: String? = null

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        etAddress = findViewById(R.id.etAddress)
        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etThreads = findViewById(R.id.etThreads)
        etTemp = findViewById(R.id.etTemp)
        etBatt = findViewById(R.id.etBatt)
        cbCharging = findViewById(R.id.cbCharging)
        btn = findViewById(R.id.btnToggle)
        btnPoolStats = findViewById(R.id.btnPoolStats)
        tvStats = findViewById(R.id.tvStats)
        tvExternalStats = findViewById(R.id.tvExternalStats)
        tvLog = findViewById(R.id.tvLog)

        val cores = Runtime.getRuntime().availableProcessors()
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        etAddress.setText(p.getString("address", ""))
        val savedHost = p.getString("host", null)
        val configuredHost = if (savedHost.isNullOrBlank() ||
            savedHost.equals(LEGACY_DEFAULT_POOL_HOST, ignoreCase = true)
        ) DEFAULT_POOL_HOST else savedHost
        etHost.setText(configuredHost)
        etPort.setText(p.getInt("port", DEFAULT_POOL_PORT).toString())
        if (configuredHost != savedHost) p.edit().putString("host", configuredHost).apply()
        etThreads.setText(p.getInt("threads", maxOf(1, cores / 2)).toString())
        etTemp.setText(p.getFloat("maxTemp", 40f).toString())
        etBatt.setText(p.getInt("maxBatt", 100).toString())
        cbCharging.isChecked = p.getBoolean("onlyCharging", true)

        btn.setOnClickListener { if (MinerState.running) stopMining() else startMining() }
        btnPoolStats.setOnClickListener { openPoolStatistics() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private fun openPoolStatistics() {
        val address = etAddress.text.toString().trim()
        if (!Util.isValidBitcoinAddress(address)) {
            Toast.makeText(this, "ആദ്യം ശരിയായ Bitcoin mainnet address നൽകുക.", Toast.LENGTH_LONG).show()
            return
        }
        val statsUri = Uri.parse("https://stats.ckpool.org/users/").buildUpon()
            .appendPath(address).build()
        startActivity(Intent(Intent.ACTION_VIEW, statsUri))
    }

    private fun pollExternalStatsIfNeeded() {
        val address = etAddress.text.toString().trim()
        if (!Util.isValidBitcoinAddress(address)) {
            externalSnapshot = null
            externalSnapshotAddress = null
            externalError = null
            tvExternalStats.text = "FREE EXTERNAL MINING (OCEAN BTC)\nWallet address നൽകുക.\nഇത് സൗജന്യ hashrate നൽകുന്നില്ല; OCEAN-ൽ ഇതിനകം പ്രവർത്തിക്കുന്ന worker-കളുടെ കണക്കാണ്."
            return
        }
        val now = System.currentTimeMillis()
        if (externalPollInFlight || now - lastExternalPollAt < EXTERNAL_REFRESH_MS) return
        val requestedAddress = address.substringBefore('.')
        externalPollInFlight = true
        lastExternalPollAt = now
        Thread {
            try {
                val result = OceanStats.fetch(requestedAddress)
                runOnUiThread {
                    val currentAddress = etAddress.text.toString().trim().substringBefore('.')
                    externalPollInFlight = false
                    if (Util.isValidBitcoinAddress(currentAddress) && currentAddress == requestedAddress) {
                        externalSnapshot = result
                        externalSnapshotAddress = requestedAddress
                        externalError = null
                    } else {
                        // Never display an old wallet's asynchronous response for a newly edited address.
                        externalSnapshot = null
                        externalSnapshotAddress = null
                        externalError = null
                        lastExternalPollAt = 0L
                    }
                    renderExternalStats()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    val currentAddress = etAddress.text.toString().trim().substringBefore('.')
                    externalPollInFlight = false
                    if (currentAddress == requestedAddress) {
                        externalError = error.message ?: "Connection unavailable"
                    } else {
                        externalSnapshot = null
                        externalSnapshotAddress = null
                        externalError = null
                        lastExternalPollAt = 0L
                    }
                    renderExternalStats()
                }
            }
        }.start()
    }

    private fun renderExternalStats() {
        val currentAddress = etAddress.text.toString().trim().substringBefore('.')
        val snapshot = externalSnapshot.takeIf { externalSnapshotAddress == currentAddress }
        val host = etHost.text.toString().trim().lowercase(Locale.US)
        val sb = StringBuilder()
        sb.append("EXTERNAL WORKER MONITOR · OCEAN BTC\\n")
        sb.append("Auto refresh: 30 sec while app is open\\n")
        if (snapshot != null) {
            val ageMs = System.currentTimeMillis() - snapshot.fetchedAtMillis
            val snapshotFresh = externalError == null && ageMs in 0..120_000L
            sb.append("External worker hashrate: ").append(Util.fmtRate(snapshot.totalHashrate)).append('\\n')
            sb.append("Workers detected: ").append(snapshot.workers.size).append('\\n')
            snapshot.workers.forEach { worker ->
                sb.append("• ").append(worker.name)
                    .append(": ").append(Util.fmtRate(worker.hashrate))
                    .append(" (5m ").append(Util.fmtRate(worker.hashrate5m)).append(")").append('\\n')
            }
            sb.append("Updated: ").append(java.text.SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(java.util.Date(snapshot.fetchedAtMillis))).append('\\n')
            if (snapshot.workers.isEmpty()) {
                if (snapshot.notice != null) {
                    sb.append("OCEAN API: ഓൺലൈൻ · ഈ വിലാസത്തിൽ active OCEAN worker ഇല്ല.\\n")
                    if (!host.contains("ocean.xyz")) {
                        sb.append("ഫോൺ CKPool-ലാണ് mining ചെയ്യുന്നത്; CKPool worker-കൾ OCEAN പട്ടികയിൽ വരില്ല.\\n")
                    } else {
                        sb.append("OCEAN-ൽ accepted worker share ലഭിക്കുന്നതുവരെ external worker കണക്കുകൾ ശൂന്യമായിരിക്കും.\\n")
                    }
                } else {
                    sb.append("ഈ wallet-ൽ OCEAN worker ഒന്നും ഇതുവരെ കണ്ടെത്തിയിട്ടില്ല.\\n")
                }
            }
            when {
                externalError != null -> {
                    sb.append("API refresh failed: ").append(externalError).append('\\n')
                    sb.append("Last successful snapshot is shown for reference only; excluded from combined total.\\n")
                }
                !snapshotFresh -> {
                    sb.append("Snapshot stale; excluded from combined total until a fresh response arrives.\\n")
                }
                host.contains("ocean.xyz") -> {
                    sb.append("Combined total: സുരക്ഷിതമായി കണക്കാക്കാനാവില്ല — phone worker OCEAN-ൽ ഉൾപ്പെട്ടിരിക്കാം.\\n")
                }
                else -> {
                    sb.append("Combined total (phone + OCEAN external workers): ")
                        .append(Util.fmtRate(MinerState.hashrate + snapshot.totalHashrate)).append('\\n')
                }
            }
        } else {
            sb.append("External hashrate: --\\n")
            sb.append(if (externalError == null) "API പരിശോധിക്കുന്നു…" else "API unavailable: $externalError").append('\\n')
            sb.append("External workers ലഭിക്കുന്നതുവരെ total-ൽ ചേർക്കില്ല.\\n")
        }
        sb.append("കുറിപ്പ്: OCEAN API-യിൽ കണ്ടെത്തിയ യഥാർത്ഥ worker കണക്കുകൾ മാത്രം. API monitoring സൗജന്യമാണ്; mining power ലഭ്യമാക്കുന്നില്ല.")
        tvExternalStats.text = sb.toString()
    }

    private fun formatExpectedShareInterval(seconds: Double): String {
        if (!seconds.isFinite() || seconds <= 0.0) return "N/A"
        return when {
            seconds >= 86_400.0 -> String.format(Locale.US, "~%.0f ദിവസം", seconds / 86_400.0)
            seconds >= 3_600.0 -> String.format(Locale.US, "~%.1f മണിക്കൂർ", seconds / 3_600.0)
            seconds >= 60.0 -> String.format(Locale.US, "~%.0f മിനിറ്റ്", seconds / 60.0)
            else -> String.format(Locale.US, "~%.0f സെക്കൻഡ്", seconds)
        }
    }

    private fun startMining() {
        val addr = etAddress.text.toString().trim()
        if (!Util.isValidBitcoinAddress(addr)) {
            Toast.makeText(this, "ശരിയായ Bitcoin mainnet വിലാസം നൽകുക; worker വേണമെങ്കിൽ address.worker നൽകാം.", Toast.LENGTH_LONG).show()
            return
        }
        val cores = Runtime.getRuntime().availableProcessors()
        val host = etHost.text.toString().trim().ifEmpty { DEFAULT_POOL_HOST }
        if (host.any { it.isWhitespace() } || host.contains('/') || host.length > 253) {
            Toast.makeText(this, "ശരിയായ pool hostname നൽകുക.", Toast.LENGTH_LONG).show()
            return
        }
        val port = etPort.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            Toast.makeText(this, "Pool port 1 മുതൽ 65535 വരെയായിരിക്കണം.", Toast.LENGTH_LONG).show()
            return
        }
        val threads = etThreads.text.toString().trim().toIntOrNull()
        if (threads == null || threads !in 1..cores) {
            Toast.makeText(this, "Worker thread എണ്ണം 1 മുതൽ $cores വരെ നൽകുക.", Toast.LENGTH_LONG).show()
            return
        }
        val maxTemp = etTemp.text.toString().trim().toDoubleOrNull()
        if (maxTemp == null || !maxTemp.isFinite() || maxTemp !in 30.0..45.0) {
            Toast.makeText(this, "സുരക്ഷയ്ക്കായി പരമാവധി താപനില 30–45°C ഇടയിൽ നൽകുക.", Toast.LENGTH_LONG).show()
            return
        }
        val maxBatt = etBatt.text.toString().trim().toIntOrNull()
        if (maxBatt == null || maxBatt !in 10..100) {
            Toast.makeText(this, "ബാറ്ററി പരിധി 10–100% ഇടയിൽ നൽകുക.", Toast.LENGTH_LONG).show()
            return
        }
        val onlyCharging = cbCharging.isChecked
        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putString("address", addr).putString("host", host).putInt("port", port)
            .putInt("threads", threads).putFloat("maxTemp", maxTemp.toFloat())
            .putInt("maxBatt", maxBatt).putBoolean("onlyCharging", onlyCharging).apply()

        val i = Intent(this, MinerService::class.java)
            .setAction(MinerService.ACTION_START)
            .putExtra(MinerService.EXTRA_ADDRESS, addr)
            .putExtra(MinerService.EXTRA_HOST, host)
            .putExtra(MinerService.EXTRA_PORT, port)
            .putExtra(MinerService.EXTRA_THREADS, threads)
            .putExtra(MinerService.EXTRA_ONLY_CHARGING, onlyCharging)
            .putExtra(MinerService.EXTRA_MAX_TEMP, maxTemp)
            .putExtra(MinerService.EXTRA_MAX_BATT, maxBatt)
        ContextCompat.startForegroundService(this, i)
    }

    private fun stopMining() {
        startService(Intent(this, MinerService::class.java).setAction(MinerService.ACTION_STOP))
    }

    private fun refresh() {
        pollExternalStatsIfNeeded()
        val s = MinerState
        btn.text = if (s.running) "സ്റ്റോപ്പ്" else "സ്റ്റാർട്ട്"
        val sb = StringBuilder()
        if (s.blockCandidateDetected) sb.append("പ്രാദേശിക block candidate കണ്ടെത്തി — pool സ്ഥിരീകരിച്ചിട്ടില്ല\n")
        sb.append("PHONE MINING: ").append(Util.fmtRate(s.hashrate))
            .append(if (s.hwSha) "  [ARM SHA2 ✓]" else "").append('\n')
        sb.append("സ്ഥിതി     : ").append(s.status).append('\n')
        sb.append("ത്രെഡ്     : ").append(s.activeThreads).append('/').append(s.totalThreads).append('\n')
        sb.append("ആകെ ഹാഷ്  : ").append(String.format(Locale.US, "%.3e", s.totalHashes.toDouble())).append('\n')
        sb.append("Pool shares: ✓").append(s.accepted.get()).append("  ✗").append(s.rejected.get())
            .append("  (പൂൾ diff ").append(if (s.poolDiff > 0.0) String.format(Locale.US, "%.4g", s.poolDiff) else "N/A").append(")\n")
        if (s.hashrate > 0.0 && s.poolDiff > 0.0) {
            val expectedSeconds = s.poolDiff * 4_294_967_296.0 / s.hashrate
            sb.append("ഏകദേശ share ഇടവേള: ").append(formatExpectedShareInterval(expectedSeconds)).append('\\n')
            sb.append("ഇത് feedback share-ന്റെ ശരാശരി മാത്രം; block/payout ഉറപ്പല്ല.\\n")
        }
        sb.append("അറിയാത്ത share response: ").append(s.unknownShares.get()).append("\n")
        sb.append("Pool-ലേക്ക് അയക്കാത്തത്: ").append(s.droppedShares.get()).append("\n")
        sb.append("Best local hash diff: ").append(if (s.totalHashes > 0 && s.bestDiff > 0.0) String.format(Locale.US, "%.4g", s.bestDiff) else "N/A").append('\n')
        sb.append("Network diff: ").append(if (s.netDiff > 0.0) String.format(Locale.US, "%.4e", s.netDiff) else "N/A").append('\n')
        sb.append("ബാറ്ററി താപനില: ").append(if (s.tempAvailable) String.format(Locale.US, "%.1f°C", s.tempC) else "N/A")
            .append("   ബാറ്ററി ").append(if (s.batteryAvailable) "${s.battery}%" else "N/A")
            .append(if (s.batteryAvailable && s.plugged) " ⚡" else "").append('\n')
        if (s.pauseReason.isNotEmpty()) sb.append("താൽക്കാലികം: ").append(s.pauseReason).append('\n')
        sb.append("കുറിപ്പ്: accepted share എന്നത് block കണ്ടെത്തലോ payout-ഓ അല്ല.\n")
        sb.append("\nCLOUD WORKERS\n")
        sb.append(CloudProviderRegistry.summary()).append('\n')
        tvStats.text = sb.toString()
        renderExternalStats()
        tvLog.text = s.logText()
    }
}
