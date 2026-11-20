# Real Mining Verification Checklist

Use this checklist on a physical Android device with a user-controlled Bitcoin payout address and a real CKPool Stratum endpoint. Do not treat mock-pool tests as evidence of live mining.

## Before install
- [ ] Confirm the APK commit SHA matches the intended branch build.
- [ ] If the APK is intended for distribution, verify its signature with Android `apksigner`; reject unsigned/debug-signed artifacts.
- [ ] Confirm the configured pool hostname and port with the pool operator; do not pin an IP address.
- [ ] Enter only the public Bitcoin payout address in the app. Never enter a seed phrase or private key.
- [ ] Start with charging-only enabled, one worker, conservative temperature limit, and a well-ventilated device.

## Live Stratum smoke test
- [ ] App reports connecting, then authorized only after a successful `mining.authorize` response.
- [ ] App receives a real `mining.notify` job and valid pool difficulty notification.
- [ ] Verify server `mining.set_extranonce` updates are accepted and the current job is invalidated until a new job arrives.
- [ ] Disconnect network: app must show disconnected/reconnecting, stop claiming pool-connected state, and must not fabricate pool difficulty or shares.
- [ ] Reconnect network: app must subscribe/authorize again and resume only after receiving a new job.
- [ ] Trigger a pool response to a submitted share and verify the matching accepted/rejected counter changes only after that response.
- [ ] Disconnect with a share pending: it must be counted as unknown, never accepted.
- [ ] Cross-check any accepted share with the pool's authoritative worker/statistics page.

## Hashing and telemetry
- [ ] Total hashes increase only while native scan calls are executing.
- [ ] Hashrate is derived from native hash-counter deltas over monotonic elapsed time.
- [ ] Stop mining: hashrate decays to zero and total hashes stop increasing.
- [ ] Pool difficulty and network difficulty show N/A until valid live values arrive.
- [ ] Missing battery telemetry pauses mining; missing battery temperature limits operation to one worker.
- [ ] Android thermal status reduces or pauses workers as configured.
- [ ] Confirm local candidate detection is labeled as a candidate only; no block-confirmed message appears without authoritative confirmation.

## Reliability soak test
- [ ] Run for at least 60 minutes with device temperature, battery, and app status monitored.
- [ ] Confirm worker count never exceeds the configured maximum and decreases when thermal policy requires.
- [ ] Test app stop/restart and verify no duplicate workers continue hashing.
- [ ] Test temporary Wi-Fi/mobile network loss and verify reconnect/backoff behavior.
- [ ] Verify old-job work is not submitted after a clean job replacement.
- [ ] Check logs for malformed protocol messages, JNI errors, worker leaks, and repeated reconnect loops.

## Release gate
- [ ] Host native CTest passes.
- [ ] JVM unit tests pass.
- [ ] Android lint passes.
- [ ] Android native libraries build for arm64-v8a and x86_64.
- [ ] Release APK assembles successfully.
- [ ] Production APK is signed with protected release credentials and `apksigner verify` passes.
- [ ] Device smoke test and pool-side accepted-share verification are recorded separately from CI results.

**Evidence boundary:** CI can prove source-level tests and build results only. It cannot prove the phone's real-world hashrate, sustained thermal safety, pool-side acceptance, payout, or block discovery.