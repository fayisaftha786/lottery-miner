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
The configured default is `stratum.ckpool.org:3333`, the CKPool-recommended automatic endpoint. The app migrates the previous default `solo.ckpool.org` to this endpoint on launch while preserving custom hostnames. Pool endpoints and availability may change; verify current details with the pool operator.

Use your Bitcoin mainnet payout address as the Stratum username. An optional worker suffix may be used if supported by the pool. Password is `x`. Never enter a seed phrase or private key.

The app's **CKPool statistics** button opens the graphical user page for the configured payout address at `https://stats.ckpool.org/users/<address>`. Statistics may not appear immediately at low hashrates because CKPool applies a minimum share difficulty. A missing statistics page is not proof of a payout or of a lost balance.

## Payout and block verification
This is CKPool solo mining: ordinary accepted shares are feedback and do not create proportional BTC earnings. A payout requires a valid block found by the miner, accepted by CKPool, and subsequently confirmed on the Bitcoin network. A local block candidate is not a confirmed block. Verify any claimed payout independently using the block and transaction details in a trusted Bitcoin block explorer. CKPool charges a 2% fee on a found block reward plus transaction fees.

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


## Mining economics advisor (expected value, not payout)

The in-app Mining Economics panel estimates Bitcoin SHA-256d expected revenue against electricity cost:

- BTC hashprice is read from Startmining's public `/api/market-summary` endpoint in **USD per PH/s per day**. The documented free tier is 100 requests/day; the app refreshes only when the user taps the button and caches a successful response for 30 minutes.
- USD/INR is read from ExchangeRate-API's open `/v6/latest/USD` endpoint. Its open rate is refreshed daily and attribution is shown in-app.
- Hashrate comes from the live native miner or the last real device hashrate recorded by the app. It is never fabricated.
- The user supplies measured *incremental mining power* in watts, the electricity tariff in INR/kWh, and the pool fee percentage. Android battery percentage/temperature are not a reliable wattmeter.
- The estimate shows expected gross value, post-fee value, daily energy, electricity cost, expected net value, and break-even hashrate.

Formula:
- Expected gross USD/day = (measured H/s ÷ 10^15) × hashprice USD/(PH/s)/day.
- Post-fee expected value = gross × (1 − pool fee).
- Daily energy (kWh) = incremental watts × 24 ÷ 1,000.
- Expected net INR/day = post-fee USD × USD/INR − daily energy × tariff INR/kWh.

This is a statistical expected-value comparison, **not an actual payout forecast**. CKPool is solo mining: accepted shares are feedback, and payout requires finding a valid block. The estimator excludes device wear, charger losses, cooling, internet, depreciation, and opportunity cost. A positive estimate is not a guarantee of income.

The current app still mines only SHA-256d. It does not automatically switch to VerusHash, RandomX, or Scrypt; those need separate native algorithms, pool protocol integration, self-tests, device benchmarks, and live accepted-share verification before being advertised as supported.

## Android distribution and foreground service
The app uses an explicitly user-started special-use foreground service for sustained local computation. This does not imply Google Play approval. Google Play policy does not allow apps that mine cryptocurrency on the device; it permits apps that remotely manage mining. Do not submit this on-device miner to Google Play as a mining app. See the [Google Play blockchain-based content policy](https://support.google.com/googleplay/android-developer/answer/13607354).

## Safety and limitations
- Battery temperature is a battery sensor reading, not CPU temperature. It may be unavailable or differ from actual processor temperature.
- Unknown battery telemetry pauses mining. Unknown battery temperature limits operation to one worker. Android thermal status can further reduce or pause workers.
- Thermal and battery safeguards reduce risk but cannot guarantee device safety. Stop mining if the device becomes hot, unstable, or uncomfortable to hold.
- Phone CPU mining has an astronomically low chance of finding a Bitcoin block compared with modern ASIC miners. Accepted pool shares do not guarantee a payout in solo mode.
- Automated tests cannot verify live pool availability, pool-side share acceptance, device thermal stability, or an actual block confirmation. These require real-device testing and independent pool/network evidence.


## Scope: Android phone mining only

This app performs SHA-256d mining on the Android device and submits work to the configured Stratum pool. It does not connect to cloud-compute providers, provision remote instances, or run remote workers. The OCEAN panel is read-only monitoring of workers already associated with a wallet; it does not supply or create mining power.
