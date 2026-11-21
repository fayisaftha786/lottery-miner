# Real-Only Bitcoin Mining Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden Lottery Miner so every production mining metric and pool status is backed by real native hashing, actual Stratum messages, or Android measurements, with no fabricated mining data.

**Architecture:** Retain the existing Kotlin Android service, Stratum client, JNI bridge, and native SHA-256d engine. Harden protocol/job lifecycle and native correctness first, then safety/UI truth, then CI and release signing. Keep each subsystem isolated behind its current interfaces unless a focused interface change is required.

**Tech Stack:** Kotlin, Android SDK/Gradle, JNI, C11, CMake, JUnit, GitHub Actions, CKPool Stratum v1.

**Spec:** docs/superpowers/specs/2026-11-20-real-only-mining-design.md

## Global Constraints
- Production must not expose simulation/demo mining or fabricated telemetry.
- Hashrate and hashes must derive only from actual native scan counters and monotonic elapsed time.
- Pool data must derive only from validated live Stratum messages.
- A submitted share is not accepted until its matching pool response arrives.
- A local candidate or pool-accepted share must never be represented as a confirmed Bitcoin block.
- Unknown temperature or unavailable pool data must remain explicitly unavailable and trigger safe behavior where specified.
- Work only on feature/real-mining-ultra; do not merge or publish without explicit approval.
- No production release may be described as production-signed while it uses the debug key.

## Review Focus
- Malformed and oversized Stratum messages: test bounded parsing and safe rejection without state corruption.
- Server extranonce changes and extranonce2 exhaustion: test invalidation, uniqueness, and safe reconnect/rotation.
- Job replacement while workers are scanning: test that old-generation work cannot be submitted.
- Unknown battery/thermal readings: test conservative worker cap/pause and N/A presentation.
- JNI callers supplying short arrays or invalid lengths: test defensive failure without native memory access.

---

### Task 1: Stratum message validation and extranonce updates

**Files:**
- Modify: `app/src/main/java/com/lottery/miner/StratumClient.kt`
- Modify: `app/src/main/java/com/lottery/miner/Job.kt`
- Test: `app/src/test/java/com/lottery/miner/StratumClientTest.kt`
- Test: `app/src/test/java/com/lottery/miner/JobTest.kt` (create if absent)

**Interfaces:**
- Consumes: existing StratumClient.Listener callbacks and Job construction API.
- Produces: validated job parsing and a listener callback for changed extranonce parameters; preserve existing public APIs where possible.

- [ ] **Step 1: Add failing tests** for valid `mining.set_extranonce`, invalid extranonce hex, extranonce2 size outside 1..16, malformed notify fields, malformed merkle branches, and overlong input lines.
- [ ] **Step 2: Run the focused JVM tests** and verify the new cases fail for the intended missing validation/handler behavior.
- [ ] **Step 3: Implement bounded parsing and validation** in StratumClient.kt and Job.kt. Apply changed extranonce parameters atomically and invalidate old work.
- [ ] **Step 4: Run focused tests** and verify valid protocol messages pass while malformed messages are rejected without crashes or bogus callbacks.
- [ ] **Step 5: Commit** with message `fix: validate stratum jobs and extranonce updates`.

### Task 2: Job generations and reliable share accounting

**Files:**
- Modify: `app/src/main/java/com/lottery/miner/MinerEngine.kt`
- Modify: `app/src/main/java/com/lottery/miner/StratumClient.kt`
- Modify: `app/src/main/java/com/lottery/miner/MinerState.kt`
- Test: `app/src/test/java/com/lottery/miner/StratumClientTest.kt`
- Test: focused MinerEngine tests (create using existing test conventions)

**Interfaces:**
- Consumes: Task 1 validated job/extranonce callback.
- Produces: monotonically increasing job generation and explicit pending/accepted/rejected/unknown share accounting.

- [ ] **Step 1: Add failing tests** for clean-job replacement, stale worker result, matching submit response, duplicate/unknown response ID, pending submit disconnect, and bounded pending queue.
- [ ] **Step 2: Run focused tests** and verify stale submissions and incorrect accounting are reproduced.
- [ ] **Step 3: Implement generation checks** immediately before submission; track pending IDs with bounded metadata and mark unresolved submissions unknown on disconnect.
- [ ] **Step 4: Run focused tests** and verify only matching pool responses change accepted/rejected counters.
- [ ] **Step 5: Commit** with message `fix: prevent stale shares and track pool responses accurately`.

### Task 3: Native hashing correctness and JNI hardening

**Files:**
- Modify: `app/src/main/cpp/miner_core.c`
- Modify: `app/src/main/cpp/jni_bridge.c`
- Modify: `app/src/main/cpp/miner_core.h`
- Test: native test sources/CMake configuration (create using repository conventions)
- Test: JVM/native integration tests where supported

**Interfaces:**
- Consumes: existing JNI scan API and native result structures.
- Produces: exact hash-attempt count, complete 256-bit best-share comparison, safe bounded result reporting, and defensive JNI argument validation.

