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
    @Volatile private var engineStartComplete = false
    @Volatile private var engineStartThread: Thread? = null
    // Service callbacks run on the main thread. Serialize Stop -> Start so a new
    // engine cannot overlap the previous engine's asynchronous shutdown.
    private var stopInProgress = false
    private var pendingStartIntent: Intent? = null
    private var serviceDestroyed = false
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (engine != null && engineStartComplete && !MinerState.running) {
                stopMining { stopSelf() }
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
            ACTION_STOP -> stopMining { stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun startMining(i: Intent) {
        if (serviceDestroyed) return
        if (stopInProgress) {
            // Keep only the newest requested configuration; start it after the old
            // engine has joined and released its resources.
            pendingStartIntent = Intent(i)
            return
        }
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Mining", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(), if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        if (engine != null) return

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lotteryminer:cpu").also { it.acquire() }

        val cfg = MinerConfig(
            address = i.getStringExtra(EXTRA_ADDRESS) ?: "",
            host = i.getStringExtra(EXTRA_HOST) ?: MinerDefaults.POOL_HOST,
            port = i.getIntExtra(EXTRA_PORT, MinerDefaults.POOL_PORT),
            threads = i.getIntExtra(EXTRA_THREADS, 1),
            onlyCharging = i.getBooleanExtra(EXTRA_ONLY_CHARGING, true),
            maxTemp = i.getDoubleExtra(EXTRA_MAX_TEMP, 40.0),
            maxBattery = i.getIntExtra(EXTRA_MAX_BATT, 100)
        )
        val e = MinerEngine(applicationContext, cfg)
        engine = e
        engineStartComplete = false
        handler.postDelayed(ticker, 5000)

        // Native self-tests and the device-specific ARM paired-scan benchmark can
        // take noticeable time. Never run them on the service main thread.
        val startupThread = Thread({
            try {
                e.start()
            } catch (failure: Throwable) {
                MinerState.status = "Miner startup failed: ${failure.message ?: failure.javaClass.simpleName}"
                MinerState.pauseReason = "Miner startup failed"
                MinerState.running = false
                MinerState.log(MinerState.status)
            } finally {
                handler.post {
                    if (engine === e) {
                        engineStartThread = null
                        engineStartComplete = true
                        if (!MinerState.running) stopMining { stopSelf() }
                    }
                }
            }
        }, "miner-engine-start")
        engineStartThread = startupThread
        startupThread.start()
    }

    private fun updateWakeLock() {
        if (MinerState.activeThreads > 0) {
            if (wakeLock?.isHeld != true) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lotteryminer:cpu").also { it.acquire() }
            }
        } else {
            try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
            wakeLock = null
        }
    }
    private fun stopMining(onStopped: (() -> Unit)? = null) {
        if (stopInProgress) return
        handler.removeCallbacks(ticker)
        val stoppingEngine = engine
        val startupThread = engineStartThread
        engine = null
        engineStartThread = null
        engineStartComplete = false

        // MinerEngine.stop() can wait for native scans/workers to exit. Keep that
        // join off the main thread so Stop and service teardown remain responsive.
        if (stoppingEngine == null) {
            releaseMiningResources()
            onStopped?.invoke()
            return
        }
        stopInProgress = true
        Thread({
            try {
                // Preserve lifecycle order: a stop request arriving immediately
                // after Start must not win the monitor race and then allow the
                // queued startup thread to launch an orphan miner afterwards.
                try { startupThread?.join() }
                catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                stoppingEngine.stop()
            } finally {
                handler.post {
                    releaseMiningResources()
                    stopInProgress = false
                    val restart = pendingStartIntent
                    pendingStartIntent = null
                    if (!serviceDestroyed && restart != null) {
                        // A Start received during shutdown supersedes the pending
                        // stopSelf callback and starts only after cleanup completes.
                        startMining(restart)
                    } else {
                        onStopped?.invoke()
                    }
                }
            }
        }, "miner-engine-stop").start()
    }

    private fun releaseMiningResources() {
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
        serviceDestroyed = true
        pendingStartIntent = null
        stopMining()
        super.onDestroy()
    }
}
