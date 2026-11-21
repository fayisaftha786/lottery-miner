# Lottery Miner — Android Stratum v1 CPU miner

This experimental Android app implements a Stratum v1 TCP client and a CPU SHA-256d miner for solo Bitcoin mining. It is not a profitable replacement for dedicated ASIC hardware and does not promise Bitcoin rewards. Phone CPU mining can create heat, reduce performance, and accelerate battery wear. Use conservative settings and stop if the device gets hot.

## Pool setup

The app's configured default is `solo.ckpool.org:3333`. Pool endpoints and availability may change; verify the current hostname and port with the pool operator. The app attempts resolved IPv4 and IPv6 addresses and retries with backoff. `ECONNREFUSED` means the endpoint or network rejected the TCP connection; retry logic cannot make an unavailable or blocked server accept connections.

Use your Bitcoin mainnet address as the Stratum username. An optional worker suffix may be used if supported by the pool. Password is `x`. Never enter a seed phrase or private key.

## Build and verification

Requires JDK 17, Android SDK 34, NDK 26.1.10909125, CMake 3.22.1, and Gradle 8.7.

```bash
gradle testDebugUnitTest
gradle assembleRelease
```

GitHub Actions runs JVM unit tests and builds a release APK artifact. It is debug-signed for testing only; do not distribute it as a production APK. A production release requires a private signing key configured via protected CI secrets. Automated tests cannot verify live pool availability or authorization.

## Safety and limitations

- Battery temperature is reported only when Android exposes it; it is not CPU temperature. If unavailable, mining is limited to one worker thread.
- Thermal and battery safeguards reduce risk but cannot guarantee device safety. Do not bypass these limits.
- Hashrate and share counters are measurements; block probability is a rough statistical estimate, not a prediction or reward guarantee.
- Do not hard-code pool IP addresses: DNS records can change. Diagnose connection errors using the displayed log, host/port, and another device on the same network.

## Cloud worker status (experimental; not connected)

The app currently mines only on the Android device. It does not have cloud-provider OAuth, cloud instance provisioning, remote worker management, or multi-worker Stratum coordination. The provider registry is a policy/evidence guard only; it does not log in or start cloud workers.

Cloud auto-connect remains locked until **all** of the following are verified for a selected provider:
1. Its applicable terms expressly permit mining, or the required written approval has been obtained and recorded.
2. A genuinely free quota is explicitly verified for this mining workload (not merely a free trial or free GPU offer).
3. A supported provider provisioning API and OAuth flow are verified.
4. The user explicitly authorizes the worker and its exact resource limits.

Current official policy checks (reviewed 21 November 2026):
- Google Cloud Free Trial: Free Trial Services may not be used for cryptocurrency mining. Standard Google Cloud use requires Google's prior written approval.
- Google Colab managed runtimes: mining is disallowed on managed runtimes, including free resources.
- Kaggle: its Terms of Use expressly prohibit cryptomining.
- AWS Free Tier offers: cryptocurrency mining is prohibited; standard charges and suspension may apply. AWS Service Terms also prohibit mining.
- Oracle Cloud (India agreement): the Cloud Services Agreement prohibits crypto-currency mining.
- Azure Lab Services: Microsoft Product Terms prohibit mining in this product; this statement is not a claim about every Azure service.
- DigitalOcean: its Acceptable Use Policy prohibits cryptocurrency mining without explicit written permission. Its Startup Program has a separate mining prohibition. No free mining quota or provider OAuth/provisioning integration has been verified.

**Current verified result: zero free providers meet all mining-permission, quota, and provisioning-API checks.** The app therefore shows Cloud auto-connect as LOCKED. No cloud instance is provisioned, no provider credentials are requested, and no paid usage is triggered by this feature. Never enter a cloud password, API key, seed phrase, or wallet private key into the app.

A future cloud integration must use provider OAuth (not password collection), Android Keystore-backed token protection, user-visible quotas and spending limits, explicit start/stop controls, and a remote worker protocol with independent job/nonce allocation and verified share accounting. These components are not implemented yet.