- [ ] **Step 1: Add failing native tests** for independent SHA256d known vectors, serialized header bytes, target equality/boundaries, nonce range boundaries, full-width best-hash ordering, and more hits than the result buffer capacity.
- [ ] **Step 2: Run native tests** and verify failures expose the targeted edge cases.
- [ ] **Step 3: Correct full-width comparison and hit counting**; add JNI null/length/exception checks before array access.
- [ ] **Step 4: Run native tests and Android native build** for arm64-v8a and x86_64; verify Genesis self-test and all vectors pass.
- [ ] **Step 5: Commit** with message `fix: harden native sha256d scanning and jni boundaries`.

### Task 4: Thermal policy and worker lifecycle safety

**Files:**
- Modify: `app/src/main/java/com/lottery/miner/MinerEngine.kt`
- Modify: `app/src/main/java/com/lottery/miner/MinerService.kt`
- Modify: `app/src/main/java/com/lottery/miner/MainActivity.kt`
- Modify: relevant preferences/layout XML
- Test: focused policy and lifecycle JVM tests

**Interfaces:**
- Consumes: existing MinerService and worker start/stop APIs.
- Produces: conservative policy for unknown temperature, consistent charging-only default, bounded thread shutdown/join, and no overlapping worker generations.

- [ ] **Step 1: Add failing tests** for missing battery temperature, unsafe temperature, thermal status throttling, first-run charging-only default, and stop/restart without overlapping workers.
- [ ] **Step 2: Run focused tests** and verify current unsafe defaults/races are reproduced.
- [ ] **Step 3: Implement fail-safe thermal policy** and deterministic worker shutdown. Keep configured worker count as a maximum, not an instruction to bypass safety.
- [ ] **Step 4: Run focused tests** and verify unknown sensor state never behaves like 0°C and old workers are fully stopped before restart.
- [ ] **Step 5: Review foreground service type/target SDK behavior** against current Android requirements; change only with a tested, policy-compliant service declaration.
- [ ] **Step 6: Commit** with message `fix: enforce safe thermal policy and worker lifecycle`.

### Task 5: Truthful mining state and UI

**Files:**
- Modify: `app/src/main/java/com/lottery/miner/MinerState.kt`
- Modify: `app/src/main/java/com/lottery/miner/MainActivity.kt`
- Modify: relevant layout and string resources
- Test: UI/state mapping tests

**Interfaces:**
- Consumes: validated pool state and native counters from Tasks 1-4.
- Produces: clear local-vs-pool metric labels and explicit candidate/submitted/accepted/rejected/confirmed states.

- [ ] **Step 1: Add failing state/UI tests** for disconnected state, unavailable values, candidate detection, submitted candidate, pool acceptance/rejection, and confirmation only from an authoritative source.
- [ ] **Step 2: Run focused tests** and verify the existing ambiguous block-found presentation is caught.
- [ ] **Step 3: Implement truthful state mapping**; remove or relabel any unsupported earnings/payout or block-confirmation claims.
- [ ] **Step 4: Run focused tests** and inspect rendered UI resources for N/A and local/pool labels.
- [ ] **Step 5: Commit** with message `feat: show only verified mining and pool status`.

### Task 6: CI quality gates and release signing

**Files:**
- Modify: `.github/workflows/build.yml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Modify: README/release documentation

**Interfaces:**
- Consumes: completed JVM/native test suites and current Gradle build tasks.
- Produces: test/lint/native/release build gates and protected release signing configuration.

- [ ] **Step 1: Inspect current workflow and Gradle signing configuration**; identify exact existing task names and available secret names without exposing secret values.
- [ ] **Step 2: Add CI validation** so JVM tests, lint, native compilation, and release APK build must pass before artifact/release publication.
- [ ] **Step 3: Remove debug-only release signing**; configure protected secrets and fail closed when a production release is requested without signing material. Debug builds remain available for local testing.
- [ ] **Step 4: Remove unsafe CMake flags** such as disabling stack protection; keep optimization/configuration appropriate to build type.
- [ ] **Step 5: Run the full CI-equivalent command set** and verify all checks pass; verify unsigned/debug artifacts are never labeled production.
- [ ] **Step 6: Commit** with message `ci: gate releases on tests and secure signing`.

### Task 7: End-to-end verification and handoff

**Files:**
- Modify: `README.md`
- Create: `docs/superpowers/verification/real-mining-checklist.md`

**Interfaces:**
- Consumes: all completed tasks and their test evidence.
- Produces: reproducible device/pool validation checklist and honest release readiness statement.

- [ ] **Step 1: Document device smoke test** for real CKPool connect/authorize/job/difficulty and pool-side accepted-share verification.
- [ ] **Step 2: Document soak test** covering temperature, battery, service restart, network interruption, and stale-job transitions.
- [ ] **Step 3: Run all repository tests, lint, native builds, and release build from a clean checkout**; record exact commands and results.
- [ ] **Step 4: Review the entire branch** against the design acceptance criteria; record any deferred items and evidence gaps.
- [ ] **Step 5: Commit** with message `docs: add real mining verification checklist`.

## Execution Notes
- Follow test-driven development: each behavior change starts with a test observed failing, then a minimal implementation, then a passing focused test.
- Run the full task-level verification after each task; do not infer success from code inspection.
- Use separate commits per task for reviewability.
- Live CKPool acceptance, device thermal stability, and actual payout cannot be proven by source tests alone; report them as unverified until tested on a real device and confirmed from pool records.
- Do not request or commit wallet private keys, pool passwords, or signing secrets. The user's public BTC payout address may be needed only for the live pool smoke test and should be handled through app configuration, not repository source.