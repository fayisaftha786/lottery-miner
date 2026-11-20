# Lottery Miner — Real Stratum v1 Android CPU miner

Lottery Miner performs local CPU SHA-256d hashing and submits valid shares to a configured Stratum v1 pool. It is a solo-mining lottery experiment, not a profitable replacement for dedicated ASIC hardware. A pool-accepted share is not itself a Bitcoin block or a payout, and this app does not promise rewards.

## Real-data rules
- Hashrate and total hashes come from actual native nonce-scan calls.
- Pool difficulty and jobs come from validated Stratum messages.
- Accepted and rejected counters change only after the matching pool response.
- If a connection closes with pending submissions, those shares are marked unknown, never accepted.
- Missing battery/temperature readings are shown as unavailable; missing battery telemetry pauses mining, and missing temperature telemetry limits operation to one worker.
- A locally detected network-target candidate is not a confirmed block. Confirmation requires independent authoritative Bitcoin network evidence.
- No simulated mining mode, fabricated hashrate, fabricated shares, or fabricated earnings are included.

## Pool setup
The configured default is `solo.ckpool.org:3333`. Pool endpoints and availability may change; verify the current hostname and port with the pool operator. The app resolves the hostname through DNS and retries with backoff.

Use your Bitcoin mainnet payout address as the Stratum username. An optional worker suffix may be used if supported by the pool. Password is `x`. Never enter a seed phrase or private key.

## Build and verification
Requires JDK 17, Android SDK 34, NDK 26.1.10909125, CMake 3.22.1, and Gradle 8.7.

```bash
cmake -S app/src/main/cpp -B build/native-tests -DBUILD_TESTING=ON -DCMAKE_BUILD_TYPE=Debug
cmake --build build/native-tests --parallel
ctest --test-dir build/native-tests --output-on-failure
gradle testDebugUnitTest lintDebug --no-daemon
gradle externalNativeBuildRelease --no-daemon
gradle assembleRelease --no-daemon
```

GitHub Actions runs host native tests, JVM tests, lint, Android native compilation, and APK assembly. If protected signing secrets are not configured, the APK artifact is unsigned and must not be installed or distributed as a production build. A tagged production release is refused unless the configured keystore signs and verifies the APK.

Configure these repository Actions secrets to enable signed releases:
- `ANDROID_KEYSTORE_BASE64`: base64-encoded release keystore
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Never commit the keystore or passwords.

## Android distribution and foreground service
The app uses an explicitly user-started special-use foreground service for sustained local computation. This does not imply Google Play approval. Google Play policy does not allow apps that mine cryptocurrency on the device; it permits apps that remotely manage mining. Do not submit this on-device miner to Google Play as a mining app. See the [Google Play blockchain-based content policy](https://support.google.com/googleplay/android-developer/answer/13607354).

## Safety and limitations
- Battery temperature is a battery sensor reading, not CPU temperature. It may be unavailable or differ from actual processor temperature.
- Unknown battery telemetry pauses mining. Unknown battery temperature limits operation to one worker. Android thermal status can further reduce or pause workers.
- Thermal and battery safeguards reduce risk but cannot guarantee device safety. Stop mining if the device becomes hot, unstable, or uncomfortable to hold.
- Phone CPU mining has an astronomically low chance of finding a Bitcoin block compared with modern ASIC miners. Accepted pool shares do not guarantee a payout in solo mode.
- Automated tests cannot verify live pool availability, pool-side share acceptance, device thermal stability, or an actual block confirmation. These require real-device testing and independent pool/network evidence.