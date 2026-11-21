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

int main(void) {
    miner_core_init();
    assert(miner_core_selftest_ok() == 1);

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

    puts("native SHA256d self-test, inclusive target boundary, nonce wrap, and bounded-hit accounting passed");
    return 0;
}
