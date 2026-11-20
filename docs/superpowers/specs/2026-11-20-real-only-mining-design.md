# Real-Only Bitcoin Mining Reliability Design

**Date:** 2026-11-20
**Repository:** fayisaftha786/lottery-miner
**Target branch:** feature/real-mining-ultra

## Intent

Make Lottery Miner an honest, reliable Android CPU miner that performs real SHA-256d work and communicates with a real CKPool Stratum v1 endpoint. The app must never present simulated, fabricated, extrapolated-as-actual, or placeholder values as live mining data.

## Goals

- Use the device's native hashing engine for all reported mining work.
- Use live Stratum server jobs, extranonce values, difficulty notifications, and share responses.
- Report only measurements backed by local counters, Android system APIs, or actual pool messages.
- Distinguish disconnected, connecting, authorized, waiting-for-job, hashing, paused, candidate-submitted, accepted, rejected, and stale states.
- Make malformed pool messages, stale jobs, nonce/extranonce exhaustion, thermal uncertainty, and shutdown races safe.
- Require automated tests before an APK is published.
- Keep production signing separate from debug signing.

## Non-goals

- No simulated mining mode or demo dashboard.
- No fake earnings, fabricated accepted shares, or simulated mining counters.
- No claim that a share is a Bitcoin block or that a block is confirmed without authoritative confirmation.
- No automatic increase of worker count beyond the user's configured limit.
- No guarantee of finding a Bitcoin block or earning BTC.

## Approaches considered

1. Patch the existing app in place on main: fastest, but risks destabilizing the default branch and makes rollback/review harder.
2. Create an isolated feature branch and harden the existing Kotlin/C/JNI architecture: recommended because it preserves the current native engine and Android structure while enabling incremental review and CI evidence.
3. Rewrite the miner and protocol stack: highest scope and regression risk; not justified because the repository already has a native hashing foundation and Stratum client.

**Selected approach:** Option 2. All changes remain on feature/real-mining-ultra; no merge to main without review.

## Architecture and behavior

### Truthful telemetry
- Hashrate is calculated only from the delta of the native scan counter over monotonic elapsed time.
- Total hashes increment only by the exact number of nonce attempts actually executed by native code.
- Accepted/rejected/stale shares are counted only from matching Stratum submit responses or explicitly detected stale work; pending submissions are shown separately.
- Pool difficulty and network difficulty are displayed only after valid pool job/difficulty data is received.
- Missing sensor or pool data is displayed as unavailable, never as zero when zero would imply a measured value.
- No earnings or payout field is shown unless backed by an authoritative CKPool payout record; this project will not invent one.

### Stratum protocol
- Validate subscribe result, extranonce1, extranonce2 size, difficulty, notify fields, and all fixed-size hex values.
- Handle mining.set_extranonce safely and invalidate/rebuild work when the server changes extranonce parameters.
- Track a monotonically increasing job generation; a worker may submit only if its job generation is still current.
- Prevent extranonce2 wraparound/collisions; rotate or reconnect safely before the allocated space is exhausted.
- Bound pending submissions, reject unknown/duplicate response IDs, and mark unresolved submissions on disconnect as unknown rather than accepted.
- Preserve clean-jobs semantics and never submit stale work after a clean job switch.
- Use the configured CKPool hostname and DNS; do not hardcode IP addresses.

### Hashing and native boundary
- Preserve ARM SHA2 runtime detection with scalar fallback and existing Genesis self-test.
- Add independent known-vector tests for SHA256d, block-header serialization, target comparison, and nonce scan behavior.
- Ensure best-share tracking compares the complete 256-bit hash, not only its top 32 bits.
- Count all target hits even if native result buffers are capped; batch safely or expose an overflow count.
- Validate JNI array lengths and pending JNI exceptions before accessing buffers.
- Keep nonce ranges disjoint and bounded to the 32-bit nonce space.

### Android safety and lifecycle
- Treat unknown battery temperature as unknown; fail safe by limiting workers to one or pausing until a valid safe reading is available.
- Use Android thermal status where available, in addition to battery temperature.
- Keep charging-only enabled by default on first run, consistent with the UI's checked default.
- Stop and join worker/connection/policy threads with bounded waits; do not allow overlapping miner instances.
- Revisit foreground-service type and target SDK compatibility against current Android platform requirements; do not claim Play Store eligibility for on-device mining.
- Do not silently weaken thermal/battery protections to increase hashrate.

### User interface truth
- Replace the current generic block-found banner with explicit states: candidate detected locally, candidate share submitted, pool accepted/rejected, and block confirmed only with authoritative confirmation.
- Show N/A for unavailable sensor or pool values.
- Clearly label local measurements versus pool-reported values.
- Never label a pool-accepted share as a block win.

### CI and release
- CI must run JVM unit tests, lint, native build, and release APK build in that order; APK publishing is gated on all checks passing.
- Add deterministic mock Stratum tests for protocol behavior only; mock values must never enter production MinerState or user-facing live telemetry.
- Use protected release-signing secrets for production release signing. Until configured, do not label a debug-signed APK as production.
- Verify artifact checksum and record commit SHA/build run in release metadata.

## Acceptance criteria
1. With no pool connection, UI shows disconnected/connecting and no fabricated pool difficulty, shares, or hashrate.
2. With a live pool connection, displayed jobs/difficulty are derived from actual server messages.
3. Native hash counters and measured hashrate advance only while native scan calls execute.
4. A share increments accepted/rejected only after the matching server response; disconnect with unresolved submissions does not count them accepted.
5. A clean job switch prevents submission of work from the previous generation.
6. mining.set_extranonce updates are validated and safely applied.
7. Malformed or oversized protocol messages cannot crash the app or create bogus state.
8. Unknown temperature activates the configured fail-safe behavior and is not rendered as 0°C.
9. A local block candidate is never presented as a confirmed block.
10. Automated tests, lint, native build, and APK build pass before publishing.
11. No simulation/demo mode, fabricated earnings, or fake mining counters are shipped.

## Live verification limits
Source tests and CI can verify protocol logic and native correctness, but they cannot prove real-world pool acceptance, device thermal stability, or a block discovery. A live smoke test must be performed on the user's device/network against CKPool, and accepted shares must be cross-checked with CKPool's authoritative statistics. A longer soak test is required before claiming sustained stability.

## Implementation boundaries
Work in small, independently testable commits on feature/real-mining-ultra. Do not merge to main or claim live mining success until live pool evidence exists.