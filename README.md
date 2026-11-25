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

**Network security:** this Android client currently implements Stratum V1 over plain TCP; it does not encrypt the pool connection. On an untrusted network, an on-path party may observe or manipulate mining traffic, including work and share messages. Prefer a trusted network; do not treat this connection as a secure channel. CKPool also publishes a Stratum V2 endpoint on port `3336`, but this app does not implement Stratum V2 and must use the V1 endpoint above.

The app's **CKPool statistics** button opens the graphical user page for the configured payout address at `https://stats.ckpool.org/users/<address>`. Statistics may not appear immediately at low hashrates because CKPool applies a minimum share difficulty. A missing statistics page is not proof of a payout or of a lost balance.

## Payout and block verification
This is CKPool solo mining: ordinary accepted shares are feedback and do not create proportional BTC earnings. A payout requires a valid block found by the miner, accepted by CKPool, and subsequently confirmed on the Bitcoin network. A local block candidate is not a confirmed block. Verify any claimed payout independently using the block and transaction details in a trusted Bitcoin block explorer. CKPool charges a 2% fee on a found block reward plus transaction fees.

## Build and verification
Requires JDK 17, Android SDK 36 (Android 16), NDK 26.1.10909125, CMake 3.22.1, Android Gradle Plugin 8.10.1, and Gradle 8.11.1.

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

## Android 16 compatibility

The app compiles and targets Android 16 (API 36). Its scrolling root applies system-bar and display-cutout insets for edge-to-edge layouts, keeping the mining controls and diagnostic panels accessible on modern devices. The foreground service remains explicitly user-started and uses the declared `specialUse` service type.

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

## Adaptive Mining Lab

The app includes a local SHA-256d benchmark and a conservative profitability calculation model. The repeated scaling benchmark runs only while mining is stopped, scans a synthetic header against a zero target, and never opens a pool connection or submits shares. It warms each candidate once, randomizes the candidate order across rounds, measures repeated samples after worker threads are ready, and reports median/minimum/maximum hashrate. Its recommendation is the lowest worker count retaining at least 98.5% of peak median throughput. This is a throughput-only recommendation, not a measured energy-efficiency result; battery watts and sustained thermal performance require separate device measurements.

The algorithm registry currently marks SHA-256d as installed. RandomX, VerusHash, GhostRider, and AstroBWT are research candidates only; they are not executable miners in this app yet. They must not be selected or advertised as available until a native implementation is integrated, licensed correctly, cross-checked against an independent reference implementation, and benchmarked on real ARM64 hardware.

Profitability estimates require device benchmark rate, network hashrate, daily network reward, market price, measured power, local electricity tariff, pool fee, and a fresh timestamp. Snapshots older than 15 minutes are rejected for net-profit selection. Hashrates from different algorithms are never compared directly. Solo-mining reward probability must not be presented as proportional pool income.

Automatic algorithm switching remains disabled until live market/network/pool data adapters and at least two validated mining implementations are available. The engine must choose by expected net return per day only after subtracting pool fees and electricity cost; if no candidate has a fresh positive estimate, mining should remain stopped or stay on the user's explicit manual selection.

## Adaptive Mining Lab and profitability estimates

The in-app single-thread reference benchmark is bounded to five seconds. The worker-scaling benchmark checks every worker count from 1 through the device's allowed maximum, with one warm-up and three randomized 900 ms measurements per count by default. It runs at reduced thread priority only while phone mining is stopped. It scans deterministic synthetic headers against a zero target and never connects to a pool or submits shares. The near-peak median rate is saved locally for the estimator; it is not a wall-power or sustained-thermal measurement.

The Bitcoin estimator refreshes read-only public data on user request:
- Bitcoin network difficulty and current block subsidy: Blockchain.com simple network API.
- BTC/INR ticker price: Blockchain.com ticker.

Network hashrate is estimated from difficulty as `difficulty × 2^32 / 600 seconds`. Expected daily subsidy uses 144 blocks/day and the current subsidy returned by the API; transaction fees are excluded. The model shows expected value, CKPool fee, and electricity cost separately. The power input must be the *mining-only incremental wall power* measured with an external meter; the electricity tariff must match the user's bill. Android battery telemetry is not treated as a wall-power meter.

