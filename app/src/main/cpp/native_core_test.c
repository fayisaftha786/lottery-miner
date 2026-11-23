#include "miner_core.h"
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static void hex2bin(const char *hex, uint8_t *out, size_t n) {
    for (size_t i = 0; i < n; i++) {
        unsigned hi = (unsigned)(hex[2 * i] <= '9' ? hex[2 * i] - '0' : hex[2 * i] - 'a' + 10);
        unsigned lo = (unsigned)(hex[2 * i + 1] <= '9' ? hex[2 * i + 1] - '0' : hex[2 * i + 1] - 'a' + 10);
        out[i] = (uint8_t)((hi << 4) | lo);
    }
}


static void assert_dual_scanner_matches_single(const uint8_t header[80],
                                               uint32_t start, uint32_t count,
                                               const uint8_t target[32]) {
    uint32_t single_found[300], dual_found[300];
    uint32_t single_best_nonce = 0, dual_best_nonce = 0;
    uint32_t single_overflow = 0, dual_overflow = 0;
    double single_best_diff = 0.0, dual_best_diff = 0.0;
    assert(count <= 300);
    const uint32_t single_count = miner_scan(header, start, count, target,
        single_found, 300, &single_best_diff, &single_best_nonce, &single_overflow);
    const uint32_t dual_count = miner_scan_dual(header, start, count, target,
        dual_found, 300, &dual_best_diff, &dual_best_nonce, &dual_overflow);
    assert(single_count == dual_count);
    assert(single_overflow == dual_overflow);
    assert(single_best_nonce == dual_best_nonce);
    assert(single_best_diff == dual_best_diff);
    assert(memcmp(single_found, dual_found, single_count * sizeof(uint32_t)) == 0);
}

static int digest_numeric_less(const uint8_t left_internal[32], const uint8_t right_internal[32]) {
    /* SHA256d output is internal little-endian uint256; compare from the top byte. */
    for (int i = 31; i >= 0; i--) {
        if (left_internal[i] < right_internal[i]) return 1;
        if (left_internal[i] > right_internal[i]) return 0;
    }
    return 0;
}

static uint32_t independently_find_best_nonce(const uint8_t header_template[80],
                                               uint32_t start, uint32_t count) {
    uint8_t header[80], digest[32], best_digest[32];
    uint32_t best_nonce = start;
    int have_best = 0;
    for (uint32_t i = 0; i < count; i++) {
        const uint32_t nonce = start + i;
        memcpy(header, header_template, sizeof(header));
        header[76] = (uint8_t)nonce;
        header[77] = (uint8_t)(nonce >> 8);
        header[78] = (uint8_t)(nonce >> 16);
        header[79] = (uint8_t)(nonce >> 24);
        miner_sha256d_header(header, digest);
        if (!have_best || digest_numeric_less(digest, best_digest)) {
            memcpy(best_digest, digest, sizeof(best_digest));
            best_nonce = nonce;
            have_best = 1;
        }
    }
    assert(have_best);
    return best_nonce;
}

