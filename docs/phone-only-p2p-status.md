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

## Still required before this can mine
- Robust peer selection, multi-peer synchronization, address persistence, rate limits, misbehavior scoring, and reconnect strategy.
- `getheaders` synchronization with checkpoint/ancestor handling and full consensus rules: 2016-block retarget boundary selection, testnet exceptions (not used for mainnet), median-time-past, future-time limit, versionbits/deployments, chain selection, and reorg handling. A valid PoW header alone does not prove the block/body is consensus-valid.
- A trustworthy current-tip view and block validation strategy. This app is not a full validating node.
- Coinbase-only block assembly: BIP34 height, payout script, subsidy halving, transaction serialization, merkle root, witness commitment rules, timestamp and version policy.
- Integration with the native SHA256d scanner, extranonce/job rebuild, nonce exhaustion handling, stale-tip cancellation, and direct `block` message relay to multiple peers.
- Android foreground-service integration, thermal/battery safeguards, and real-device network tests.

No mining, block acceptance, or payout is claimed by this branch. The current additions are transport and validation primitives only. A phone CPU has an extraordinarily small chance of finding a Bitcoin Mainnet block compared with industrial ASIC hashrate; no code optimization can remove that probability gap.
