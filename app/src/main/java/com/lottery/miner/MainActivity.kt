package com.lottery.miner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var etAddress: EditText
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etThreads: EditText
    private lateinit var etTemp: EditText
    private lateinit var etBatt: EditText
    private lateinit var cbCharging: CheckBox
    private lateinit var btn: Button
    private lateinit var tvStats: TextView
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
        etAddress = findViewById(R.id.etAddress)
        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etThreads = findViewById(R.id.etThreads)
        etTemp = findViewById(R.id.etTemp)
        etBatt = findViewById(R.id.etBatt)
        cbCharging = findViewById(R.id.cbCharging)
        btn = findViewById(R.id.btnToggle)
        tvStats = findViewById(R.id.tvStats)
        tvLog = findViewById(R.id.tvLog)

        val cores = Runtime.getRuntime().availableProcessors()
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        etAddress.setText(p.getString("address", ""))
        etHost.setText(p.getString("host", "solo.ckpool.org"))
        etPort.setText(p.getInt("port", 3333).toString())
        etThreads.setText(p.getInt("threads", maxOf(1, cores / 2)).toString())
        etTemp.setText(p.getFloat("maxTemp", 40f).toString())
        etBatt.setText(p.getInt("maxBatt", 100).toString())
        cbCharging.isChecked = p.getBoolean("onlyCharging", false)

        btn.setOnClickListener { if (MinerState.running) stopMining() else startMining() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private fun startMining() {
        val addr = etAddress.text.toString().trim()
        if (!Util.isValidBitcoinAddress(addr)) {
            Toast.makeText(this, "ശരിയായ Bitcoin mainnet വിലാസം നൽകുക; worker വേണമെങ്കിൽ address.worker നൽകാം.", Toast.LENGTH_LONG).show()
            return
        }
        val cores = Runtime.getRuntime().availableProcessors()
        val host = etHost.text.toString().trim().ifEmpty { "solo.ckpool.org" }
        if (host.any { it.isWhitespace() } || host.contains('/') || host.length > 253) {
            Toast.makeText(this, "ശരിയായ pool hostname നൽകുക.", Toast.LENGTH_LONG).show()
            return
        }
        val port = etPort.text.toString().toIntOrNull() ?: 3333
        if (port !in 1..65535) {
            Toast.makeText(this, "Pool port 1 മുതൽ 65535 വരെയായിരിക്കണം.", Toast.LENGTH_LONG).show()
            return
        }
        val threads = (etThreads.text.toString().toIntOrNull() ?: maxOf(1, cores / 2)).coerceIn(1, cores)
        val maxTemp = etTemp.text.toString().toDoubleOrNull() ?: 40.0
        if (!maxTemp.isFinite() || maxTemp !in 30.0..45.0) {
            Toast.makeText(this, "സുരക്ഷയ്ക്കായി പരമാവധി താപനില 30–45°C ഇടയിൽ തിരഞ്ഞെടുക്കുക.", Toast.LENGTH_LONG).show()
            return
        }
        val maxBatt = (etBatt.text.toString().toIntOrNull() ?: 100).coerceIn(10, 100)
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
        val s = MinerState
        btn.text = if (s.running) "സ്റ്റോപ്പ്" else "സ്റ്റാർട്ട്"
        val sb = StringBuilder()
        if (s.blockFound) sb.append("🎉🎉 ബ്ലോക്ക് കണ്ടെത്തി! 🎉🎉\n")
        sb.append("സ്ഥിതി     : ").append(s.status).append('\n')
        sb.append("വേഗത      : ").append(Util.fmtRate(s.hashrate))
            .append(if (s.hwSha) "  [ARM SHA2 ✓]" else "").append('\n')
        sb.append("ത്രെഡ്     : ").append(s.activeThreads).append('/').append(s.totalThreads).append('\n')
        sb.append("ആകെ ഹാഷ്  : ").append(String.format(Locale.US, "%.3e", s.totalHashes.toDouble())).append('\n')
        sb.append("Shares    : ✓").append(s.accepted.get()).append("  ✗").append(s.rejected.get())
            .append("  (പൂൾ diff ").append(s.poolDiff).append(")\n")
        sb.append("Best share: ").append(String.format(Locale.US, "%.4g", s.bestDiff)).append('\n')
        sb.append("Network diff: ").append(String.format(Locale.US, "%.4e", s.netDiff)).append('\n')
        sb.append("താപനില    : ").append(String.format(Locale.US, "%.1f°C", s.tempC))
            .append("   ബാറ്ററി ").append(s.battery).append('%')
            .append(if (s.plugged) " ⚡" else "").append('\n')
        if (s.pauseReason.isNotEmpty()) sb.append("താൽക്കാലികം: ").append(s.pauseReason).append('\n')
        if (s.netDiff > 0 && s.hashrate > 0) {
            val perBlock = s.netDiff * 4294967296.0
            val years = perBlock / s.hashrate / 31557600.0
            val pSoFar = -Math.expm1(-s.totalHashes.toDouble() / perBlock) * 100.0
            sb.append("ഒരു ബ്ലോക്കിന് ശരാശരി ≈ ").append(String.format(Locale.US, "%.3e", years)).append(" വർഷം\n")
            sb.append("ഇതുവരെ ജയിക്കാനുള്ള സാധ്യത: ").append(String.format(Locale.US, "%.3e", pSoFar)).append(" %\n")
        }
        tvStats.text = sb.toString()
        tvLog.text = s.logText()
    }
}
