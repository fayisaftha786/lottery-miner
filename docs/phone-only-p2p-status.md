# Phone-only Bitcoin Mainnet P2P miner — implementation and verification status

This branch is configured so the Android app's visible Start action uses direct Bitcoin Mainnet P2P. The P2P path does not use Stratum, a pool, a Gateway, a proxy, or externally supplied hashrate.

## Implemented

- Mainnet P2P message framing with network magic, command/padding checks, payload bounds, exact frame length, and SHA256d checksums.
- Canonical CompactSize encoding/decoding and bounded getheaders/headers/inventory message parsing.
- DNS seed discovery and outbound TCP connections to Bitcoin Mainnet peers, version/verack handshake, ping/pong, and independent reader/writer handling.
- Header PoW checks, compact target decoding, Mainnet PoW limit, chainwork calculation, contextual previous-hash linkage, 2016-block difficulty retarget selection, previous-11-block Median Time Past, two-hour future-time bound, and buried BIP34/BIP66/BIP65 version floors.
- Sequential header synchronization from the hard-coded Mainnet genesis header with at least two independent peers. The app compares cumulative header chainwork and reports peer agreement.
- Coinbase-only block candidate construction, BIP34 height encoding, subsidy halving, Mainnet P2PKH/P2SH and Bech32/Bech32m payout scripts, and single-transaction Merkle-root construction.
- Eight interleaved, non-overlapping nonce lanes (W1 checks 0,8,16…; W2 checks 1,9,17… etc.) using the same Bitcoin SHA256d proof-of-work equation.
- Native ARM SHA-256 acceleration where supported and self-tested, plus the native strided scan JNI API.
- The visible Android Start action launches a foreground phone-only P2P engine; the UI reports peers, synchronized header tip, worker-lane hashes, real hashrate, battery/thermal state, block announcements, and requested block payloads.
- A locally found block is announced with an `inv` inventory message. The full `block` payload is sent only when a peer asks for that hash using `getdata`.
- Battery charging/level, battery temperature, and Android thermal-state safeguards can pause or reduce the active worker count.
- JVM and native tests cover message framing, header parsing/PoW, contextual Mainnet header rules, coinbase serialization, nonce lane partitioning, native SHA256d scanning, and inventory serialization.

## Important limits — this is not a fully validating Bitcoin node

- Header synchronization verifies proof-of-work and contextual header rules, but it does not download and validate the historical block bodies, transaction scripts, UTXO set, or every consensus deployment/activation condition. Header-chain agreement is not equivalent to full-node consensus validation.
- The header store is linear and in-memory. It does not retain a fork DAG, implement safe reorganization handling, persist a trusted checkpoint, or recover automatically from every peer fork. A non-linking header is rejected rather than silently accepted.
- Mining templates contain only the coinbase transaction. They claim the subsidy only, include no mempool transactions or fees, and are built from the selected peer header-chain tip.
- A peer accepting a TCP write, receiving `inv`, or requesting `getdata` does not prove the block was accepted. A peer-announced matching header is evidence of propagation only; confirmations require a later best-chain view.
- CI can compile and test the application, but it cannot prove that a particular Android carrier/network can reach Mainnet peers or that a found block is accepted. That requires a real-device network test.
- Phone CPU hashrate is extraordinarily small compared with industrial Bitcoin ASIC hashrate. No nonce ordering or additional worker strategy changes the network target or guarantees a block.

No block has been found, accepted, confirmed, or paid out by this code. The app reports those events only when directly observed.
