package com.lottery.miner

/** Truthful live status for direct-P2P mode; values come from real peer and scan events. */
object P2pSoloState {
    @Volatile var modeSelected = true
    @Volatile var running = false
    @Volatile var status = "Stopped"
    @Volatile var peerCount = 0
    @Volatile var peerAddresses = ""
    @Volatile var headerHeight = 0L
    @Volatile var headerHash = ""
    @Volatile var peerAgreement = "not checked"
    @Volatile var activeWorkers = 0
    @Volatile var configuredWorkers = 8
    @Volatile var hashrate = 0.0
    @Volatile var totalHashes = 0L
    @Volatile var workerHashes: List<Long> = emptyList()
    @Volatile var bestHashDiff = 0.0
    @Volatile var lastBlockHash = ""
    @Volatile var blocksAnnounced = 0
    @Volatile var blockRequestsServed = 0
    @Volatile var peerHeaderObserved = false
    @Volatile var batteryPercent: Int? = null
    @Volatile var batteryTemperatureC: Double? = null
    @Volatile var thermalStatus = "N/A"
    @Volatile var pauseReason = ""
    private val logs = ArrayDeque<String>()

    @Synchronized fun log(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        logs.addLast(timestamp + " " + message)
        while (logs.size > 60) logs.removeFirst()
    }

    @Synchronized fun logText(): String = logs.reversed().joinToString("\n")

    @Synchronized fun reset(workers: Int) {
        modeSelected = true
        running = true
        status = "Starting direct Mainnet P2P"
        peerCount = 0
        peerAddresses = ""
        headerHeight = 0L
        headerHash = ""
        peerAgreement = "not checked"
        activeWorkers = 0
        configuredWorkers = workers
        hashrate = 0.0
        totalHashes = 0L
        workerHashes = List(workers) { 0L }
        bestHashDiff = 0.0
        lastBlockHash = ""
        blocksAnnounced = 0
        blockRequestsServed = 0
        peerHeaderObserved = false
        batteryPercent = null
        batteryTemperatureC = null
        thermalStatus = "N/A"
        pauseReason = ""
        logs.clear()
    }

    @Synchronized fun stop(message: String = "Stopped") {
        running = false
        status = message
        activeWorkers = 0
        hashrate = 0.0
        pauseReason = ""
    }
}
