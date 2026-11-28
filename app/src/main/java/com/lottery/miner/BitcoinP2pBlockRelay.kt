package com.lottery.miner

/**
 * Sends a locally found one-coinbase block directly to already-connected peers.
 * A successful send is only a socket write; it is NOT peer acceptance or block
 * confirmation. Callers should monitor peer announcements/chain updates.
 */
object BitcoinP2pBlockRelay {
    data class Result(
        val blockHash: String,
        val attemptedPeers: Int,
        val sentPeers: Int,
        val failedPeers: List<Int>
    )

    fun relay(found: EightLaneSha256dScanner.FoundBlock, peers: List<BitcoinP2pPeer>): Result {
        require(peers.isNotEmpty()) { "at least one connected Mainnet peer is required" }
        val block = found.blockBytes
        require(block.size in 81..4_000_000) { "serialized block is outside the Mainnet size bound" }
        val header = BitcoinHeader.parseAndCheck(block.copyOfRange(0, 80))
        val (transactionCount, _) = BitcoinP2pCodec.decodeCompactSize(block, 80)
        require(transactionCount == 1L) { "this relay path only accepts coinbase-only candidate blocks" }

        var sent = 0
        val failed = ArrayList<Int>()
        peers.forEachIndexed { index, peer ->
            try {
                peer.send("block", block)
                sent++
            } catch (_: Exception) {
                failed.add(index)
            }
        }
        return Result(header.hashDisplay, peers.size, sent, failed)
    }
}
