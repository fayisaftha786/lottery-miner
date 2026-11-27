package com.lottery.miner

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Live miner state shared between the service/engine and the UI. All numbers are real measurements. */
object MinerState {
    @Volatile var running = false
    @Volatile var connected = false
    @Volatile var status = "നിർത്തി"
    @Volatile var hashrate = 0.0
    @Volatile var totalHashes = 0L
    val accepted = AtomicInteger(0)
    val rejected = AtomicInteger(0)
    val unknownShares = AtomicInteger(0)
    val droppedShares = AtomicInteger(0)
    val submittedShares = AtomicInteger(0)
    val connectionAttempts = AtomicInteger(0)
    @Volatile var lastConnectAttemptAt = 0L
    @Volatile var authorizedAt = 0L
    @Volatile var lastJobAt = 0L
    @Volatile var lastShareSubmittedAt = 0L
    @Volatile var lastShareResponseAt = 0L
    @Volatile var lastDisconnectReason = ""
    @Volatile var lastPoolEvent = ""
    @Volatile var lastPoolEventAt = 0L
    @Volatile var bestDiff = 0.0
    @Volatile var poolDiff = 0.0
    @Volatile var netDiff = 0.0
    @Volatile var tempC = Double.NaN
    @Volatile var tempAvailable = false
    @Volatile var androidThermalStatus = "N/A"
    @Volatile var battery = 0
    @Volatile var batteryAvailable = false
    @Volatile var plugged = false
    @Volatile var activeThreads = 0
    @Volatile var totalThreads = 0
    @Volatile var workerTuningStatus = "Waiting for an authorized mining job"
    @Volatile var hwSha = false
    @Volatile var blockCandidateDetected = false
    @Volatile var pauseReason = ""

    private val logs = ArrayDeque<String>()

    @Synchronized
    fun noteBest(d: Double) {
        if (d > bestDiff) bestDiff = d
    }

    @Synchronized
    fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logs.addLast("$t $msg")
        while (logs.size > 40) logs.removeFirst()
    }

    @Synchronized
    fun logText(): String = logs.reversed().joinToString("\n")

    @Synchronized
    fun reset() {
        connected = false
        hashrate = 0.0
        battery = 0
        plugged = false
        activeThreads = 0
        workerTuningStatus = "Waiting for an authorized mining job"
        totalHashes = 0L
        accepted.set(0)
        rejected.set(0)
        unknownShares.set(0)
        droppedShares.set(0)
        submittedShares.set(0)
        connectionAttempts.set(0)
        lastConnectAttemptAt = 0L
        authorizedAt = 0L
        lastJobAt = 0L
        lastShareSubmittedAt = 0L
        lastShareResponseAt = 0L
        lastDisconnectReason = ""
        lastPoolEvent = ""
        lastPoolEventAt = 0L
        bestDiff = 0.0
        poolDiff = 0.0
        netDiff = 0.0
        tempC = Double.NaN
        tempAvailable = false
        androidThermalStatus = "N/A"
        batteryAvailable = false
        blockCandidateDetected = false
        pauseReason = ""
        logs.clear()
        status = "തുടങ്ങുന്നു…"
    }
}
