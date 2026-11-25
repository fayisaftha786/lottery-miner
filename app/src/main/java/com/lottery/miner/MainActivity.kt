package com.lottery.miner

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
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
        const val DEFAULT_POOL_HOST = MinerDefaults.POOL_HOST
        const val DEFAULT_POOL_PORT = MinerDefaults.POOL_PORT
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
    private lateinit var btnExportDiagnostics: Button
    private lateinit var btnBenchmark: Button
    private lateinit var tvBenchmark: TextView
    private lateinit var etMiningPower: EditText
    private lateinit var etElectricityTariff: EditText
    private lateinit var etPoolFee: EditText
    private lateinit var btnRefreshProfitability: Button
    private lateinit var tvProfitability: TextView
    @Volatile private var benchmarkRunning = false
    @Volatile private var profitabilityFetchInFlight = false
    private var lastBenchmarkHps = 0.0
    private var lastBenchmarkUsedArmPair = false
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
        configureEdgeToEdgeInsets()
        etAddress = findViewById(R.id.etAddress)
        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etThreads = findViewById(R.id.etThreads)
        etTemp = findViewById(R.id.etTemp)
        etBatt = findViewById(R.id.etBatt)
        cbCharging = findViewById(R.id.cbCharging)
        btn = findViewById(R.id.btnToggle)
        btnPoolStats = findViewById(R.id.btnPoolStats)
        btnExportDiagnostics = findViewById(R.id.btnExportDiagnostics)
        btnBenchmark = findViewById(R.id.btnBenchmark)
        tvBenchmark = findViewById(R.id.tvBenchmark)
        etMiningPower = findViewById(R.id.etMiningPower)
        etElectricityTariff = findViewById(R.id.etElectricityTariff)
        etPoolFee = findViewById(R.id.etPoolFee)
        btnRefreshProfitability = findViewById(R.id.btnRefreshProfitability)
        tvProfitability = findViewById(R.id.tvProfitability)
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
        lastBenchmarkHps = p.getString("lastBenchmarkHps", "0.0")?.toDoubleOrNull() ?: 0.0
        lastBenchmarkUsedArmPair = p.getBoolean("lastBenchmarkUsedArmPair", false)
        etMiningPower.setText(p.getString("miningPowerWatts", ""))
        etElectricityTariff.setText(p.getString("electricityTariff", ""))
        etPoolFee.setText(p.getString("poolFeePercent", "2.0"))

        btn.setOnClickListener { if (MinerState.running) stopMining() else startMining() }
        btnPoolStats.setOnClickListener { openPoolStatistics() }
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
        val configuredMaxTemp = etTemp.text.toString().trim().toDoubleOrNull() ?: 40.0
        if (!configuredMaxTemp.isFinite() || configuredMaxTemp !in 30.0..45.0) {
            Toast.makeText(this, "Temperature limit 30–45°C ഇടയിൽ നൽകുക.", Toast.LENGTH_LONG).show()
            return
        }
        benchmarkRunning = true
        btnBenchmark.isEnabled = false
        btn.isEnabled = false
        tvBenchmark.text = "ADAPTIVE MINING LAB\nSHA-256d benchmark നടക്കുന്നു…\nPool connection ഇല്ല; share submit ചെയ്യില്ല."
        Thread {
            try {
                val previousSavedBenchmarkHps = lastBenchmarkHps
                val previousSavedUsedArmPair = lastBenchmarkUsedArmPair
                val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val batteryTempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
                val batteryTempC = batteryTempTenths / 10.0
                check(batteryTempTenths > 0) { "Battery temperature is unavailable; benchmark refused for safety." }
                check(batteryTempC < configuredMaxTemp - 3.0) {
                    "Battery temperature is too close to the configured limit; let the phone cool before benchmarking."
                }
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
                val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    powerManager.currentThermalStatus
                } else {
                    null
                }
                if (thermalStatus != null) {
                    check(thermalStatus < PowerManager.THERMAL_STATUS_MODERATE) {
                        "Android thermal status is MODERATE or higher; let the phone cool before benchmarking."
                    }
                }
                // When Android cannot report thermal state, follow the miner's fail-safe
                // policy and benchmark only one worker rather than stress all CPU cores.
                // No Android thermal API (pre-Android 10) or UNKNOWN thermal
                // status means benchmark conservatively with one worker only.
                val initialWorkers = if (
                    thermalStatus == null || thermalStatus < PowerManager.THERMAL_STATUS_NONE
                ) 1 else cores
                val headroomThresholds: Map<Int, Float> = if (Build.VERSION.SDK_INT >= 35) {
                    runCatching { powerManager.thermalHeadroomThresholds }.getOrDefault(emptyMap())
                } else emptyMap()
                val initialHeadroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching { powerManager.getThermalHeadroom(10).toDouble() }.getOrNull()
                } else null
                val initialHeadroomDecision = ThermalHeadroomPolicy.evaluate(
                    configuredWorkers = cores,
                    requestedWorkers = initialWorkers,
                    forecastHeadroom = initialHeadroom,
                    manufacturerThresholds = headroomThresholds
                )
                val benchmarkWorkers = initialHeadroomDecision.workerCount
                check(benchmarkWorkers > 0) {
                    initialHeadroomDecision.reason.ifBlank {
                        "Device thermal headroom is too close to its manufacturer limit; let the phone cool before benchmarking."
                    }
                }
                var cachedHeadroomSafe = true
                var lastHeadroomCheckNanos = System.nanoTime()
                val thermalSafe = {
                    // Re-read battery temperature during the benchmark, not just at start.
                    val batteryNow = applicationContext.registerReceiver(
                        null,
                        IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                    )
                    val rawTempNow = batteryNow?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
                    val batteryStillSafe = rawTempNow > 0 &&
                        rawTempNow / 10.0 < configuredMaxTemp - 3.0
                    val currentStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        powerManager.currentThermalStatus
                    } else {
                        null
                    }
                    val androidThermalSafe = when {
                        currentStatus == null -> true // pre-Android 10: battery-temperature guard only
                        currentStatus < PowerManager.THERMAL_STATUS_NONE -> benchmarkWorkers == 1
                        else -> currentStatus < PowerManager.THERMAL_STATUS_MODERATE
                    }
                    // getThermalHeadroom is a slow-moving estimate; poll it no faster than every 2 seconds.
                    val nowNanos = System.nanoTime()
                    if (headroomThresholds.isNotEmpty() &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        nowNanos - lastHeadroomCheckNanos >= 2_000_000_000L
                    ) {
                        val forecast = runCatching {
                            powerManager.getThermalHeadroom(10).toDouble()
                        }.getOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
                        // A missing forecast must not clear an earlier safety abort condition.
                        if (forecast != null) {
                            val decision = ThermalHeadroomPolicy.evaluate(
                                configuredWorkers = cores,
                                requestedWorkers = benchmarkWorkers,
                                forecastHeadroom = forecast,
                                manufacturerThresholds = headroomThresholds
                            )
                            cachedHeadroomSafe = decision.workerCount == benchmarkWorkers
                        }
                        lastHeadroomCheckNanos = nowNanos
                    }
                    batteryStillSafe && androidThermalSafe && cachedHeadroomSafe
                }
                val result = Sha256dBenchmark.runScaling(
                    benchmarkWorkers,
                    durationMillis = 900L,
                    repeatCount = 3,
                    shouldContinue = thermalSafe
                )
                runOnUiThread {
                    val peak = result.peak
                    val recommended = result.recommended
                    // Pre-fill, but do not auto-start, the measured worker recommendation.
                    etThreads.setText(recommended.workers.toString())
                    // Store the conservative near-peak median, not one lucky maximum sample.
                    lastBenchmarkHps = recommended.medianHashesPerSecond
                    lastBenchmarkUsedArmPair = Native.usesArmPairScan()
                    getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                        .putString("lastBenchmarkHps", recommended.medianHashesPerSecond.toString())
                        .putInt("recommendedThreads", recommended.workers)
                        .putBoolean("lastBenchmarkUsedArmPair", Native.usesArmPairScan()).apply()
                    tvBenchmark.text = buildString {
                        appendLine("ADAPTIVE MINING LAB · REPEATED DEVICE SCALING")
                        appendLine("Algorithm: Bitcoin SHA-256d · Hardware SHA-256: ${if (result.hardwareSha) "ON" else "OFF"}")
                        appendLine("Native scan path: ${when {
                            result.hardwareSha && Native.usesArmPairScan() -> "paired ARM SHA2 (runtime equivalence passed)"
                            result.hardwareSha -> "single-state ARM SHA2"
                            else -> "scalar software SHA-256"
                        }}")
                        if (previousSavedBenchmarkHps.isFinite() && previousSavedBenchmarkHps > 0.0) {
                            val changePct = (recommended.medianHashesPerSecond / previousSavedBenchmarkHps - 1.0) * 100.0
                            appendLine("Compared with previous saved benchmark: ${String.format(Locale.US, "%+.1f%%", changePct)}")
                            appendLine("Previous scan path: ${if (previousSavedUsedArmPair) "paired ARM SHA2" else "not recorded as paired ARM SHA2"}")
                            appendLine("Comparison uses the previously saved phone result; conditions may differ.")
                        } else {
                            appendLine("No previous saved benchmark available for device A/B comparison.")
                        }
                        appendLine("Maximum benchmark workers: $benchmarkWorkers")
                        appendLine("Configurations: every worker count from 1 to $benchmarkWorkers")
                        appendLine("Each configuration: warm-up + 3 randomized measurements")
                        result.results.forEach { reading ->
                            appendLine("${reading.workers} worker(s): median ${Util.fmtRate(reading.medianHashesPerSecond)}")
                            appendLine("  range ${Util.fmtRate(reading.minimumHashesPerSecond)}–${Util.fmtRate(reading.maximumHashesPerSecond)} · ${reading.sampleCount} samples")
                        }
                        appendLine("Peak median: ${peak.workers} workers · ${Util.fmtRate(peak.medianHashesPerSecond)}")
                        appendLine("Recommended: ${recommended.workers} workers · ${Util.fmtRate(recommended.medianHashesPerSecond)}")
                        appendLine("Recommendation retains at least 98.5% of peak median throughput.")
                        appendLine("Suggested worker count was filled into the settings field; press Start to apply.")
                        appendLine("Throughput-only recommendation; battery watts are not wall-power.")
                        appendLine("Sustained 5m/30m hashrate is recorded during real mining sessions.")
                        appendLine("Apply only after checking a 30–60 minute mining session.")
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
        val host = etHost.text.toString().trim().lowercase(Locale.US)
        if (!host.contains("ckpool")) {
            tvProfitability.text = "ഈ estimator നിലവിൽ CKPool solo Bitcoin mining-നു മാത്രമാണ്. മറ്റൊരു pool payout model ഇതിൽ ഉൾപ്പെടുത്തിയിട്ടില്ല."
            return
        }
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
        val fee = etPoolFee.text.toString().trim().toDoubleOrNull()
        if (power == null || !power.isFinite() || power !in 0.1..200.0) {
            tvProfitability.text = "USB power meter ഉപയോഗിച്ച് mining മാത്രം ഉപയോഗിക്കുന്ന watts നൽകുക (0.1–200 W)."
            return
        }
        if (tariff == null || !tariff.isFinite() || tariff !in 0.0..1000.0) {
            tvProfitability.text = "നിങ്ങളുടെ വൈദ്യുതി നിരക്ക് ₹/kWh ആയി നൽകുക (0–1000)."
            return
        }
        if (fee == null || !fee.isFinite() || fee !in 0.0..100.0) {
            tvProfitability.text = "Pool fee 0–100% ഇടയിൽ നൽകുക. CKPool-ന്റെ സാധാരണ fee 2% ആണ്."
            return
        }
        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putString("miningPowerWatts", power.toString())
            .putString("electricityTariff", tariff.toString())
            .putString("poolFeePercent", fee.toString())
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
                        appendLine("Expected pool fee/day: ${formatInr(result.poolFeeFiatPerDay)} (${String.format(Locale.US, "%.2f", fee)}%)")
                        appendLine("Estimated electricity/day: ${formatInr(result.electricityFiatPerDay)} (${String.format(Locale.US, "%.2f", power)} W × 24 h)")
                        appendLine("Expected net value/day: ${formatInr(result.netFiatPerDay)}")
                        appendLine("Mean block wait estimate: ${formatExpectedBlockDays(result.expectedDaysPerBlock)}")
                        appendLine("Data age: ${ageSeconds}s · ${if (result.isFresh) "FRESH" else "STALE"}")
                        appendLine("WARNING: expected value only, not daily payout or guaranteed profit. Solo block rewards are rare and highly variable.")
                        if (result.netFiatPerDay <= 0.0) appendLine("RESULT: At these inputs, estimated electricity + pool costs exceed expected gross value.")
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
            appendLine("Pool: ${etHost.text.toString().trim()}:${etPort.text}")
            appendLine("Running: ${s.running}; authorized: ${s.connected}; status: ${s.status}")
            appendLine("Connection attempts: ${s.connectionAttempts.get()}")
            appendLine("Last connection attempt: ${time(s.lastConnectAttemptAt)}")
            appendLine("Last authorization: ${time(s.authorizedAt)}")
            appendLine("Last job: ${time(s.lastJobAt)}")
            appendLine("Last share submitted: ${time(s.lastShareSubmittedAt)}")
            appendLine("Last share response: ${time(s.lastShareResponseAt)}")
            val pendingResponses = (s.submittedShares.get() - s.accepted.get() - s.rejected.get() - s.unknownShares.get()).coerceAtLeast(0)
            appendLine("Session shares submitted/accepted/rejected: ${s.submittedShares.get()}/${s.accepted.get()}/${s.rejected.get()}")
            appendLine("Pending/unknown/not-submitted: $pendingResponses/${s.unknownShares.get()}/${s.droppedShares.get()}")
            appendLine("Local rolling hashrate (up to 5m): ${Util.fmtRate(s.localHashrate5m)} over ${String.format(Locale.US, "%.1f", s.localHashrateWindowSeconds)} seconds")
            appendLine("Sustained rolling hashrate (up to 30m): ${Util.fmtRate(s.localHashrate30m)} over ${String.format(Locale.US, "%.1f", s.localHashrate30mWindowSeconds)} seconds")
            appendLine("Pool website totals are address-wide/cumulative and are not this session's counters.")
            appendLine("Pool/network difficulty: ${s.poolDiff}/${s.netDiff}")
            appendLine("Native SHA-256 implementation: ${if (s.hwSha) "ARMv8 SHA2 hardware" else "scalar software"}")
            appendLine("Paired ARM nonce scan: ${if (s.armPairScan) "enabled (runtime equivalence tests passed)" else "not selected"}")
            appendLine("Android thermal status: ${s.androidThermalStatus}")
            appendLine("Thermal headroom forecast (10s): ${if (s.thermalHeadroomForecast.isFinite()) String.format(Locale.US, "%.3f", s.thermalHeadroomForecast) else "unavailable"}")
            appendLine("Manufacturer thermal thresholds: ${s.thermalHeadroomThresholdsAvailable}")
            appendLine("Hashrate/hashes: ${Util.fmtRate(s.hashrate)} / ${s.totalHashes}")
            appendLine("Active workers: ${s.activeThreads}/${s.totalThreads}")
            appendLine("Battery: ${if (s.batteryAvailable) "${s.battery}%" else "unavailable"}; charging: ${s.plugged}")
            appendLine("Battery temperature: ${if (s.tempAvailable) "${s.tempC} C" else "unavailable"}")
            appendLine("Battery fuel-gauge current: ${if (s.batteryEnergyAvailable) "${s.batteryCurrentMicroAmps} uA" else "unavailable"}")
            appendLine("Battery-side net power: ${if (s.batteryEnergyAvailable) "${s.batteryNetPowerWatts} W" else "unavailable"}")
            appendLine("Session battery discharge estimate: ${s.batteryDischargedMilliWattHours} mWh (${s.batteryEnergySampleCount} samples)")
            appendLine("Note: fuel-gauge data is battery-side net flow, not USB input power or miner-only power.")
            appendLine("Thermal state: ${s.pauseReason.ifBlank { "none" }}")
            appendLine("Last disconnect: ${s.lastDisconnectReason.ifBlank { "none recorded" }}")
            appendLine("Last pool event: ${s.lastPoolEvent.ifBlank { "none" }}")
            appendLine("Recent log:")
            appendLine(s.logText().ifBlank { "No log entries" })
            appendLine("Privacy: full wallet address and credentials are excluded.")
            appendLine("Pool-side accepted share is the strongest external proof of submission.")
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Lottery Miner real-device verification")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(send, "Share verification report"))
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
        sb.append("EXTERNAL WORKER MONITOR · OCEAN BTC\n")
        sb.append("Auto refresh: 30 sec while app is open\n")
        if (snapshot != null) {
            val ageMs = System.currentTimeMillis() - snapshot.fetchedAtMillis
            val snapshotFresh = externalError == null && ageMs in 0..120_000L
            val oceanUserNotFound = snapshot.notice?.contains("no such user", ignoreCase = true) == true
            if (oceanUserNotFound) {
                sb.append("External worker hashrate: N/A (OCEAN user/worker record not found)\n")
                sb.append("Workers detected: സ്ഥിരീകരിക്കാനാവില്ല\n")
            } else {
                sb.append("External worker hashrate: ").append(Util.fmtRate(snapshot.totalHashrate)).append('\n')
                sb.append("Workers detected: ").append(snapshot.workers.size).append('\n')
            }
            snapshot.workers.forEach { worker ->
                sb.append("• ").append(worker.name)
                    .append(": ").append(Util.fmtRate(worker.hashrate))
                    .append(" (5m ").append(Util.fmtRate(worker.hashrate5m)).append(")").append('\n')
            }
            sb.append("Updated: ").append(java.text.SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(java.util.Date(snapshot.fetchedAtMillis))).append('\n')
            if (snapshot.workers.isEmpty()) {
                if (oceanUserNotFound) {
                    sb.append("OCEAN API: ഓൺലൈൻ · ഈ വിലാസത്തിന് OCEAN user/worker record കണ്ടെത്തിയില്ല.\n")
                    sb.append("ഇത് API outage അല്ല; external hashrate സ്ഥിരീകരിക്കാനാവില്ല.\n")
                    if (!host.contains("ocean.xyz")) {
                        sb.append("ഫോൺ CKPool-ലാണ് mining ചെയ്യുന്നത്; CKPool worker-കൾ OCEAN പട്ടികയിൽ വരില്ല.\n")
                    }
                } else if (snapshot.notice != null) {
                    sb.append("OCEAN API: ഓൺലൈൻ · active OCEAN worker കണ്ടെത്തിയില്ല.\n")
                    if (!host.contains("ocean.xyz")) {
                        sb.append("ഫോൺ CKPool-ലാണ് mining ചെയ്യുന്നത്; CKPool worker-കൾ OCEAN പട്ടികയിൽ വരില്ല.\n")
                    } else {
                        sb.append("OCEAN-ൽ accepted worker share ലഭിക്കുന്നതുവരെ external worker കണക്കുകൾ ശൂന്യമായിരിക്കും.\n")
                    }
                } else {
                    sb.append("ഈ wallet-ൽ OCEAN worker ഒന്നും ഇതുവരെ കണ്ടെത്തിയിട്ടില്ല.\n")
                }
            }
            when {
                externalError != null -> {
                    sb.append("API refresh failed: ").append(externalError).append('\n')
                    sb.append("Last successful snapshot is shown for reference only; excluded from combined total.\n")
                }
                !snapshotFresh -> {
                    sb.append("Snapshot stale; excluded from combined total until a fresh response arrives.\n")
                }
                oceanUserNotFound -> {
                    sb.append("Combined total: OCEAN worker status സ്ഥിരീകരിക്കാത്തതിനാൽ കണക്കാക്കുന്നില്ല.\n")
                }
                host.contains("ocean.xyz") -> {
                    sb.append("Combined total: സുരക്ഷിതമായി കണക്കാക്കാനാവില്ല — phone worker OCEAN-ൽ ഉൾപ്പെട്ടിരിക്കാം.\n")
                }
                else -> {
                    sb.append("Combined total (phone + OCEAN external workers): ")
                        .append(Util.fmtRate(MinerState.hashrate + snapshot.totalHashrate)).append('\n')
                }
            }
        } else {
            sb.append("External hashrate: --\n")
            sb.append(if (externalError == null) "API പരിശോധിക്കുന്നു…" else "API unavailable: $externalError").append('\n')
            sb.append("External workers ലഭിക്കുന്നതുവരെ total-ൽ ചേർക്കില്ല.\n")
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
        sb.append("PHONE MINING · LOCAL CURRENT (2s EMA): ").append(Util.fmtRate(s.hashrate))
            .append(when {
                s.armPairScan -> "  [ARM SHA2 PAIRED ✓]"
                s.hwSha -> "  [ARM SHA2 ✓]"
                else -> "  [SCALAR SHA-256]"
            }).append('\n')
        val rollingWindowLabel = if (s.localHashrateWindowSeconds >= 60.0) {
            String.format(Locale.US, "%.1f min", s.localHashrateWindowSeconds / 60.0)
        } else {
            String.format(Locale.US, "%.0f sec", s.localHashrateWindowSeconds)
        }
        sb.append("LOCAL ROLLING AVG (up to 5m): ").append(Util.fmtRate(s.localHashrate5m))
            .append(" · window ").append(rollingWindowLabel).append('\n')
        val sustainedWindowLabel = if (s.localHashrate30mWindowSeconds >= 60.0) {
            String.format(Locale.US, "%.1f min", s.localHashrate30mWindowSeconds / 60.0)
        } else {
            String.format(Locale.US, "%.0f sec", s.localHashrate30mWindowSeconds)
        }
        sb.append("SUSTAINED AVG (up to 30m): ").append(Util.fmtRate(s.localHashrate30m))
            .append(" · window ").append(sustainedWindowLabel).append('\n')
        sb.append("സ്ഥിതി     : ").append(s.status).append('\n')
        sb.append("ഫോൺ CPU workers: ").append(s.activeThreads).append('/').append(s.totalThreads).append('\n')
        sb.append("ആകെ ഹാഷ്  : ").append(String.format(Locale.US, "%.3e", s.totalHashes.toDouble())).append('\n')
        val submitted = s.submittedShares.get()
        val accepted = s.accepted.get()
        val rejected = s.rejected.get()
        val unknown = s.unknownShares.get()
        val pendingResponses = (submitted - accepted - rejected - unknown).coerceAtLeast(0)
        sb.append("SESSION shares: accepted ✓").append(accepted).append(" · rejected ✗").append(rejected).append('\n')
        sb.append("Submitted: ").append(submitted).append(" · pending response: ").append(pendingResponses).append('\n')
        sb.append("Unknown outcome: ").append(unknown).append(" · not submitted (queue full): ").append(s.droppedShares.get()).append('\n')
        sb.append("Pool diff: ").append(if (s.poolDiff > 0.0) String.format(Locale.US, "%.4g", s.poolDiff) else "N/A").append('\n')
        sb.append("കുറിപ്പ്: CKPool Worker Count = pool account identity; ഫോൺ CPU workers-ന്റെ എണ്ണം അല്ല.\n")
        sb.append("ഈ session counters പുതിയ mining start-ൽ reset ചെയ്യും; CKPool Total Shares address-wide cumulative ആണ്.\n")
        if (s.hashrate > 0.0 && s.poolDiff > 0.0) {
            val expectedSeconds = Util.expectedShareIntervalSeconds(s.poolDiff, s.hashrate)
            sb.append("ഏകദേശ share ഇടവേള: ").append(formatExpectedShareInterval(expectedSeconds)).append('\n')
            sb.append("ഇത് feedback share-ന്റെ ശരാശരി മാത്രം; block/payout ഉറപ്പല്ല.\n")
        }
        sb.append("Best local hash diff: ").append(if (s.totalHashes > 0 && s.bestDiff > 0.0) String.format(Locale.US, "%.4g", s.bestDiff) else "N/A").append('\n')
        sb.append("Network diff: ").append(if (s.netDiff > 0.0) String.format(Locale.US, "%.4e", s.netDiff) else "N/A").append('\n')
        sb.append("ബാറ്ററി താപനില: ").append(if (s.tempAvailable) String.format(Locale.US, "%.1f°C", s.tempC) else "N/A")
            .append("   ബാറ്ററി ").append(if (s.batteryAvailable) "${s.battery}%" else "N/A")
            .append(if (s.batteryAvailable && s.plugged) " ⚡" else "").append('\n')
        if (s.batteryEnergyAvailable) {
            val currentMilliAmps = s.batteryCurrentMicroAmps / 1000.0
            val direction = when {
                currentMilliAmps > 1.0 -> "net charging"
                currentMilliAmps < -1.0 -> "net discharging"
                else -> "near-zero battery current"
            }
            sb.append("BATTERY FUEL-GAUGE: ")
                .append(String.format(Locale.US, "%+.0f mA", currentMilliAmps))
                .append(" · ").append(direction)
                .append(" · ").append(String.format(Locale.US, "%+.2f W", s.batteryNetPowerWatts))
                .append('\n')
            sb.append("Session battery discharge estimate: ")
                .append(String.format(Locale.US, "%.2f mWh", s.batteryDischargedMilliWattHours))
                .append(" · samples ").append(s.batteryEnergySampleCount).append('\n')
        } else {
            sb.append("Battery power: N/A (fuel-gauge current/voltage unavailable)\n")
        }
        sb.append("കുറിപ്പ്: ഇത് battery-side sensor estimate മാത്രം; USB wall power അല്ല, miner-only power-ഉം അല്ല.\n")
        sb.append("Android thermal: ").append(s.androidThermalStatus).append('\n')
        sb.append("Thermal headroom forecast (10s): ")
            .append(if (s.thermalHeadroomForecast.isFinite()) String.format(Locale.US, "%.3f", s.thermalHeadroomForecast) else "N/A")
            .append(if (s.thermalHeadroomThresholdsAvailable) " · manufacturer thresholds active" else " · no device thresholds").append('\n')
        if (s.pauseReason.isNotEmpty()) sb.append("താൽക്കാലികം: ").append(s.pauseReason).append('\n')
        sb.append("കുറിപ്പ്: accepted share എന്നത് block കണ്ടെത്തലോ payout-ഓ അല്ല.\n")
        tvStats.text = sb.toString()
        renderExternalStats()
        tvLog.text = s.logText()
    }
}
