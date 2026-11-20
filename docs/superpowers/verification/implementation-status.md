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

## Tests added (execution result not yet verified)
- `app/src/main/cpp/native_core_test.c`: native SHA-256d self-test and result-buffer overflow accounting.
- `StratumClientTest`: subscribe/authorize/extranonce/job/share flow and unknown share on disconnect.
- `JobTest`: fixed-width job fields and merkle branch validation.
- `UtilTest`: invalid compact target and zero share target rejection.

## Verification still required
- Run host CMake/CTest, Gradle JVM unit tests, lint, Android native builds for arm64-v8a and x86_64, and both APK assembly tasks in GitHub Actions. Do not merge if any step fails.
- Inspect the resulting debug APK on a physical Android device.
- Configure the four protected Actions secrets before expecting a production-signed release: ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD.
- Perform a real CKPool connect/authorize/share-response test and cross-check accepted shares with the pool's authoritative statistics.
- Perform at least a 60-minute device soak test with thermal/battery monitoring.
- A local candidate, accepted pool share, payout, and confirmed Bitcoin block are distinct states; no block confirmation or payout has been verified.

## Release boundary
Changes remain on the feature branch. `main` has not been modified or merged. The APK has not been declared production-ready because test/build output and live-device/pool evidence are not yet available.
## Review record
- Author self-review completed against the design and implementation plan; no independent reviewer tool or local Android build environment was available in this session.
- The repository Actions workflow has been updated to run the full verification pipeline, but no successful run output was obtained here. Treat all tests/builds as pending until a green Actions run is inspected.
- Live CKPool and physical-device verification remain pending and cannot be inferred from source changes.
