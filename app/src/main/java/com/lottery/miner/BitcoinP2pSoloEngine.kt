package com.lottery.miner

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Phone-only direct Bitcoin Mainnet P2P solo-mining attempt.
 *
 * Header PoW, linkage, timestamp and difficulty rules are checked from genesis
 * using two independent peers. This is NOT a full validating node: historical
 * block bodies/UTXO state and a reorg-capable fork DAG are not maintained.
 */
class BitcoinP2pSoloEngine(
    private val context: Context,
    private val payoutAddress: String,
    private val configuredWorkers: Int,
    private val onlyCharging: Boolean,
    private val maxTemperatureC: Double,
    private val maximumBatteryPercent: Int
) {
    companion object {
        private const val MIN_INDEPENDENT_PEERS = 2
        private const val MAX_PEERS = 3
        private const val NONCE_BATCH = 32_768
        private const val MAX_CACHED_BLOCKS = 8
    }

    private data class Safety(
        val workers: Int,
        val reason: String,
        val battery: Int?,
        val temperatureC: Double?,
        val plugged: Boolean,
        val thermalLabel: String
    )

    private val running = AtomicBoolean(false)
    private val peers = CopyOnWriteArrayList<BitcoinP2pPeer>()
    private val readerThreads = CopyOnWriteArrayList<Thread>()
    private val blockCache = ConcurrentHashMap<String, ByteArray>()
    private val totalHashes = AtomicLong(0L)
    private val scannerRef = AtomicReference<EightLaneSha256dScanner?>(null)
    private val tipChanged = AtomicBoolean(false)
    @Volatile private var chain: BitcoinMainnetHeaderChain? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var temperatureHot = false
    @Volatile private var batteryLimitHit = false

    init {
        require(Util.isValidBitcoinAddress(payoutAddress)) { "valid Bitcoin Mainnet payout address required" }
        require(configuredWorkers in 1..8) { "choose 1..8 phone workers" }
        require(maxTemperatureC in 30.0..45.0)
        require(maximumBatteryPercent in 10..100)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        P2pSoloState.reset(configuredWorkers)
        MinerState.reset()
        MinerState.running = true
        MinerState.totalThreads = configuredWorkers
        worker = Thread({ runMiningLoop() }, "bitcoin-p2p-solo").also { it.start() }
    }

    fun stop() {
        running.set(false)
        scannerRef.getAndSet(null)?.cancel()
        peers.forEach { try { it.close() } catch (_: Exception) { } }
        worker?.let { thread ->
            if (thread !== Thread.currentThread()) {
                try { thread.join(6_000L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }
        readerThreads.forEach { it.interrupt() }
        P2pSoloState.stop()
        MinerState.running = false
        MinerState.connected = false
        MinerState.activeThreads = 0
        MinerState.hashrate = 0.0
    }

    private fun runMiningLoop() {
        try {
            val hardware = Native.initCore()
            check(hardware >= 0) { "native SHA256d self-test failed" }
            MinerState.hwSha = hardware == 1
            connectToPeers()
            val selectedChain = synchronizeIndependentPeers()
            chain = selectedChain
            updateTipState(selectedChain)
            peers.forEach { it.requestHeadersAnnouncements() }
            startPeerReaders()

            var extraNonce = 1L
            while (running.get()) {
                val safety = readSafety()
                applySafety(safety)
                if (safety.workers == 0) {
                    MinerState.activeThreads = 0
                    Thread.sleep(2_000L)
                    continue
                }

                val activeChain = chain ?: error("validated header chain is unavailable")
                val candidate = activeChain.buildCoinbaseCandidate(
                    payoutAddress = payoutAddress,
                    extraNonce = encodeExtraNonce(extraNonce),
                    nowSeconds = System.currentTimeMillis() / 1000L
                )
                tipChanged.set(false)
                P2pSoloState.status = "Mining candidate at Mainnet height " + candidate.height
                P2pSoloState.log("New coinbase candidate at height " + candidate.height +
                    " with " + safety.workers + " independent nonce lanes")

                val scanner = EightLaneSha256dScanner(safety.workers, NONCE_BATCH)
                scannerRef.set(scanner)
                val lastProgressHashes = AtomicLong(0L)
                val lastPolicyCheck = AtomicLong(0L)
                val found = try {
                    scanner.scan(candidate) { progress ->
                        lastProgressHashes.set(progress.totalHashes)
                        if (tipChanged.get() || !running.get()) {
                            scanner.cancel()
                        } else {
                            val now = SystemClock.elapsedRealtime()
                            val previousPolicyCheck = lastPolicyCheck.get()
                            if (now - previousPolicyCheck >= 1_000L &&
                                lastPolicyCheck.compareAndSet(previousPolicyCheck, now)
                            ) {
                                val nextSafety = readSafety()
                                applySafety(nextSafety)
                                if (nextSafety.workers == 0 || nextSafety.workers < safety.workers) scanner.cancel()
                            }
                            MinerState.hashrate = progress.hashesPerSecond
                            MinerState.activeThreads = safety.workers
                            MinerState.totalThreads = configuredWorkers
                            MinerState.totalHashes = totalHashes.get() + progress.totalHashes
                            P2pSoloState.hashrate = progress.hashesPerSecond
                            P2pSoloState.totalHashes = totalHashes.get() + progress.totalHashes
                            P2pSoloState.activeWorkers = safety.workers
                            P2pSoloState.workerHashes = progress.workerHashes
                            P2pSoloState.status = if (progress.nonceSpaceCompleted) {
                                "Nonce space completed; rotating ExtraNonce"
                            } else {
                                "Searching SHA256d nonce lanes"
                            }
                        }
                    }
                } finally {
                    scannerRef.compareAndSet(scanner, null)
                }

                if (!running.get()) break
                if (found != null) {
                    totalHashes.addAndGet(found.totalHashes)
                } else {
                    totalHashes.addAndGet(lastProgressHashes.get())
                }
                MinerState.totalHashes = totalHashes.get()
                P2pSoloState.totalHashes = totalHashes.get()

                if (tipChanged.get()) {
                    extraNonce = 1L
                    P2pSoloState.log("New Mainnet tip received; stale candidate discarded")
                    continue
                }
                if (P2pSoloState.pauseReason.isNotEmpty()) {
                    P2pSoloState.status = P2pSoloState.pauseReason
                    continue
                }

                if (found != null) {
                    MinerState.blockCandidateDetected = true
                    val parsed = BitcoinHeader.parseAndCheck(found.blockBytes.copyOfRange(0, 80))
                    P2pSoloState.lastBlockHash = parsed.hashDisplay
                    blockCache[parsed.hashDisplay] = found.blockBytes
                    while (blockCache.size > MAX_CACHED_BLOCKS) {
                        blockCache.keys.firstOrNull()?.let { blockCache.remove(it) } ?: break
                    }
                    val relay = BitcoinP2pBlockRelay.announce(found, peers)
                    P2pSoloState.blocksAnnounced += relay.announcedPeers
                    P2pSoloState.status = "Local PoW found; inv announced to " +
                        relay.announcedPeers + "/" + relay.attemptedPeers + " peers; acceptance pending"
                    P2pSoloState.log("Network-target block candidate " + relay.blockHash +
                        "; full block will be sent only after peer getdata")
                    MinerState.status = P2pSoloState.status
                } else {
                    P2pSoloState.log("Full uint32 nonce space exhausted without a block; rotating ExtraNonce")
                }

                if (extraNonce == Long.MAX_VALUE) error("coinbase ExtraNonce counter exhausted")
                extraNonce++
            }
        } catch (t: Throwable) {
            if (running.get()) {
                P2pSoloState.status = "P2P miner stopped: " + (t.message ?: t.javaClass.simpleName)
                P2pSoloState.log(P2pSoloState.status)
                MinerState.status = P2pSoloState.status
                MinerState.pauseReason = P2pSoloState.status
            }
        } finally {
            running.set(false)
            scannerRef.getAndSet(null)?.cancel()
            peers.forEach { try { it.close() } catch (_: Exception) { } }
            MinerState.running = false
            MinerState.connected = false
            MinerState.activeThreads = 0
            MinerState.hashrate = 0.0
            val finalStatus = if (P2pSoloState.status.startsWith("P2P miner stopped")) P2pSoloState.status else "Stopped"
            P2pSoloState.stop(finalStatus)
        }
    }

    private fun connectToPeers() {
        P2pSoloState.status = "Discovering Bitcoin Mainnet peers"
        P2pSoloState.log("Resolving Bitcoin Mainnet DNS seeds directly from the phone")
        val candidates = BitcoinP2pPeer.discoverCandidates(48)
        check(candidates.isNotEmpty()) { "Bitcoin DNS seeds returned no peer candidates" }
        val successfulAddresses = ArrayList<String>()
        for (address in candidates) {
            if (!running.get() || peers.size >= MAX_PEERS) break
            val peer = BitcoinP2pPeer()
            try {
                val handshake = peer.connectAndHandshake(address)
                peers.add(peer)
                successfulAddresses.add(handshake.peerAddress)
                P2pSoloState.peerCount = peers.size
                P2pSoloState.peerAddresses = successfulAddresses.joinToString()
                P2pSoloState.status = "Connected to " + peers.size + " direct Mainnet peer(s)"
                P2pSoloState.log("P2P handshake " + handshake.peerAddress +
                    ":" + BitcoinP2pPeer.MAINNET_PORT + " protocol " + handshake.peerVersion)
            } catch (e: Exception) {
                peer.close()
                P2pSoloState.log("Peer " + address.hostAddress + " failed: " +
                    (e.message ?: e.javaClass.simpleName))
            }
        }
        check(peers.size >= MIN_INDEPENDENT_PEERS) {
            "Could not connect to two independent Bitcoin Mainnet peers; connected " + peers.size
        }
        MinerState.connected = true
    }

    private fun synchronizeIndependentPeers(): BitcoinMainnetHeaderChain {
        P2pSoloState.status = "Validating Mainnet headers from independent peers"
        val executor = Executors.newFixedThreadPool(peers.size)
        try {
            val jobs: List<Future<Pair<BitcoinP2pPeer, BitcoinMainnetHeaderChain>>> = peers.map { peer ->
                executor.submit(Callable {
                    val synced = BitcoinP2pHeaderSynchronizer.synchronize(peer) { tip, count ->
                        P2pSoloState.status = "Header sync height " + tip.height + " (" + count + " headers)"
                    }
                    peer to synced
                })
            }
            val successful = ArrayList<Pair<BitcoinP2pPeer, BitcoinMainnetHeaderChain>>()
            jobs.forEach { future ->
                try {
                    successful.add(future.get())
                } catch (e: Exception) {
                    P2pSoloState.log("Peer header sync failed: " + (e.cause?.message ?: e.message))
                }
            }
            require(successful.size >= MIN_INDEPENDENT_PEERS) {
                "Header synchronization did not succeed with two independent peers"
            }
            val ranked = successful.sortedByDescending { it.second.tip().cumulativeChainWork }
            val best = ranked.first().second
            val bestTip = best.tip()
            val agreement = successful.count { it.second.tip().hashDisplay == bestTip.hashDisplay }
            require(ranked.size < 2 ||
                ranked[0].second.tip().cumulativeChainWork > ranked[1].second.tip().cumulativeChainWork ||
                ranked[0].second.tip().hashDisplay == ranked[1].second.tip().hashDisplay) {
                "Independent peers report different tips with equal chainwork; refusing to mine"
            }
            P2pSoloState.peerAgreement = agreement.toString() + "/" + successful.size + " peers agree on best-work tip"
            P2pSoloState.log("Header sync complete: height " + bestTip.height +
                "; " + agreement + "/" + successful.size + " peers agree")
            return best
        } finally {
            executor.shutdownNow()
        }
    }

    private fun startPeerReaders() {
        peers.forEachIndexed { index, peer ->
            val thread = Thread({ peerReadLoop(index, peer) }, "bitcoin-p2p-peer-" + index)
            thread.isDaemon = true
            readerThreads.add(thread)
            thread.start()
        }
    }

    private fun peerReadLoop(index: Int, peer: BitcoinP2pPeer) {
        while (running.get()) {
            try {
                val message = peer.receiveMessage()
                when (message.command) {
                    "ping" -> {
                        require(message.payload.size == 8) { "invalid ping payload" }
                        peer.send("pong", message.payload)
                    }
                    "headers" -> handlePeerHeaders(message.payload)
                    "inv" -> {
                        val items = BitcoinP2pBlockRelay.parseInventoryPayload(message.payload)
                        if (items.any {
                            it.type == BitcoinP2pBlockRelay.MSG_BLOCK ||
                                it.type == BitcoinP2pBlockRelay.MSG_WITNESS_BLOCK
                        }) {
                            chain?.let { peer.requestHeaders(listOf(it.tip().hashInternal)) }
                        }
                    }
                    "getdata" -> {
                        blockCache.values.toList().forEach { block ->
                            if (BitcoinP2pBlockRelay.serveGetData(peer, message.payload, block)) {
                                P2pSoloState.blockRequestsServed++
                                P2pSoloState.log("Peer " + index +
                                    " requested an announced block; block payload sent")
                            }
                        }
                    }
                    "sendheaders", "sendaddrv2", "wtxidrelay", "verack" ->
                        require(message.payload.isEmpty()) { "unexpected capability payload" }
                    "reject" -> P2pSoloState.log("Peer " + index + " sent reject message")
                    else -> { /* bounded, checksum-validated message not needed by this client */ }
                }
            } catch (_: SocketTimeoutException) {
                // Quiet peers are not automatically disconnected.
            } catch (e: Exception) {
                if (running.get()) P2pSoloState.log("Peer " + index + " disconnected/invalid: " + e.message)
                peer.close()
                peers.remove(peer)
                P2pSoloState.peerCount = peers.size
                if (peers.size < MIN_INDEPENDENT_PEERS && running.get()) {
                    P2pSoloState.status = "Peer quorum lost; mining stopped"
                    scannerRef.get()?.cancel()
                    running.set(false)
                }
                break
            }
        }
    }

    @Synchronized
    private fun handlePeerHeaders(payload: ByteArray) {
        val headers = BitcoinP2pSync.parseHeaders(payload, verifyProofOfWork = false)
        if (headers.isEmpty()) return
        val activeChain = chain ?: return
        val oldTip = activeChain.tip()
        val lastHeader = BitcoinHeader.parseAndCheck(headers.last())
        if (lastHeader.hashDisplay == oldTip.hashDisplay) return
        val newTip = activeChain.appendHeaders(headers, System.currentTimeMillis() / 1000L)
        if (newTip.hashDisplay != oldTip.hashDisplay) {
            updateTipState(activeChain)
            tipChanged.set(true)
            scannerRef.get()?.cancel()
            if (P2pSoloState.lastBlockHash == newTip.hashDisplay) {
                P2pSoloState.peerHeaderObserved = true
                P2pSoloState.log("Found block appeared in a peer header announcement; confirmation depth is zero")
            } else {
                P2pSoloState.log("New peer header passed contextual rules; stale mining work cancelled")
            }
        }
    }

    private fun updateTipState(activeChain: BitcoinMainnetHeaderChain) {
        val tip = activeChain.tip()
        P2pSoloState.headerHeight = tip.height
        P2pSoloState.headerHash = tip.hashDisplay
        val diff1 = java.math.BigInteger.valueOf(65_535L).shiftLeft(208)
        MinerState.netDiff = diff1.toBigDecimal().divide(tip.cumulativeChainWork.let {
            BitcoinHeader.compactToTarget(tip.bits).toBigDecimal()
        }, 8, java.math.RoundingMode.HALF_UP).toDouble()
    }

    @Synchronized
    private fun readSafety(): Safety {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return Safety(0, "Battery information unavailable; mining paused", null, null, false, "UNKNOWN")
        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val battery = if (level >= 0 && scale > 0) level * 100 / scale else null
        val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val rawTemperature = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val temperature = if (rawTemperature > 0) rawTemperature / 10.0 else null
        if (maximumBatteryPercent < 100 && battery != null) {
            if (!batteryLimitHit && battery >= maximumBatteryPercent) batteryLimitHit = true
            else if (batteryLimitHit && battery < maximumBatteryPercent - 2) batteryLimitHit = false
        }
        if (temperature != null) {
            if (temperature >= maxTemperatureC) temperatureHot = true
            else if (temperature <= maxTemperatureC - 4.0) temperatureHot = false
        }
        val requested = when {
            battery == null -> 0
            onlyCharging && !plugged -> 0
            batteryLimitHit || temperatureHot -> 0
            temperature == null -> 1
            else -> configuredWorkers
        }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else ThermalWorkerPolicy.UNKNOWN
        val decision = ThermalWorkerPolicy.evaluate(configuredWorkers, requested, thermal)
        val reason = when {
            battery == null -> "Battery data unavailable; mining paused"
            onlyCharging && !plugged -> "Waiting for charger"
            batteryLimitHit -> "Battery limit reached; cooling/power pause"
            temperatureHot -> "Battery temperature high; cooling pause"
            decision.reason.isNotEmpty() -> decision.reason
            temperature == null -> "Battery temperature unavailable; one-worker safety cap"
            else -> ""
        }
        return Safety(decision.workerCount, reason, battery, temperature, plugged, ThermalWorkerPolicy.label(thermal))
    }

    private fun applySafety(safety: Safety) {
        P2pSoloState.batteryPercent = safety.battery
        P2pSoloState.batteryTemperatureC = safety.temperatureC
        P2pSoloState.thermalStatus = safety.thermalLabel
        P2pSoloState.pauseReason = if (safety.workers == 0) safety.reason else ""
        P2pSoloState.activeWorkers = safety.workers
        MinerState.batteryAvailable = safety.battery != null
        MinerState.battery = safety.battery ?: 0
        MinerState.plugged = safety.plugged
        MinerState.tempAvailable = safety.temperatureC != null
        MinerState.tempC = safety.temperatureC ?: Double.NaN
        MinerState.androidThermalStatus = safety.thermalLabel
        MinerState.activeThreads = safety.workers
        MinerState.totalThreads = configuredWorkers
        MinerState.pauseReason = if (safety.reason.isNotEmpty()) safety.reason else ""
    }

    private fun encodeExtraNonce(value: Long): ByteArray =
        ByteArray(8) { index -> (value ushr (56 - index * 8)).toByte() }
}
