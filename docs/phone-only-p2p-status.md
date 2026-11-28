# Phone-only Bitcoin P2P work — implementation status

This branch starts a direct Bitcoin Mainnet P2P transport foundation for Android. It uses no Gateway, pool, proxy, or external hashrate provider.

## Implemented in this increment
- Mainnet message envelope encode/decode using f9beb4d9 magic.
- 12-byte command and zero-padding validation.
- 4 MiB payload cap, exact frame-length check, and SHA256d checksum verification.
- Canonical Bitcoin CompactSize encode/decode with truncation and non-canonical-form checks.
- Unit tests for valid frames, wrong magic, checksum tampering, oversized declarations, trailing bytes, and CompactSize boundaries.

## Not implemented yet — this is not yet a solo miner
- TCP peer discovery, DNS seeds, peer address management.
- version/verack handshake, streaming parser, ping/pong and peer scoring.
- Header synchronization and full mainnet consensus validation (retarget, chainwork, MTP and deployment rules).
- Coinbase-only block template construction, BIP34 height, merkle root and witness commitment policy.
- Native scan integration for a locally constructed block and direct block relay.
- Android foreground-service lifecycle, reconnect strategy and end-to-end device tests for P2P.

No mining or block acceptance is claimed. A valid P2P frame/checksum is not proof of a valid Bitcoin block. Further stages must be implemented and tested before enabling solo mining. Phone CPU probability remains extraordinarily low against Bitcoin mainnet ASIC hashrate.
