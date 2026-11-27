package com.lottery.miner

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Live miner state shared between the service/engine and the UI. All numbers are real measurements. */
object MinerState {
    @Volatile var running = false
    @Volatile var connected = false
    @Volatile var versionRollingNegotiated = false
    @Volatile var versionRollingMask = 0L
    @Volatile var extranonce2Bytes = 0
    @Volatile var status = "നിർത്തി"
    /** EMA of real native scan throughput, updated by the policy loop every ~2 seconds. */
    @Volatile var hashrate = 0.0
    /** Rolling average derived from cumulative native hashes over at most five minutes. */
    @Volatile var localHashrate5m = 0.0
    @Volatile var localHashrateWindowSeconds = 0.0
    /** Real rolling average over the observed portion of up to thirty minutes. */
    @Volatile var localHashrate30m = 0.0
    @Volatile var localHashrate30mWindowSeconds = 0.0
    /** Wall-clock start of this app mining session; session counters reset on each start. */
    @Volatile var sessionStartedAtMillis = 0L
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
    /** Android thermal headroom forecast; NaN means unsupported/unavailable. */
    @Volatile var thermalHeadroomForecast = Double.NaN
    /** True only when Android exposes manufacturer-defined headroom thresholds. */
    @Volatile var thermalHeadroomThresholdsAvailable = false
    @Volatile var battery = 0
    @Volatile var batteryAvailable = false
    @Volatile var plugged = false
    /** Device fuel-gauge telemetry; this is battery-side net power, not USB wall power. */
    @Volatile var batteryEnergyAvailable = false
    @Volatile var batteryCurrentMicroAmps = Long.MIN_VALUE
    @Volatile var batteryVoltageMilliVolts = 0
    @Volatile var batteryNetPowerWatts = Double.NaN
    @Volatile var batteryDischargedMilliWattHours = 0.0
    @Volatile var batteryEnergySampleCount = 0
    @Volatile var activeThreads = 0
    @Volatile var totalThreads = 0
    @Volatile var hwSha = false
    @Volatile var armPairScan = false
    @Volatile var armQuadScan = false
    /** Per-device measured quad throughput gain vs the selected baseline; -1 means unavailable. */
    @Volatile var armQuadGainPercent = -1000.0
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
        versionRollingNegotiated = false
        versionRollingMask = 0L
        extranonce2Bytes = 0
        hashrate = 0.0
        localHashrate5m = 0.0
        localHashrateWindowSeconds = 0.0
        localHashrate30m = 0.0
        localHashrate30mWindowSeconds = 0.0
        sessionStartedAtMillis = System.currentTimeMillis()
        battery = 0
        plugged = false
        activeThreads = 0
        armPairScan = false
        armQuadScan = false
        armQuadGainPercent = -1000.0
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
        thermalHeadroomForecast = Double.NaN
        thermalHeadroomThresholdsAvailable = false
        batteryAvailable = false
        batteryEnergyAvailable = false
        batteryCurrentMicroAmps = Long.MIN_VALUE
        batteryVoltageMilliVolts = 0
        batteryNetPowerWatts = Double.NaN
        batteryDischargedMilliWattHours = 0.0
        batteryEnergySampleCount = 0
        blockCandidateDetected = false
        pauseReason = ""
        logs.clear()
        status = "തുടങ്ങുന്നു…"
    }
}
