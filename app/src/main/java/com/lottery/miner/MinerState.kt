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
    @Volatile var lastJobAt = 0L
    @Volatile var lastDisconnectReason = ""
    @Volatile var bestDiff = 0.0
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
    @Volatile var directHeight = 0
    @Volatile var directTransactionCount = 0
    @Volatile var directJobId = ""
    @Volatile var directLastResponse = ""
    val directCandidates = AtomicInteger(0)
    val directAccepted = AtomicInteger(0)
    val directRejected = AtomicInteger(0)
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
        lastJobAt = 0L
        lastDisconnectReason = ""
        bestDiff = 0.0
        netDiff = 0.0
        tempC = Double.NaN
        tempAvailable = false
        androidThermalStatus = "N/A"
        batteryAvailable = false
        blockCandidateDetected = false
        directHeight = 0
        directTransactionCount = 0
        directJobId = ""
        directLastResponse = ""
        directCandidates.set(0)
        directAccepted.set(0)
        directRejected.set(0)
        pauseReason = ""
        logs.clear()
        status = "തുടങ്ങുന്നു…"
    }
}
