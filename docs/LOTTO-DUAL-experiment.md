# LOTTO-DUAL experimental SHA256d scanner

## Purpose

LOTTO-DUAL is an experimental two-stream ARMv8 SHA-256 compression path for Bitcoin SHA256d proof-of-work. It does **not** change Bitcoin's proof-of-work algorithm or target rules. It computes the exact same double-SHA256 result for each 80-byte block header, with two independent nonce values processed in interleaved ARM SHA2 instruction streams.

The experiment seeks a possible throughput improvement from exposing independent SHA-256 work to the CPU pipeline. It is not a cryptographic shortcut and does not improve the probability per hash.

## Current implementation

- The existing single-stream ARM/scalar scanner remains the production mining path.
- The dual scanner computes the immutable first-64-byte header midstate once.
- Each lane then builds its own nonce-dependent SHA-256 block.
- The two first-round compression states are processed with `compress_arm_dual`.
- Each lane's resulting digest is used as the message to its own second SHA-256 block; those blocks are also compressed in parallel.
- Hashes are compared against the same big-endian target with the same inclusive `hash <= target` rule as the existing scanner.
- Results are returned in nonce-ascending order. Odd nonce counts use one valid final lane and discard the duplicate lane's output.
- The dual scanner returns unavailable unless ARM SHA2 hardware is present and the differential self-test passes.
- Mining does not automatically select the dual scanner. It is accessible only through the stopped-miner A/B benchmark.

## Correctness checks

On a SHA2-capable ARM64 device, native initialization compares both streams against the scalar compression implementation and then compares complete single- and dual-scanner results for:
- a 257-nonce all-target scan crossing the 32-bit nonce wrap boundary (odd count and result ordering);
- an exact hash target (inclusive boundary);
- the same target decremented by one;
- a zero target (no practical hit).

The normal host native tests also confirm that non-ARM builds report the dual scanner as unavailable rather than pretending to run it.

## Device benchmark

The app's **Compare LOTTO-DUAL experimental scanner** button:
- refuses to run while mining is active;
- refuses to run while Android reports MODERATE or higher thermal status;
- performs equal warm-up runs, then three 1-second samples per scanner in alternating order;
- uses the same deterministic synthetic 80-byte header and a zero target;
- performs no Stratum connection and submits no shares;
- reports median single-lane and dual-lane hashes per second and percentage change.

This short A/B test is only a screening measurement. It is not an estimate of pool hashrate, payout, or block probability.

## Production acceptance gate

Do not route mining work through LOTTO-DUAL unless all of the following have been demonstrated on the target device:
1. Differential self-tests pass on the actual ARM64 device.
2. Repeated A/B tests show at least 5% median end-to-end SHA256d improvement.
3. A 30-60 minute sustained test shows the improvement remains after thermal equilibrium.
4. Power is measured with an external USB power meter; hashes per joule do not regress materially.
5. Live pool integration produces the same accepted/rejected share behaviour as the existing scanner.

A CI build only proves source compilation, test execution on the CI host, and APK packaging. It does not prove that the device's ARM SHA2 dual path executed or that it is faster. Until the device A/B and sustained tests are reported, LOTTO-DUAL remains experimental and production mining remains on the existing scanner.

## Important limitation

Bitcoin block discovery remains probabilistic. Every valid nonce hash is one independent SHA256d trial; a faster scanner increases the number of trials per second but does not make any individual hash more likely to meet the network target.