int main(void) {
    miner_core_init();
    assert(miner_core_selftest_ok() == 1);

    /* Exercise nonce pairing and result accounting through the host scalar reference. */
    uint8_t pair_header[80], pair_target[32];
    for (uint32_t trial = 0; trial < 6; trial++) {
        for (uint32_t i = 0; i < sizeof(pair_header); i++)
            pair_header[i] = (uint8_t)(i * 19u + trial * 43u);
        if (trial % 3u == 0u) memset(pair_target, 0xff, sizeof(pair_target));
        else if (trial % 3u == 1u) memset(pair_target, 0, sizeof(pair_target));
        else for (uint32_t i = 0; i < sizeof(pair_target); i++)
            pair_target[i] = (uint8_t)(i * 13u + trial * 17u);
        assert_dual_scanner_matches_single(pair_header,
            UINT32_MAX - 80u + trial, 257u - (trial % 2u), pair_target);
    }


    /* Independently verify the winning nonce using byte-wise full 256-bit hash ordering. */
    for (uint32_t trial = 0; trial < 12; trial++) {
        uint8_t best_header[80], best_target[32];
        for (uint32_t i = 0; i < sizeof(best_header); i++)
            best_header[i] = (uint8_t)(i * 31u + trial * 59u);
        memset(best_target, 0, sizeof(best_target)); /* No hits; still track best hash. */
        const uint32_t best_start = UINT32_MAX - 100u + trial;
        const uint32_t best_length = 257u;
        const uint32_t expected_best = independently_find_best_nonce(best_header, best_start, best_length);
        uint32_t best_found[1], single_best_nonce = 0, dual_best_nonce = 0;
        uint32_t single_overflow = 0, dual_overflow = 0;
        double single_best_diff = 0.0, dual_best_diff = 0.0;
        assert(miner_scan(best_header, best_start, best_length, best_target, best_found, 1,
            &single_best_diff, &single_best_nonce, &single_overflow) == 0);
        assert(miner_scan_dual(best_header, best_start, best_length, best_target, best_found, 1,
            &dual_best_diff, &dual_best_nonce, &dual_overflow) == 0);
        assert(single_best_nonce == expected_best);
        assert(dual_best_nonce == expected_best);
        assert(single_best_diff == dual_best_diff);
        assert(single_overflow == 0 && dual_overflow == 0);
    }

    /* A candidate hash must pass at equality and fail with target-minus-one. */
    for (uint32_t i = 0; i < sizeof(pair_header); i++)
        pair_header[i] = (uint8_t)(i * 23u + 9u);
    const uint32_t exact_nonce = 0x1235u;
    pair_header[76] = (uint8_t)exact_nonce;
    pair_header[77] = (uint8_t)(exact_nonce >> 8);
    pair_header[78] = (uint8_t)(exact_nonce >> 16);
    pair_header[79] = (uint8_t)(exact_nonce >> 24);
    uint8_t pair_digest[32];
    miner_sha256d_header(pair_header, pair_digest);
    for (uint32_t i = 0; i < 32; i++) pair_target[i] = pair_digest[31u - i];
    assert_dual_scanner_matches_single(pair_header, exact_nonce - 8u, 17u, pair_target);
    uint32_t exact_found[300], exact_best_nonce = 0, exact_overflow = 0;
    double exact_best_diff = 0.0;
    uint32_t exact_count = miner_scan_dual(pair_header, exact_nonce - 8u, 17u,
        pair_target, exact_found, 300, &exact_best_diff, &exact_best_nonce, &exact_overflow);
    int exact_nonce_accepted = 0;
    for (uint32_t i = 0; i < exact_count; i++)
        if (exact_found[i] == exact_nonce) exact_nonce_accepted = 1;
    assert(exact_nonce_accepted);
    for (int i = 31; i >= 0; i--) {
        if (pair_target[i] != 0) { pair_target[i]--; break; }
        pair_target[i] = 0xff;
    }
    assert_dual_scanner_matches_single(pair_header, exact_nonce - 8u, 17u, pair_target);
    exact_count = miner_scan_dual(pair_header, exact_nonce - 8u, 17u,
        pair_target, exact_found, 300, &exact_best_diff, &exact_best_nonce, &exact_overflow);
    exact_nonce_accepted = 0;
    for (uint32_t i = 0; i < exact_count; i++)
        if (exact_found[i] == exact_nonce) exact_nonce_accepted = 1;
    assert(!exact_nonce_accepted);

#ifndef HAVE_ARM_SHA2
    /* The host uses two scalar compression calls to test dual-scanner bookkeeping.
       It must not report that the ARM hardware implementation passed its self-test. */
    assert(miner_core_dual_selftest_ok() == 0);
#endif

    uint8_t header[80] = {0};
    uint8_t target[32];
    memset(target, 0xff, sizeof(target));

    uint32_t found[2] = {0};
    uint32_t best_nonce = 0;
    uint32_t overflow = 0;
    double best_diff = 0.0;

    uint32_t stored = miner_scan(header, 0, 10, target, found, 1,
                                 &best_diff, &best_nonce, &overflow);
    assert(stored == 1);
    assert(overflow == 9);
    assert(best_diff > 0.0);

    /* Verify inclusive hash <= target at the exact Bitcoin genesis hash. */
    static const char *genesis_header_hex =
        "01000000"
        "0000000000000000000000000000000000000000000000000000000000000000"
        "3ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a"
        "29ab5f49"
        "ffff001d"
        "1dac2b7c";
    hex2bin(genesis_header_hex, header, sizeof(header));
    uint8_t digest_le[32];
    uint8_t exact_target[32];
    miner_sha256d_header(header, digest_le);
    for (size_t i = 0; i < 32; i++) exact_target[i] = digest_le[31 - i];

    uint32_t genesis_nonce = 0;
    stored = miner_scan(header, 2083236893u, 1, exact_target, &genesis_nonce, 1,
                        &best_diff, &best_nonce, &overflow);
    assert(stored == 1);
    assert(genesis_nonce == 2083236893u);

    /*
     * Independent SHA-256d known-answer vectors generated with Python hashlib
     * (hashlib.sha256(hashlib.sha256(header).digest()).digest()).
     * These expected digests are fixed external vectors, not values derived
     * from miner_sha256d_header(), so they detect a shared hashing defect.
     */
    static const struct {
        const char *header_hex;
        const char *digest_hex;
    } independent_vectors[] = {
        {
            "00254a6f94b9de03284d7297bce1062b50759abfe4092e53789dc2e70c31567b"
            "a0c5ea0f34597ea3c8ed12375c81a6cbf0153a5f84a9cef3183d6287acd1f61b"
            "40658aafd4f91e43688db2d740302010",
            "739f31ec63e5b01cd933fcb42f27ece2663dd02009b9b1e0a2a809f02ffb93c2"
        },
        {
            "355a7fa4c9ee13385d82a7ccf1163b6085aacff4193e6388add2f71c41668bb0"
            "d5fa1f44698eb3d8fd22476c91b6db00254a6f94b9de03284d7297bce1062b50"
            "759abfe4092e53789dc2e70c41312111",
            "446f8c36675dcba95c9a4ea2d07b19ed400871034f1a30182597056ed6c2b66b"
        },
        {
            "6a8fb4d9fe23486d92b7dc01264b7095badf04294e7398bde2072c51769bc0e5"
            "0a2f54799ec3e80d32577ca1c6eb10355a7fa4c9ee13385d82a7ccf1163b6085"
            "aacff4193e6388add2f71c4142322212",
            "c0408d345262a2e27f6de904a895afb464127d3f65b6426821a6a4556ec10153"
        },
        {
            "9fc4e90e33587da2c7ec11365b80a5caef14395e83a8cdf2173c6186abd0f51a"
            "3f6489aed3f81d42678cb1d6fb20456a8fb4d9fe23486d92b7dc01264b7095ba"
            "df04294e7398bde2072c517643332313",
            "5f77e202d08675a2ff8d72ec616e90570ba97ad9074dec54c72a1b4eac34f01f"
        }
    };
    for (size_t v = 0; v < sizeof(independent_vectors) / sizeof(independent_vectors[0]); v++) {
        hex2bin(independent_vectors[v].header_hex, header, sizeof(header));
        uint8_t expected_digest[32];
        hex2bin(independent_vectors[v].digest_hex, expected_digest, sizeof(expected_digest));
        miner_sha256d_header(header, digest_le);
        assert(memcmp(digest_le, expected_digest, sizeof(expected_digest)) == 0);
    }

    /* A target one unit below the hash must reject the same nonce. */
    for (int i = 31; i >= 0; i--) {
        if (exact_target[i] != 0) {
            exact_target[i]--;
            break;
        }
        exact_target[i] = 0xff;
    }
    stored = miner_scan(header, 2083236893u, 1, exact_target, &genesis_nonce, 1,
                        &best_diff, &best_nonce, &overflow);
    assert(stored == 0);

    /*
     * Exercise the optimized word comparison with several unrelated digest
     * patterns. An exact digest target must pass; target-1 must fail.
     */
    for (uint32_t trial = 0; trial < 4; trial++) {
        for (size_t i = 0; i < sizeof(header); i++) {
            header[i] = (uint8_t)(i * 37u + trial * 53u);
        }
        const uint32_t nonce = 0x10203040u + trial * 0x01010101u;
        header[76] = (uint8_t)nonce;
        header[77] = (uint8_t)(nonce >> 8);
        header[78] = (uint8_t)(nonce >> 16);
        header[79] = (uint8_t)(nonce >> 24);

        miner_sha256d_header(header, digest_le);
        for (size_t i = 0; i < 32; i++) exact_target[i] = digest_le[31 - i];

        stored = miner_scan(header, nonce, 1, exact_target, &genesis_nonce, 1,
                            &best_diff, &best_nonce, &overflow);
        assert(stored == 1 && genesis_nonce == nonce);

        int decremented = 0;
        for (int i = 31; i >= 0; i--) {
            if (exact_target[i] != 0) {
                exact_target[i]--;
                decremented = 1;
                break;
            }
            exact_target[i] = 0xff;
        }
        assert(decremented);
        stored = miner_scan(header, nonce, 1, exact_target, &genesis_nonce, 1,
                            &best_diff, &best_nonce, &overflow);
        assert(stored == 0);
    }

    /* Nonce range wraps modulo 2^32 and visits both boundary values. */
    memset(target, 0xff, sizeof(target));
    stored = miner_scan(header, UINT32_MAX, 2, target, found, 2,
                        &best_diff, &best_nonce, &overflow);
    assert(stored == 2);
    assert(found[0] == UINT32_MAX);
    assert(found[1] == 0);

    /* The dual scanner must preserve bounded result-buffer overflow accounting. */
    memset(target, 0xff, sizeof(target));
    uint32_t dual_found_small[1], dual_overflow = 0, dual_best_nonce = 0;
    double dual_best_diff = 0.0;
    uint32_t dual_stored = miner_scan_dual(header, 0xfffffff0u, 10u, target,
        dual_found_small, 1u, &dual_best_diff, &dual_best_nonce, &dual_overflow);
    assert(dual_stored == 1u);
    assert(dual_overflow == 9u);
    assert(dual_found_small[0] == 0xfffffff0u);

    puts("native SHA256d self-test, inclusive target boundary, nonce wrap, and bounded-hit accounting passed");
    return 0;
}
