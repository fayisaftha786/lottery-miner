# Lottery Miner — Source and Verification Status

**Audit date:** 2026-11-23  
**Latest verified main commit:** `54ddfb833f958cdb6be04bbf29da9a25b9f89fe6`  
**Merged application/configuration PR:** [#24](https://github.com/fayisaftha786/lottery-miner/pull/24)  
**Merged CI maintenance PR:** [#25](https://github.com/fayisaftha786/lottery-miner/pull/25)  
**Merged signing-gate fix:** [#26](https://github.com/fayisaftha786/lottery-miner/pull/26)  
**Merged Stratum/native correctness fixes:** [#28](https://github.com/fayisaftha786/lottery-miner/pull/28)  
**Latest full verification run:** [#574 / 45185365764](https://github.com/fayisaftha786/lottery-miner/actions/runs/45185365764)  
**Run #574 tested source commit:** `58060472e800c53d7aa8882aff42c5d0859918d3` (merged into main as `54ddfb833f958cdb6be04bbf29da9a25b9f89fe6`)


## Current implementation

The reviewed `main` source contains the following implemented paths:

- Real local SHA-256d nonce scanning through the native C/JNI engine; displayed hashes and hashrate are derived from native scan work and monotonic elapsed time.
- Stratum v1 subscribe/authorize/job/difficulty/share submission with bounded input parsing, field validation, job-generation invalidation, and response-ID matching.
- Pool difficulty changes are staged until the next job; the displayed active difficulty and native share target switch together. Disconnect clears old active/pending difficulty state before reconnect.
- Native best-nonce reporting follows the exact 256-bit hash ordering and does not use rounded floating-point difficulty as the tie-breaker.
- Pending submissions are bounded. A response lost during a disconnect is counted as unknown, not accepted. A full queue increments dropped/not-submitted rather than accepted shares.
- SHA-256d core includes a Bitcoin genesis self-test and independent fixed known-answer vectors in the host-native test suite.
- ARMv8 SHA-256 acceleration is selected only when the runtime CPU feature is present and the accelerated implementation passes the core self-test.
- JNI validates array lengths, scan counts, and non-wrapping nonce ranges before accessing native buffers.
- Battery availability, battery temperature, and Android device thermal status are treated as separate measurements. Unknown battery telemetry pauses mining; unknown battery temperature limits workers; Android thermal policy can reduce or pause work.
- Adaptive worker concurrency ramps conservatively only after pool authorization and job readiness. Safety reductions are applied immediately.
- A local network-target hit is labelled as a candidate. A submitted share, accepted share, payout, and confirmed Bitcoin block remain distinct states.
- The app includes a local benchmark and a read-only profitability estimator with freshness and input checks. Only SHA-256d is an executable miner; other algorithm names are research-only.
- The current Android client uses Stratum V1 over plain TCP. It does not provide transport encryption or Stratum V2 support; users should avoid untrusted networks. CKPool publishes an SV2 endpoint, but this app cannot connect to it until an SV2 client is implemented and verified.
- OCEAN is read-only external-worker monitoring. It does not provide free mining hashrate or provision remote workers.

## Scope and exclusions

This repository currently implements **Android phone CPU mining only**. It does not provision cloud instances or remote workers. The old `feature/zero-cost-cloud-worker-guard` branch is divergent and substantially behind current `main`; it must not be merged wholesale into the current engine. No remote compute provider is represented as connected or available unless an actual supported integration exists.

RandomX, VerusHash, Scrypt, GhostRider, and other non-SHA-256d algorithms are not installed miners. Automatic algorithm switching is disabled until a separately implemented and independently validated native miner, compatible pool adapter, licence review, and real-device benchmark exist.

## Build and CI verification

The current build configuration uses:
- JDK 17
- Android Gradle Plugin 8.10.1
- Gradle wrapper 8.11.1
- Android SDK / compile SDK 36
- NDK 26.1.10909125
- CMake 3.22.1
- Kotlin 2.2.21

The workflow uses Node 24-compatible GitHub Actions:
- `actions/checkout@v7.0.1`
- `actions/setup-java@v6.3.0`
- `gradle/actions/setup-gradle@v6.4.1`
- `actions/upload-artifact@v7.0.2`
- `actions/download-artifact@v8.0.2`

**Latest full CI verification: PASS** for PR #28 workflow commit `58060472e800c53d7aa8882aff42c5d0859918d3`, merged into main as `54ddfb833f958cdb6be04bbf29da9a25b9f89fe6`. Run [#574 / 45185365764](https://github.com/fayisaftha786/lottery-miner/actions/runs/45185365764) completed successfully. Earlier full run #554 also passed for the Node 24 Actions upgrade.

Completed successfully:
- Host-native CMake build and CTest.
- JVM unit tests and Android lint.
- Release native library build and Release APK assembly.
- 16 KB ELF segment and APK alignment checks.
- Release APK checksum recording.
- Release artifact upload and download smoke test.
- Debug APK assembly, debug APK signature verification, and presence of `arm64-v8a` and `x86_64` native libraries.
- Artifact upload for the Debug APK and JVM test reports.

The previous Node.js 20 deprecation warning is absent from run #554. Gradle 8.11.1 emits a non-failing advisory that a newer Gradle version exists. The wrapper remains unchanged because an AGP/Gradle toolchain upgrade requires a separate compatibility-tested change.

The protected release signing step was skipped because the four release-keystore Actions secrets are not configured. Therefore the Release artifact is unsigned and is not production-ready. The Debug test APK is signed with the Android debug key and is for controlled device testing only. The workflow now decodes the keystore only when all four required secrets are present: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`.

## Live verification still required

The following cannot be proven by source inspection or mock-pool unit tests:

- Install the exact CI-built debug APK on a physical Android ARM64 device and record its package version and source commit.
- Confirm a real CKPool subscribe and authorization, receipt of live job/difficulty messages, difficulty changes, and reconnect after a network interruption.
- Verify any accepted share against CKPool's authoritative worker/statistics page. A locally counted submission is not pool acceptance.
- Run a monitored 60-minute device soak test. Record battery telemetry, battery temperature, Android thermal status, worker count, native hash count, and any pauses/reconnects.
- Verify stop/restart leaves no background hashing workers and that old-job work is not submitted.
- For production distribution, configure protected Actions signing secrets and verify the signed release APK with `apksigner`.

No real pool-accepted share, Bitcoin block candidate accepted by the network, payout, or block confirmation is asserted by this source audit.

## Verification boundary

A green CI run establishes that the configured tests and build steps passed for the exact commit that ran. It does not prove real-device hashrate, long-term thermal safety, pool-side acceptance, profitability, payout, or block discovery. Record those separately with device and pool evidence.
