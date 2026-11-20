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

GitHub Actions runs JVM tests and builds an APK artifact. It is debug-signed for testing only; do not distribute it as a production APK. A production release requires a private signing key configured via protected CI secrets. Automated tests cannot verify live pool availability or authorization.

## Safety and limitations

- Battery temperature is reported only when Android exposes it; it is not CPU temperature. If unavailable, mining is limited to one worker thread.
- Thermal and battery safeguards reduce risk but cannot guarantee device safety. Do not bypass these limits.
- Hashrate and share counters are measurements; block probability is a rough statistical estimate, not a prediction or reward guarantee.
- Do not hard-code pool IP addresses: DNS records can change. Diagnose connection errors using the displayed log, host/port, and another device on the same network.
