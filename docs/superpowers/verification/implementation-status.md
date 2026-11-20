# Real-Only Mining Implementation Status

**Branch:** `feature/real-mining-ultra`
**Base:** `main` at `bcaa3b5f3afcbca3e3cac84b7201992904aed617`

## Source changes committed
- Stratum input lines are bounded; subscribe, extranonce, difficulty, job, and share-response fields are validated.
- `mining.set_extranonce` invalidates old work and restarts extranonce allocation after a new server subscription.
- Job generations prevent old work from being submitted after job, difficulty, extranonce, or connection transitions.
- Share responses are tied to pending request IDs. Ambiguous send failures and disconnects become unknown, not accepted.
- Native best-hash comparison checks all 256 bits; extra target hits beyond the JNI result buffer are counted and surfaced.
- JNI checks array lengths, exceptions, scan counts, and non-wrapping 32-bit nonce ranges.
- Unknown battery telemetry pauses mining; unknown battery temperature limits mining to one worker; Android thermal status can reduce/pause workers.
- Worker shutdown uses a bounded shared deadline and blocks restart while old workers remain alive.
- Dashboard distinguishes a local block candidate from a confirmed block and displays unavailable telemetry as N/A.
- Release signing no longer silently uses the debug key. Release publishing is isolated to a tag-only job with write permission.
- Added host-native C tests, Kotlin tests for Stratum/Job/target handling, and an installable debug test APK artifact workflow.

## Latest GitHub Actions verification

**Run:** [44746519862](https://github.com/fayisaftha786/lottery-miner/actions/runs/44746519862)  
**Built commit:** `fe88340c167ba496a5e2daf74bfbd50bb7f10d91`  
**Overall result:** SUCCESS

- Host native CMake/CTest: 1/1 passed.
- JVM unit tests: 16/16 passed (including Stratum subscribe/authorize/job/share and unknown-share disconnect tests).
- Android `lintDebug`: passed.
- Android release native libraries: built successfully.
- Release APK: assembled successfully, but unsigned because protected release signing secrets are not configured.
- Debug test APK: assembled successfully and uploaded as `LotteryMiner-debug-test-apk`.

The debug APK is a test build signed with the standard Android debug key. It is suitable for device testing, not production distribution. The unsigned release APK must not be installed/distributed as a production build.

## Live verification still required

- Install and test the debug APK on a physical Android device.
- Perform a real CKPool connect/authorize/share-response test and cross-check accepted shares with the pool's authoritative statistics.
- Perform at least a 60-minute device soak test with thermal/battery monitoring.
- Configure protected Actions secrets before expecting a production-signed release: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
- A local candidate, accepted pool share, payout, and confirmed Bitcoin block are distinct states; no block confirmation or payout has been verified.

## Release boundary
Changes remain on the feature branch. `main` has not been modified or merged. The APK has not been declared production-ready because test/build output and live-device/pool evidence are not yet available.
## Review record
- Source-level self-review completed. The green GitHub Actions run above verifies CI compilation/tests, not independent code review or live-device behavior.
- Live CKPool and physical-device verification remain pending and cannot be inferred from source changes.
