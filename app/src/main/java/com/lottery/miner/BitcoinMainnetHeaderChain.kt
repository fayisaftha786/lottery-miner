package com.lottery.miner

import java.math.BigInteger

/**
 * Sequential Bitcoin Mainnet header-chain validator from the hard-coded genesis
 * checkpoint. It retains only the current tip, last 11 timestamps, interval
 * start time and cumulative chainwork; it does not retain a reorg-capable DAG.
 *
 * IMPORTANT: this validates headers, not historical block bodies or transactions.
 * It is an SPV/header-chain view, not a fully validating Bitcoin node.
 */
class BitcoinMainnetHeaderChain private constructor(
    private var height: Long,
    private var tip: BitcoinHeader.Parsed,
    private var cumulativeWork: BigInteger,
    private val recentTimestamps: MutableList<Long>,
    private var difficultyPeriodStartTime: Long
) {
    data class Tip(
        val height: Long,
        val hashDisplay: String,
        val hashInternal: ByteArray,
        val timestamp: Long,
        val bits: Long,
        val medianTimePast: Long,
        val cumulativeChainWork: BigInteger
    )

    companion object {
        private const val GENESIS_HEADER_HEX =
            "01000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "3ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a" +
            "29ab5f49ffff001d1dac2b7c"

        fun fromGenesis(): BitcoinMainnetHeaderChain {
            val raw = GENESIS_HEADER_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val genesis = BitcoinHeader.parseAndCheck(raw)
            check(genesis.hashDisplay ==
                "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f") {
                "compiled Mainnet genesis header does not match the known genesis hash"
            }
            return BitcoinMainnetHeaderChain(
                height = 0L,
                tip = genesis,
                cumulativeWork = genesis.chainWork,
                recentTimestamps = mutableListOf(genesis.timestamp),
                difficultyPeriodStartTime = genesis.timestamp
            )
        }
    }

    @Synchronized
    fun tip(): Tip {
        val mtp = BitcoinMainnetHeaderRules.medianTimePast(recentTimestamps)
        return Tip(
            height, tip.hashDisplay, tip.hashInternal.copyOf(), tip.timestamp,
            tip.bits, mtp, cumulativeWork
        )
    }

    /** Adds one peer headers batch after full contextual header validation. */
    @Synchronized
    fun appendHeaders(headers: List<ByteArray>, nowSeconds: Long): Tip {
        require(headers.size <= BitcoinP2pSync.MAX_HEADERS_PER_MESSAGE) {
            "peer sent more than 2,000 headers in one batch"
        }
        require(nowSeconds >= 0L)
        // Validate against temporary state first so an invalid header midway
        // through a peer batch cannot leave a partially advanced active tip.
        var stagedHeight = height
        var stagedTip = tip
        var stagedWork = cumulativeWork
        var stagedTimes = recentTimestamps.toMutableList()
        var stagedPeriodStart = difficultyPeriodStartTime

        headers.forEach { raw ->
            val nextHeight = stagedHeight + 1L
            val firstIntervalTimestamp = if (
                nextHeight % BitcoinMainnetHeaderRules.DIFFICULTY_INTERVAL == 0L
            ) stagedPeriodStart else null

            val checked = BitcoinMainnetHeaderRules.validateNextHeader(
                rawHeader = raw,
                nextHeight = nextHeight,
                previousHeader = stagedTip,
                previousTimestamps = stagedTimes.toList(),
                nowSeconds = nowSeconds,
                firstIntervalTimestamp = firstIntervalTimestamp
            )

            stagedHeight = nextHeight
            stagedTip = checked
            stagedWork = stagedWork.add(checked.chainWork)
            stagedTimes.add(checked.timestamp)
            if (stagedTimes.size > 11) stagedTimes.removeAt(0)
            if (stagedHeight % BitcoinMainnetHeaderRules.DIFFICULTY_INTERVAL == 0L) {
                stagedPeriodStart = checked.timestamp
            }
        }

        height = stagedHeight
        tip = stagedTip
        cumulativeWork = stagedWork
        recentTimestamps.clear()
        recentTimestamps.addAll(stagedTimes)
        difficultyPeriodStartTime = stagedPeriodStart
        return tip()
    }

    @Synchronized
    fun buildCoinbaseCandidate(
        payoutAddress: String,
        extraNonce: ByteArray,
        nowSeconds: Long,
        tag: String = "LotteryMiner/P2P"
    ): BitcoinCoinbaseTemplate.Candidate {
        require(height > 0L) { "header sync has not advanced beyond Mainnet genesis" }
        val nextHeight = height + 1L
        val history = recentTimestamps.toList()
        val mtp = BitcoinMainnetHeaderRules.medianTimePast(history)
        val timestamp = maxOf(nowSeconds, mtp + 1L)
        BitcoinMainnetHeaderRules.checkTimestamp(timestamp, history, nowSeconds)
        val nextBits = BitcoinMainnetHeaderRules.expectedBits(
            nextHeight = nextHeight,
            previousBits = tip.bits,
            previousTimestamp = tip.timestamp,
            firstIntervalTimestamp = if (
                nextHeight % BitcoinMainnetHeaderRules.DIFFICULTY_INTERVAL == 0L
            ) difficultyPeriodStartTime else null
        )
        // 0x20000000 is the current versionbits top-pattern; buried height floors are met.
        return BitcoinCoinbaseTemplate.build(
            height = nextHeight,
            previousHashInternal = tip.hashInternal.copyOf(),
            version = 0x20000000L,
            timestamp = timestamp,
            bits = nextBits,
            payoutAddress = payoutAddress,
            extraNonce = extraNonce,
            tag = tag
        )
    }
}

