# Lottery Miner — Source and Verification Status

**Audit date:** 2026-11-23  
**Audited base branch:** `main`  
**Base commit reviewed:** `68053f65929e28003e53214858bdb9fcd77e3cf5`  
**Verification update branch:** `fix/final-verification-2026-11-23`

## Current implementation

The reviewed `main` source contains the following implemented paths:

- Real local SHA-256d nonce scanning through the native C/JNI engine; displayed hashes and hashrate are derived from native scan work and monotonic elapsed time.
- Stratum v1 subscribe/authorize/job/difficulty/share submission with bounded input parsing, field validation, job-generation invalidation, and response-ID matching.
- Pending submissions are bounded. A response lost during a disconnect is counted as unknown, not accepted. A full queue increments dropped/not-submitted rather than accepted shares.
- SHA-256d core includes a Bitcoin genesis self-test and independent fixed known-answer vectors in the host-native test suite.
- ARMv8 SHA-256 acceleration is selected only when the runtime CPU feature is present and the accelerated implementation passes the core self-test.
- JNI validates array lengths, scan counts, and non-wrapping nonce ranges before accessing native buffers.
- Battery availability, battery temperature, and Android device thermal status are treated as separate measurements. Unknown battery telemetry pauses mining; unknown battery temperature limits workers; Android thermal policy can reduce or pause work.
- Adaptive worker concurrency ramps conservatively only after pool authorization and job readiness. Safety reductions are applied immediately.
- A local network-target hit is labelled as a candidate. A submitted share, accepted share, payout, and confirmed Bitcoin block remain distinct states.
- The app includes a local benchmark and a read-only profitability estimator with freshness and input checks. Only SHA-256d is an executable miner; other algorithm names are research-only.
- OCEAN is read-only external-worker monitoring. It does not provide free mining hashrate or provision remote workers.

## Scope and exclusions

This repository currently implements **Android phone CPU mining only**. It does not provision cloud instances or remote workers. The old `feature/zero-cost-cloud-worker-guard` branch is divergent and substantially behind current `main`; it must not be merged wholesale into the current engine. No remote compute provider is represented as connected or available unless an actual supported integration exists.

RandomX, VerusHash, Scrypt, GhostRider, and other non-SHA-256d algorithms are not installed miners. Automatic algorithm switching is disabled until a separately implemented and independently validated native miner, compatible pool adapter, licence review, and real-device benchmark exist.

## Build and CI verification

The current build configuration reviewed on `main` uses:
- JDK 17
- Android Gradle Plugin 8.10.1
- Gradle wrapper 8.11.1
- Android SDK / compile SDK 36
- NDK 26.1.10909125
- CMake 3.22.1
- Kotlin 2.2.21

The GitHub Actions workflow is configured to run host-native CMake/CTest, JVM unit tests, Android lint, Android native library builds, release APK assembly, 16 KB ELF/APK alignment checks, APK signing-state verification, and debug APK assembly.

**CI verification: PASS** for the application and configuration changes at commit `21c025d54b84e64e98cd7ad5ae5aa6d6cbd9fb39`, GitHub Actions run [#546 / 45173841370](https://github.com/fayisaftha786/lottery-miner/actions/runs/45173841370).

Completed successfully:
- Host-native CMake build and CTest.
- JVM unit tests and `lintDebug`.
- Release native library build and Release APK assembly.
- 16 KB ELF segment and APK alignment checks.
- APK checksum recording.
- Debug APK assembly, debug APK signature verification, and presence of `arm64-v8a` and `x86_64` native libraries.
- Artifact upload for release build, debug test APK, and JVM test reports.

The protected release signing step was skipped because release-keystore secrets were not available to this workflow. The Release artifact is therefore `app-release-unsigned.apk`, not production-signed. The Debug test APK is signed with the Android debug key and is intended only for controlled device testing.

This report update is documentation-only and is being added after run #546; that later documentation commit is not itself part of the tested application-code SHA above.

A release artifact without the protected release keystore is unsigned and is not a production distribution. Debug-signed test APKs are for controlled device testing only.

## Live verification still required

The following cannot be proven by source inspection or mock-pool unit tests:

- Install the exact CI-built debug APK on a physical Android ARM64 device and record its package version and source commit.
- Confirm a real CKPool subscribe and authorization, receipt of live job/difficulty messages, and reconnect after a network interruption.
- Verify any accepted share against CKPool's authoritative worker/statistics page. A locally counted submission is not pool acceptance.
- Run a monitored 60-minute device soak test. Record battery telemetry, battery temperature, Android thermal status, worker count, native hash count, and any pauses/reconnects.
- Verify stop/restart leaves no background hashing workers and that old-job work is not submitted.
- For production distribution, configure protected Actions signing secrets and verify the signed release APK with `apksigner`.

No real pool-accepted share, Bitcoin block candidate accepted by the network, payout, or block confirmation is asserted by this source audit.

## Verification boundary

A green CI run establishes that the configured tests and build steps passed for the exact commit that ran. It does not prove real-device hashrate, long-term thermal safety, pool-side acceptance, profitability, payout, or block discovery. Record those separately with device and pool evidence.
