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
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_THREADS = "threads"
        const val EXTRA_ONLY_CHARGING = "onlyCharging"
        const val EXTRA_MAX_TEMP = "maxTemp"
        const val EXTRA_MAX_BATT = "maxBatt"
        private const val CHANNEL = "mining"
        private const val NOTIF_ID = 1
    }

    private var engine: MinerEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
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

    private fun startMining(i: Intent) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Mining", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (engine != null) return

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lotteryminer:cpu").also { it.acquire() }

        val cfg = MinerConfig(
            address = i.getStringExtra(EXTRA_ADDRESS) ?: "",
            host = i.getStringExtra(EXTRA_HOST) ?: "solo.ckpool.org",
            port = i.getIntExtra(EXTRA_PORT, 3333),
            threads = i.getIntExtra(EXTRA_THREADS, 1),
            onlyCharging = i.getBooleanExtra(EXTRA_ONLY_CHARGING, true),
            maxTemp = i.getDoubleExtra(EXTRA_MAX_TEMP, 40.0),
            maxBattery = i.getIntExtra(EXTRA_MAX_BATT, 80)
        )
        val e = MinerEngine(applicationContext, cfg)
        engine = e
        e.start()
        handler.postDelayed(ticker, 5000)
    }

    private fun stopMining() {
        handler.removeCallbacks(ticker)
        engine?.stop()
        engine = null
        try { wakeLock?.release() } catch (e: Exception) { /* ignore */ }
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
            Util.fmtRate(MinerState.hashrate) + "  ✓" + MinerState.accepted.get()
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Lottery Miner")
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
