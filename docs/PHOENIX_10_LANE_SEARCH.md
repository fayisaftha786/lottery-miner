# PHOENIX 10-lane search design

## Purpose

The miner supports up to ten logical worker lanes. Each lane searches a disjoint subset of the pool-provided extranonce2 counter space. The pool's job template, extranonce1, extranonce2 size, version, ntime rules, and network target remain authoritative.

This is a search-space partition and traversal-order strategy. It is not a shortcut for SHA-256d and does not improve the probability of an individual hash.

## Ten lane equations

Let W be the configured lane count (1..10), i a lane index (0..W-1), Cmax the maximum representable extranonce2 counter, Mi = floor((Cmax-i)/W)+1 the lane capacity, and s the local sequence number.

Let ai be the lane's multiplier, adjusted until gcd(ai, Mi)=1, and bi=(7i+3) mod Mi.

Each lane computes:

k_i(s) = (a_i*s + b_i) mod M_i

e_i(s) = i + W*k_i(s)

The ten configured multiplier seeds are 1, 3, 5, 7, 11, 13, 17, 19, 23, and 29. The implementation adjusts each multiplier to be coprime to the actual lane capacity. Thus each lane's affine mapping is a permutation of its own residue class.

### Lane instances

W0: e0(s) = 0 + W*((a0*s+3) mod M0)
W1: e1(s) = 1 + W*((a1*s+10) mod M1)
W2: e2(s) = 2 + W*((a2*s+17) mod M2)
W3: e3(s) = 3 + W*((a3*s+24) mod M3)
W4: e4(s) = 4 + W*((a4*s+31) mod M4)
W5: e5(s) = 5 + W*((a5*s+38) mod M5)
W6: e6(s) = 6 + W*((a6*s+45) mod M6)
W7: e7(s) = 7 + W*((a7*s+52) mod M7)
W8: e8(s) = 8 + W*((a8*s+59) mod M8)
W9: e9(s) = 9 + W*((a9*s+66) mod M9)

Only lanes with i < W are active.

## Why lanes do not overlap

Every counter from lane i satisfies e_i mod W = i. Different lane indices therefore cannot produce the same counter. Since ai is coprime to Mi, the affine mapping visits every counter in that lane exactly once before exhaustion.

The native scanner then scans the full 32-bit nonce range for each distinct extranonce2 value. Each extranonce2 produces a distinct coinbase/merkle-root/header search, subject to the pool job rules.

## Probability

For N independent valid hashes and network target T:

P(at least one) = 1 - (1 - (T+1)/2^256)^N

Changing traversal order does not change this probability. More genuine, non-duplicated valid hashes increase total chance; pool shares are not block candidates.

## Safety and operational limits

- The UI permits up to ten logical lanes, but adaptive worker tuning starts conservatively and keeps only worker counts that improve measured throughput.
- Android thermal, battery, charging, and OS thermal caps remain in force.
- Ten logical threads do not imply ten physical CPU cores or a tenfold speedup.
- Exhausted extranonce allocations trigger a reconnect; a pool that reissues the same exhausted allocation is stopped rather than silently repeating work.
- The native SHA-256d target check remains the only block-candidate test.
