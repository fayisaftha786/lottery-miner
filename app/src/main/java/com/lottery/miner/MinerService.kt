package com.lottery.miner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class MinerService : Service() {

    companion object {
        const val ACTION_START = "com.lottery.miner.START"
        const val ACTION_STOP = "com.lottery.miner.STOP"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_GATEWAY_URL = "gatewayUrl"
        const val EXTRA_API_TOKEN = "apiToken"
        const val EXTRA_THREADS = "threads"
        const val EXTRA_ONLY_CHARGING = "onlyCharging"
        const val EXTRA_MAX_TEMP = "maxTemp"
        const val EXTRA_MAX_BATT = "maxBatt"
        private const val CHANNEL = "phoenix_direct_mining"
        private const val NOTIF_ID = 1
    }

    private var directEngine: DirectSoloEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (directEngine != null && !MinerState.running) {
                stopMining()
                stopSelf()
                return
            }
            updateWakeLock()
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
            handler.postDelayed(this, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMining(intent)
            ACTION_STOP -> {
                stopMining()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startMining(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "PHOENIX Direct Solo", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        if (directEngine != null) return

        val config = DirectSoloConfig(
            payoutAddress = intent.getStringExtra(EXTRA_ADDRESS) ?: "",
            gatewayUrl = intent.getStringExtra(EXTRA_GATEWAY_URL) ?: "",
            apiToken = intent.getStringExtra(EXTRA_API_TOKEN) ?: "",
            threads = intent.getIntExtra(EXTRA_THREADS, 1),
            onlyCharging = intent.getBooleanExtra(EXTRA_ONLY_CHARGING, true),
            maxTemp = intent.getDoubleExtra(EXTRA_MAX_TEMP, 40.0),
            maxBattery = intent.getIntExtra(EXTRA_MAX_BATT, 100)
        )
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lotteryminer:direct-cpu").also { it.acquire() }
            val engine = DirectSoloEngine(applicationContext, config)
            directEngine = engine
            engine.start()
            handler.postDelayed(ticker, 5000)
        } catch (error: Exception) {
            MinerState.running = false
            MinerState.status = "Direct mining configuration error: " + (error.message ?: error.javaClass.simpleName)
            MinerState.log(MinerState.status)
            stopMining()
            stopSelf()
        }
    }

    private fun updateWakeLock() {
        if (MinerState.activeThreads > 0) {
            if (wakeLock?.isHeld != true) {
                val power = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lotteryminer:direct-cpu").also { it.acquire() }
            }
        } else {
            try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
            wakeLock = null
        }
    }

    private fun stopMining() {
        handler.removeCallbacks(ticker)
        directEngine?.stop()
        directEngine = null
        try { wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MinerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (MinerState.pauseReason.isNotEmpty()) {
            MinerState.pauseReason
        } else {
            Util.fmtRate(MinerState.hashrate) + " · height " + MinerState.directHeight
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("PHOENIX Direct Solo")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        stopMining()
        super.onDestroy()
    }
}
