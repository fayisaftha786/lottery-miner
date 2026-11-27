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
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val DEFAULT_GATEWAY_URL = ""
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var etAddress: EditText
    private lateinit var etHost: EditText
    private lateinit var etApiToken: EditText
    private lateinit var etThreads: EditText
    private lateinit var etTemp: EditText
    private lateinit var etBatt: EditText
    private lateinit var cbCharging: CheckBox
    private lateinit var btn: Button
    private lateinit var btnExportDiagnostics: Button
    private lateinit var btnBenchmark: Button
    private lateinit var tvBenchmark: TextView
    private lateinit var etMiningPower: EditText
    private lateinit var etElectricityTariff: EditText
    private lateinit var btnRefreshProfitability: Button
    private lateinit var tvProfitability: TextView
    @Volatile private var benchmarkRunning = false
    @Volatile private var profitabilityFetchInFlight = false
    private var lastBenchmarkHps = 0.0
    private lateinit var tvStats: TextView
    private lateinit var tvExternalStats: TextView
    private lateinit var tvLog: TextView

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        configureEdgeToEdgeInsets()
        etAddress = findViewById(R.id.etAddress)
        etHost = findViewById(R.id.etHost)
        etApiToken = findViewById(R.id.etApiToken)
        etThreads = findViewById(R.id.etThreads)
        etTemp = findViewById(R.id.etTemp)
        etBatt = findViewById(R.id.etBatt)
        cbCharging = findViewById(R.id.cbCharging)
        btn = findViewById(R.id.btnToggle)
        btnExportDiagnostics = findViewById(R.id.btnExportDiagnostics)
        btnBenchmark = findViewById(R.id.btnBenchmark)
        tvBenchmark = findViewById(R.id.tvBenchmark)
        etMiningPower = findViewById(R.id.etMiningPower)
        etElectricityTariff = findViewById(R.id.etElectricityTariff)
        btnRefreshProfitability = findViewById(R.id.btnRefreshProfitability)
        tvProfitability = findViewById(R.id.tvProfitability)
        tvStats = findViewById(R.id.tvStats)
        tvExternalStats = findViewById(R.id.tvExternalStats)
        tvLog = findViewById(R.id.tvLog)

        val cores = Runtime.getRuntime().availableProcessors()
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        etAddress.setText(p.getString("address", null)?.takeIf { it.isNotBlank() } ?: MinerDefaults.DEFAULT_PAYOUT_ADDRESS)
        etHost.setText(p.getString("gatewayUrl", DEFAULT_GATEWAY_URL))
        etApiToken.setText(SecretStore.readToken(this))
        etThreads.setText(p.getInt("threads", maxOf(1, cores)).toString())
        etTemp.setText(p.getFloat("maxTemp", 40f).toString())
        etBatt.setText(p.getInt("maxBatt", 100).toString())
        cbCharging.isChecked = p.getBoolean("onlyCharging", true)
        lastBenchmarkHps = p.getString("lastBenchmarkHps", "0.0")?.toDoubleOrNull() ?: 0.0
        etMiningPower.setText(p.getString("miningPowerWatts", ""))
        etElectricityTariff.setText(p.getString("electricityTariff", ""))
        tvBenchmark.text = "PHOENIX DIRECT SOLO\\nNative SHA-256d · Bitcoin Core getblocktemplate · full block submitblock"

        btn.setOnClickListener { if (MinerState.running) stopMining() else startMining() }
        btnExportDiagnostics.setOnClickListener { exportVerificationReport() }
        btnBenchmark.setOnClickListener { runSafeBenchmark() }
        btnRefreshProfitability.setOnClickListener { refreshBitcoinProfitability() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    /**
     * API 35+ enforces edge-to-edge for modern targets. Apply system-bar and
     * display-cutout insets to the scrolling root so controls remain reachable.
     */
    private fun configureEdgeToEdgeInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<View>(R.id.rootScroll)
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val safeInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                initialLeft + safeInsets.left,
                initialTop + safeInsets.top,
                initialRight + safeInsets.right,
                initialBottom + safeInsets.bottom
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }



    private fun runSafeBenchmark() {
        if (MinerState.running || benchmarkRunning || Sha256dBenchmark.isRunning()) {
            Toast.makeText(this, "Benchmark നടത്താൻ mining നിർത്തിയിരിക്കണം.", Toast.LENGTH_LONG).show()
            return
        }
        benchmarkRunning = true
        btnBenchmark.isEnabled = false
        btn.isEnabled = false
        tvBenchmark.text = "ADAPTIVE MINING LAB\nSHA-256d benchmark നടക്കുന്നു…\nPool connection ഇല്ല; share submit ചെയ്യില്ല."
        Thread {
            try {
                val result = Sha256dBenchmark.run(5_000L)
                runOnUiThread {
                    lastBenchmarkHps = result.hashesPerSecond
                    getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                        .putString("lastBenchmarkHps", result.hashesPerSecond.toString()).apply()
                    tvBenchmark.text = buildString {
                        appendLine("ADAPTIVE MINING LAB · LOCAL MEASUREMENT")
                        appendLine("Algorithm: Bitcoin SHA-256d")
                        appendLine("Measured: ${Util.fmtRate(result.hashesPerSecond)}")
                        appendLine("Hashes: ${result.hashes}")
                        appendLine("Duration: ${result.elapsedMillis} ms")
                        appendLine("Hardware SHA-256: ${if (result.hardwareSha) "ON" else "OFF"}")
                        appendLine("Stored as the latest local benchmark for estimates.")
                        appendLine("Other algorithm miners are not installed yet.")
                        appendLine("No pool shares or earnings are generated by this test.")
                    }
                    benchmarkRunning = false
                    btnBenchmark.isEnabled = true
                    btn.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvBenchmark.text = "Benchmark failed: ${e.message ?: e.javaClass.simpleName}"
                    benchmarkRunning = false
                    btnBenchmark.isEnabled = true
                    btn.isEnabled = true
                }
            }
        }.start()
    }

    private fun refreshBitcoinProfitability() {
        if (profitabilityFetchInFlight) return
        // Direct-node solo uses the full Bitcoin block reward model with no pool commission.
        val liveRate = if (MinerState.running) {
            MinerState.hashrate.takeIf { it.isFinite() && it > 0.0 }
        } else {
            null
        }
        if (MinerState.running && liveRate == null) {
            tvProfitability.text = "Mining ഇപ്പോൾ 0 H/s ആണ്. Worker വീണ്ടും പ്രവർത്തിക്കുന്നതായി ഉറപ്പാക്കിയ ശേഷം estimate പുതുക്കുക."
            return
        }
        val rate = liveRate ?: lastBenchmarkHps
        if (!rate.isFinite() || rate <= 0.0) {
            tvProfitability.text = "ആദ്യം mining തുടങ്ങുകയോ Mining നിർത്തിയ ശേഷം safe SHA-256d benchmark നടത്തുകയോ ചെയ്യുക."
            return
        }
        val power = etMiningPower.text.toString().trim().toDoubleOrNull()
        val tariff = etElectricityTariff.text.toString().trim().toDoubleOrNull()
        val fee = 0.0
        if (power == null || !power.isFinite() || power !in 0.1..200.0) {
            tvProfitability.text = "USB power meter ഉപയോഗിച്ച് mining മാത്രം ഉപയോഗിക്കുന്ന watts നൽകുക (0.1–200 W)."
            return
        }
        if (tariff == null || !tariff.isFinite() || tariff !in 0.0..1000.0) {
            tvProfitability.text = "നിങ്ങളുടെ വൈദ്യുതി നിരക്ക് ₹/kWh ആയി നൽകുക (0–1000)."
            return
        }
        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putString("miningPowerWatts", power.toString())
            .putString("electricityTariff", tariff.toString())
            .apply()

        profitabilityFetchInFlight = true
        btnRefreshProfitability.isEnabled = false
        tvProfitability.text = "Live Bitcoin difficulty, block subsidy, BTC/INR data പരിശോധിക്കുന്നു…"
        Thread {
            try {
                val snapshot = BitcoinMarketData.fetchSnapshot()
                val ageSeconds = ((System.currentTimeMillis() - snapshot.fetchedAtMillis) / 1000L).coerceAtLeast(0L)
                val result = MiningStrategy.estimate(
                    MiningEconomicsInput(
                        deviceHashrate = rate,
                        networkHashrate = snapshot.networkHashrateHps,
                        dailyNetworkRewardCoins = snapshot.dailySubsidyBtc,
                        coinPriceFiat = snapshot.btcPriceInr,
                        powerWatts = power,
                        electricityFiatPerKwh = tariff,
                        poolFeePercent = fee,
                        snapshotAgeSeconds = ageSeconds,
                        networkBlocksPerDay = BitcoinProfitSnapshot.AVERAGE_BLOCKS_PER_DAY,
                        payoutModel = PayoutModel.SOLO_BLOCK_REWARD
                    )
                )
                runOnUiThread {
                    val rateSource = if (liveRate != null) "live phone hashrate" else "last safe device benchmark"
                    tvProfitability.text = buildString {
                        appendLine("BITCOIN SOLO · EXPECTED VALUE (INR)")
                        appendLine("Rate source: $rateSource · ${Util.fmtRate(rate)}")
                        appendLine("BTC price: ₹${String.format(Locale.US, "%.2f", snapshot.btcPriceInr)}")
                        appendLine("Network difficulty: ${String.format(Locale.US, "%.4e", snapshot.difficulty)}")
                        appendLine("Estimated network hashrate: ${Util.fmtRate(snapshot.networkHashrateHps)}")
                        appendLine("Block subsidy: ${String.format(Locale.US, "%.8f", snapshot.blockSubsidyBtc)} BTC")
                        appendLine("Daily subsidy assumption: ${String.format(Locale.US, "%.4f", snapshot.dailySubsidyBtc)} BTC (144 blocks; fees excluded)")
                        appendLine("Expected gross value/day: ${formatInr(result.grossFiatPerDay)}")
                        appendLine("Pool commission/day: ₹0.00 (direct node mining)")
                        appendLine("Estimated electricity/day: ${formatInr(result.electricityFiatPerDay)} (${String.format(Locale.US, "%.2f", power)} W × 24 h)")
                        appendLine("Expected net value/day: ${formatInr(result.netFiatPerDay)}")
                        appendLine("Mean block wait estimate: ${formatExpectedBlockDays(result.expectedDaysPerBlock)}")
                        appendLine("Data age: ${ageSeconds}s · ${if (result.isFresh) "FRESH" else "STALE"}")
                        appendLine("WARNING: expected value only, not daily payout or guaranteed profit. Solo block rewards are rare and highly variable.")
                        if (result.netFiatPerDay <= 0.0) appendLine("RESULT: At these inputs, estimated electricity cost exceeds expected gross value.")
                        else appendLine("RESULT: Positive mathematical expectation only; payout timing risk remains extreme at phone hashrate.")
                    }
                    profitabilityFetchInFlight = false
                    btnRefreshProfitability.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvProfitability.text = "Profitability data unavailable: ${e.message ?: e.javaClass.simpleName}\nLast data is not reused as live. Try refresh later."
                    profitabilityFetchInFlight = false
                    btnRefreshProfitability.isEnabled = true
                }
            }
        }.start()
    }

    private fun formatInr(value: Double): String {
        if (!value.isFinite()) return "N/A"
        return if (kotlin.math.abs(value) >= 0.01) {
            "₹" + String.format(Locale.US, "%.4f", value)
        } else {
            "₹" + String.format(Locale.US, "%.3e", value)
        }
    }

    private fun formatExpectedBlockDays(days: Double): String {
        if (!days.isFinite() || days <= 0.0) return "not estimable"
        return when {
            days >= 365.0 * 100.0 -> String.format(Locale.US, "%.2e years", days / 365.0)
            days >= 365.0 -> String.format(Locale.US, "%.1f years", days / 365.0)
            days >= 1.0 -> String.format(Locale.US, "%.1f days", days)
            else -> String.format(Locale.US, "%.2f days", days)
        }
    }

    private fun exportVerificationReport() {
        val s = MinerState
        val address = etAddress.text.toString().trim().substringBefore('.')
        val maskedAddress = if (address.length >= 12) address.take(5) + "…" + address.takeLast(5)
            else if (address.isNotEmpty()) "provided (masked)" else "not set"
        val df = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
        fun time(v: Long) = if (v > 0L) df.format(java.util.Date(v)) else "never"
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown" } catch (_: Exception) { "unknown" }
        val report = buildString {
            appendLine("LOTTERY MINER — REAL DEVICE VERIFICATION")
            appendLine("Generated: ${df.format(java.util.Date())}")
            appendLine("App version: $version")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("CPU cores: ${Runtime.getRuntime().availableProcessors()}")
            appendLine("Wallet (masked): $maskedAddress")
            appendLine("Direct Node Gateway: " + etHost.text.toString().trim())
            appendLine("Running: ${s.running}; authorized: ${s.connected}; status: ${s.status}")
            appendLine("Connection attempts: ${s.connectionAttempts.get()}")
            appendLine("Last connection attempt: ${time(s.lastConnectAttemptAt)}")
            appendLine("Last authorization: ${time(s.authorizedAt)}")
            appendLine("Last job: ${time(s.lastJobAt)}")
            appendLine("Last share submitted: ${time(s.lastShareSubmittedAt)}")
            appendLine("Last share response: ${time(s.lastShareResponseAt)}")
            appendLine("Network difficulty: " + s.netDiff)
            appendLine("Network-target candidates / node accepted / rejected: " + s.directCandidates.get() + " / " + s.directAccepted.get() + " / " + s.directRejected.get())
            appendLine("Block height / template transaction count: " + s.directHeight + " / " + s.directTransactionCount)
            appendLine("Android thermal status: ${s.androidThermalStatus}")
            appendLine("Hashrate/hashes: ${Util.fmtRate(s.hashrate)} / ${s.totalHashes}")
            appendLine("Active workers: ${s.activeThreads}/${s.totalThreads}")
            appendLine("Worker tuning: ${s.workerTuningStatus}")
            appendLine("Battery: ${if (s.batteryAvailable) "${s.battery}%" else "unavailable"}; charging: ${s.plugged}")
            appendLine("Battery temperature: ${if (s.tempAvailable) "${s.tempC} C" else "unavailable"}")
            appendLine("Thermal state: ${s.pauseReason.ifBlank { "none" }}")
            appendLine("Last disconnect: ${s.lastDisconnectReason.ifBlank { "none recorded" }}")
            appendLine("Last node response: " + s.directLastResponse.ifBlank { "none" })
            appendLine("Recent log:")
            appendLine(s.logText().ifBlank { "No log entries" })
            appendLine("Privacy: full wallet address and credentials are excluded.")
            appendLine("Bitcoin Core acceptance is not the same as network confirmation or wallet payout.")
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Lottery Miner real-device verification")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(send, "Share verification report"))
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
        if (benchmarkRunning || Sha256dBenchmark.isRunning()) {
            Toast.makeText(this, "Benchmark പൂർത്തിയായതിന് ശേഷം mining start ചെയ്യുക.", Toast.LENGTH_LONG).show()
            return
        }
        val addr = etAddress.text.toString().trim()
        if (!Util.isValidBitcoinAddress(addr)) {
            Toast.makeText(this, "ശരിയായ Bitcoin mainnet വിലാസം നൽകുക; worker വേണമെങ്കിൽ address.worker നൽകാം.", Toast.LENGTH_LONG).show()
            return
        }
        val cores = Runtime.getRuntime().availableProcessors()
        val gatewayUrl = etHost.text.toString().trim().trimEnd('/')
        val gatewayUri = runCatching { java.net.URI(gatewayUrl) }.getOrNull()
        if (gatewayUri == null || !gatewayUri.scheme.equals("https", true) || gatewayUri.host.isNullOrBlank() || gatewayUri.userInfo != null || gatewayUri.query != null || gatewayUri.fragment != null || gatewayUrl.length > 512) {
            Toast.makeText(this, "HTTPS Direct Node Gateway URL നൽകുക.", Toast.LENGTH_LONG).show()
            return
        }
        val apiToken = etApiToken.text.toString()
        if (apiToken.toByteArray(Charsets.UTF_8).size < 32 || apiToken.any { it.isWhitespace() }) {
            Toast.makeText(this, "കുറഞ്ഞത് 32-byte സുരക്ഷിത Gateway API token നൽകുക.", Toast.LENGTH_LONG).show()
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
            .putString("address", addr).putString("gatewayUrl", gatewayUrl)
            .putInt("threads", threads).putFloat("maxTemp", maxTemp.toFloat())
            .putInt("maxBatt", maxBatt).putBoolean("onlyCharging", onlyCharging).apply()
        SecretStore.saveToken(this, apiToken)

        val i = Intent(this, MinerService::class.java)
            .setAction(MinerService.ACTION_START)
            .putExtra(MinerService.EXTRA_ADDRESS, addr)
            .putExtra(MinerService.EXTRA_GATEWAY_URL, gatewayUrl)
            .putExtra(MinerService.EXTRA_API_TOKEN, apiToken)
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
        val s = MinerState
        btn.text = if (s.running) "സ്റ്റോപ്പ്" else "സ്റ്റാർട്ട്"
        val sb = StringBuilder()
        if (s.blockCandidateDetected) sb.append("Bitcoin network-target candidate കണ്ടെത്തി — node response പരിശോധിക്കുക\\n")
        sb.append("PHOENIX DIRECT MINING: ").append(Util.fmtRate(s.hashrate))
            .append(if (s.hwSha) "  [ARM SHA2 ✓]" else "").append('\n')
        sb.append("സ്ഥിതി     : ").append(s.status).append('\n')
        sb.append("ത്രെഡ്     : ").append(s.activeThreads).append('/').append(s.totalThreads).append('\n')
        sb.append("Worker tune: ").append(s.workerTuningStatus).append('\n')
        sb.append("ആകെ ഹാഷ്  : ").append(String.format(Locale.US, "%.3e", s.totalHashes.toDouble())).append('\n')
        sb.append("Pool shares: ✓").append(s.accepted.get()).append("  ✗").append(s.rejected.get())
            .append("  (പൂൾ diff ").append(if (s.poolDiff > 0.0) String.format(Locale.US, "%.4g", s.poolDiff) else "N/A").append(")\n")
        if (s.hashrate > 0.0 && s.poolDiff > 0.0) {
            val expectedSeconds = Util.expectedShareIntervalSeconds(s.poolDiff, s.hashrate)
            sb.append("ഏകദേശ share ഇടവേള: ").append(formatExpectedShareInterval(expectedSeconds)).append('\n')
            sb.append("ഇത് feedback share-ന്റെ ശരാശരി മാത്രം; block/payout ഉറപ്പല്ല.\n")
        }
        sb.append("Pool-ലേക്ക് അയച്ചത്: ").append(s.submittedShares.get()).append("\n")
        sb.append("അറിയാത്ത share response: ").append(s.unknownShares.get()).append("\n")
        sb.append("Pool-ലേക്ക് അയക്കാത്തത്: ").append(s.droppedShares.get()).append("\n")
        sb.append("Best local hash diff: ").append(if (s.totalHashes > 0 && s.bestDiff > 0.0) String.format(Locale.US, "%.4g", s.bestDiff) else "N/A").append('\n')
        sb.append("Network diff: ").append(if (s.netDiff > 0.0) String.format(Locale.US, "%.4e", s.netDiff) else "N/A").append('\n')
        sb.append("ബാറ്ററി താപനില: ").append(if (s.tempAvailable) String.format(Locale.US, "%.1f°C", s.tempC) else "N/A")
            .append("   ബാറ്ററി ").append(if (s.batteryAvailable) "${s.battery}%" else "N/A")
            .append(if (s.batteryAvailable && s.plugged) " ⚡" else "").append('\n')
        sb.append("Android thermal: ").append(s.androidThermalStatus).append('\n')
        if (s.pauseReason.isNotEmpty()) sb.append("താൽക്കാലികം: ").append(s.pauseReason).append('\n')
        sb.append("Node acceptance പോലും network confirmation അല്ല; payout confirmation വേറെയാണ്.\\n")
        tvStats.text = sb.toString()
        tvLog.text = s.logText()
    }
}