For CKPool solo mining the displayed net value is only a long-run mathematical expectation. It is not a daily payout forecast: a payout requires a valid network block, and the wait time at phone hashrates can be extraordinarily long. Accepted shares are feedback, not proportional BTC earnings. The estimator refuses to use snapshots older than 15 minutes and does not reuse failed market data as if it were live.

Only SHA-256d has a native miner implementation in this application. VerusHash, RandomX, Scrypt, and other algorithm entries are research targets, not selectable or executable miners. Automatic multi-algorithm switching is disabled until a separately audited native engine, compatible pool protocol, license notices, per-device benchmarks, and network/economic data adapters are integrated and validated.


## Installing CI test APKs safely

GitHub Actions debug-test APKs are signed with the runner's debug key. A later workflow run can have a different signing certificate, so Android may refuse an in-place update over a debug APK from an earlier run. Before uninstalling an older test build, record the public Bitcoin payout address and the pool host, port, worker/thread count, charging option, and battery/temperature limits. Never record or enter a seed phrase or private key. Uninstall the older test APK only when those settings can be re-entered, then install the newly verified debug APK.

The release APK remains unsigned until all four protected release-signing secrets are configured. A successful release APK assembly is not a production signing pass. Production updates must use the same privately controlled release keystore for every version; never commit the keystore or passwords.


## Local hashrate and pool-share accounting (v1.2; retained in 1.3.0)

The mining dashboard reports separate measurements so the local phone rate is not confused with pool-side averages:
- **Local current rate** is an exponential moving average of actual native scan throughput, refreshed by the policy loop about every two seconds.
- **Local rolling average** is derived from cumulative native hashes and a monotonic clock over the observed portion of a five-minute window. During the first five minutes, the measured window is shorter and its duration is shown.
- Compare the local rolling average with the pool's five-minute rate, not with the two-second local EMA or the pool's one-hour/day/week averages. Short-window differences are expected because the time windows differ and the pool applies its own accounting/reporting.
- The app's accepted/rejected/submitted/pending/unknown counters are for the current app mining session and reset when a new mining session starts. The CKPool web page's Total Shares is address-wide/cumulative and is not expected to match a single app session.
- CKPool Worker Count describes a pool-side worker identity/connection, not the Android CPU thread count. One pool worker can receive shares generated by several local CPU threads.
- Pending share responses are reconciled from submitted shares minus accepted, rejected and unknown outcomes. Queue-full submissions are shown separately as not submitted.

## Adaptive native scan batches (v1.2; retained in 1.3.0)

Each Android CPU worker tunes its contiguous native SHA-256d scan batch using measured scan-call duration. Repeated fast samples can grow the batch up to 65,536 nonces; repeated slow samples reduce it to a minimum of 8,192. Normal timing samples do not cause oscillation. This changes only how much contiguous work is passed to each JNI call; it does not skip nonce ranges or change Bitcoin SHA-256d proof-of-work. It is a measured overhead/stop-responsiveness trade-off, not a guaranteed hashrate increase. Real-device A/B and sustained thermal measurements are still required.

## Adaptive mining controller (v1)

The existing Bitcoin SHA-256d native engine is preserved. Once a real Stratum job is authorized, the controller starts one worker and can add at most one worker every 2 seconds (an 8-worker target takes about 14 seconds from the first eligible worker), while still respecting the configured thread count and live safety limits. Battery/thermal safety reductions and pauses apply immediately; missing telemetry keeps the existing fail-safe limits. This short ramp improves startup responsiveness but does not increase the phone's underlying SHA-256d capability or guarantee higher hashes per watt.

Only SHA-256d is executable. VerusHash, RandomX, Scrypt, and GhostRider remain disabled research entries until their native implementations, pool protocols, independent known-answer tests, licensing review, and device benchmarks are complete. The app must never switch to an algorithm that is only listed in the registry.

Profitability estimates currently support Bitcoin solo expected value only. The estimator rejects the proportional-pool mode unless pool-specific hashrate and payout data are available; network-wide Bitcoin data must not be misrepresented as a pool payout estimate. Android battery temperature and percentage are not treated as measured wall power. A real net-profit decision requires an external meter's incremental mining watts, the user's actual electricity tariff, a fresh market/network snapshot, and the applicable pool fee.

Automatic multi-algorithm selection and automatic profitability-based switching remain disabled until at least two independent native engines and their matching pool/economic adapters have passed device-level verification.


## Native SHA-256d runtime self-test (v1.4.0)

