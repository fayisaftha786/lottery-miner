# Phone-only Bitcoin P2P work — implementation status

This branch builds a direct Bitcoin Mainnet P2P path for Android. It does not use a Gateway, pool, proxy, or external hashrate provider.

## Implemented
- Mainnet message framing with magic, strict command padding, payload cap, exact length, and SHA256d checksum checks.
- Canonical Bitcoin CompactSize encoder/decoder.
- DNS seed candidate resolution and direct TCP socket connection to a Mainnet peer.
- `version` / `verack` handshake, protocol-version check, ping/pong handling, bounded frame reads, and socket cleanup.
- 80-byte header parser, target decoding, Mainnet proof-of-work limit check, SHA256d proof-of-work validation, and per-header chainwork calculation.
- Mainnet retarget arithmetic helper with the consensus timespan clamp. The caller must supply the correct retarget-boundary history; this helper alone is not a full difficulty validator.
- JVM tests for message framing, malformed frames, CompactSize boundaries, and the Bitcoin genesis header proof-of-work vector.

## Additional implementation increments
- Eight-lane non-overlapping uint32 nonce partitioner and native strided scan entry point. The native API validates non-wrapping ranges; the existing legacy Stratum engine is not yet migrated to this API.
- Bounded P2P getheaders/headers wire helpers and block getdata inventory serialization. The peer wrapper can now request and receive one validated frame, but no complete multi-peer synchronizer is installed.
- Coinbase-only candidate transaction/block serialization with BIP34 height push, Mainnet P2PKH/P2SH and Bech32/Bech32m payout-script decoding, and subsidy-halving calculation.
- Contextual Mainnet header helpers for 2016-block retarget selection, previous-11-block Median Time Past, two-hour future-time bound, previous-hash linkage, and activated BIP34 version floor. These require a validated ancestor context supplied by a caller; no complete chain store currently supplies it. Inputs such as height, previous hash, version, timestamp and nBits still must come from a fully validated chain-state source.

## Still required before this can mine
- Robust peer selection, multi-peer synchronization, address persistence, rate limits, misbehavior scoring, and reconnect strategy.
- `getheaders` synchronization with checkpoint/ancestor handling and full consensus rules: 2016-block retarget boundary selection, testnet exceptions (not used for mainnet), median-time-past, future-time limit, versionbits/deployments, chain selection, and reorg handling. A valid PoW header alone does not prove the block/body is consensus-valid.
- A trustworthy current-tip view and block validation strategy. This app is not a full validating node.
- Coinbase-only block assembly: BIP34 height, payout script, subsidy halving, transaction serialization, merkle root, witness commitment rules, timestamp and version policy.
- Integration with the native SHA256d scanner, extranonce/job rebuild, nonce exhaustion handling, stale-tip cancellation, and direct `block` message relay to multiple peers.
- Android foreground-service integration, thermal/battery safeguards, and real-device network tests.

No mining, block acceptance, or payout is claimed by this branch. The current additions are transport and validation primitives only. A phone CPU has an extraordinarily small chance of finding a Bitcoin Mainnet block compared with industrial ASIC hashrate; no code optimization can remove that probability gap.