/**
 * Sequentially requests getheaders from a peer until it returns an empty headers
 * response. A non-linking fork is rejected; callers must discard this state and
 * resynchronize from genesis to recover. No fork DAG/reorg switch is implemented.
 */
object BitcoinP2pHeaderSynchronizer {
    const val MAX_SYNC_HEADERS = 1_500_000

    fun synchronize(
        peer: BitcoinP2pPeer,
        chain: BitcoinMainnetHeaderChain = BitcoinMainnetHeaderChain.fromGenesis(),
        onProgress: (BitcoinMainnetHeaderChain.Tip, Long) -> Unit = {}
    ): BitcoinMainnetHeaderChain {
        var totalHeaders = 0L
        while (totalHeaders <= MAX_SYNC_HEADERS) {
            peer.requestHeaders(listOf(chain.tip().hashInternal))
            var receivedHeaders: List<ByteArray>? = null
            while (receivedHeaders == null) {
                val message = peer.receiveMessage()
                when (message.command) {
                    "headers" -> {
                        receivedHeaders = BitcoinP2pSync.parseHeaders(
                            message.payload, verifyProofOfWork = false
                        )
                    }
                    "ping" -> {
                        require(message.payload.size == 8) { "invalid ping payload" }
                        peer.send("pong", message.payload)
                    }
                    "sendheaders", "sendaddrv2", "wtxidrelay" ->
                        require(message.payload.isEmpty()) { "unexpected capability payload" }
                    "reject" -> throw IllegalStateException("peer rejected header synchronization")
                    else -> { /* ignore unrelated, checksum-validated P2P messages */ }
                }
            }

            if (receivedHeaders.isEmpty()) {
                require(totalHeaders > 0L) {
                    "peer returned no headers for the Mainnet genesis locator"
                }
                return chain
            }

            totalHeaders += receivedHeaders.size
            require(totalHeaders <= MAX_SYNC_HEADERS) { "header sync exceeded safety limit" }
            val tip = chain.appendHeaders(
                receivedHeaders,
                System.currentTimeMillis() / 1000L
            )
            try { onProgress(tip, totalHeaders) } catch (_: Exception) { }
        }
        throw IllegalStateException("header sync exceeded safety limit")
    }
}