Before selecting the ARMv8 SHA2 implementation, startup validates the candidate compressor against the Bitcoin genesis header and four independent SHA-256d known-answer vectors. If the ARM candidate fails any vector, the engine keeps the scalar implementation rather than selecting a compressor with an unverified result. These checks run at initialization, not inside the nonce-scanning hot loop.

The local native C test suite also verifies target inclusivity, target-minus-one rejection, nonce boundary wrap, best-hash ordering, and bounded result-buffer overflow accounting. Host CI runs the scalar implementation; the ARM candidate is checked on-device during native initialization because host CI cannot execute the phone's ARM crypto instructions.

## Repeated worker-scaling benchmark (v1.3.0)

The benchmark compares worker counts in a randomized order, warms each candidate once, records repeated monotonic-time samples, and uses the median rather than the single highest sample. The recommendation chooses the lowest worker count retaining 98.5% of peak median throughput. The worker threads are prepared before timing begins; each worker scans disjoint nonce lanes using a fixed-size chunk and wraps the nonce modulo 2^32. A zero target ensures this diagnostic benchmark does not generate pool shares.

The recommendation is explicitly throughput-based. The app does not claim it measures energy efficiency: battery percentage and battery temperature are not calibrated wall-power or CPU-junction sensors. The benchmark refuses to run when battery temperature is unavailable or too close to the configured limit; it re-checks battery temperature during the run, aborting if the safety margin is lost. Android thermal MODERATE or higher aborts the run, and unavailable Android thermal status caps the benchmark at one worker. When the device exposes manufacturer-defined thermal headroom thresholds, the benchmark also honours the 10-second headroom forecast and stops if the corresponding worker limit is reached. Missing headroom/threshold data is reported as unavailable and never replaced with a guessed value. A 30–60 minute real-device test is still required before adopting a long-run worker setting. The benchmark does not run while mining is active and never starts mining automatically.


## Battery-side energy telemetry (v1.5.0)

During a mining session, the app samples Android `BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE` and falls back to `CURRENT_NOW` only if the averaged reading is unavailable. It combines signed battery current with the battery voltage broadcast to estimate net battery-side power. Positive current/power means net charging; negative means net discharging, per the Android API contract.

The app integrates only contiguous samples where both endpoints show battery discharge, using monotonic time and a maximum 15-second sample gap. Unsupported/invalid current or voltage readings are shown as N/A and break the integration chain; missing telemetry is never replaced with zero. The discharge estimate resets with each new mining session.

This is not USB input power, wall power, whole-device consumption, or mining-only power. Fuel-gauge availability, averaging period, and calibration vary by device. Use an external USB power meter when entering watts for electricity-cost estimation. This estimate must not be used as a safety limit or profitability input.

## Sustained performance and thermal headroom (v1.6.0)

During an actual mining session, the dashboard records both the existing five-minute rolling hashrate and a thirty-minute rolling hashrate. Both are calculated from cumulative hashes returned by native nonce scans and monotonic elapsed time; the thirty-minute display grows only as real session time is observed. It does not fill missing history or forecast future hashrate. The 30-minute window retains up to 901 samples at the approximately two-second policy interval.

On Android 11/API 30 and later, the miner samples `PowerManager.getThermalHeadroom(10)` at most once every ten seconds. On Android 15/API 35 and later, it also reads the manufacturer's thermal-headroom thresholds. Worker reductions based on the headroom forecast are enabled only when the device supplies usable thresholds. The forecast is compared with the matching device-provided LIGHT/MODERATE/SEVERE thresholds; it is never interpreted using a universal guessed threshold. Existing battery-temperature and Android thermal-status safeguards remain in force. Devices without the API or threshold data continue using those existing safeguards and display headroom as unavailable or informational.

The worker benchmark now tests every integer worker count from 1 through the safe maximum, with three randomized measured rounds after one warm-up per candidate. It still runs only while mining is stopped, never opens a pool connection, uses a zero target, and does not submit shares. The recommendation remains the lowest worker count retaining at least 98.5% of peak median throughput. It is not an energy-efficiency claim.

The 30-minute rolling reading and headroom forecast are live telemetry, not proof that a 30–60 minute physical-device test has been completed. Validate sustained hashrate, thermal behaviour, battery drain, accepted/rejected pool shares and reconnect behaviour on the target phone before treating a configuration as device-verified.

