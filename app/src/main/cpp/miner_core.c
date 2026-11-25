#include "miner_core.h"
#include <string.h>
#include <time.h>

const uint32_t SHA256_K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};

static const uint32_t IV[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                               0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};

#define ROR(x, n) (((x) >> (n)) | ((x) << (32 - (n))))

static void compress_scalar(uint32_t st[8], const uint32_t win[16]) {
    uint32_t w[64];
    memcpy(w, win, 64);
    for (int i = 16; i < 64; i++) {
        uint32_t s0 = ROR(w[i - 15], 7) ^ ROR(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = ROR(w[i - 2], 17) ^ ROR(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    uint32_t a = st[0], b = st[1], c = st[2], d = st[3], e = st[4], f = st[5], g = st[6], h = st[7];
    for (int i = 0; i < 64; i++) {
        uint32_t S1 = ROR(e, 6) ^ ROR(e, 11) ^ ROR(e, 25);
        uint32_t ch = (e & f) ^ (~e & g);
        uint32_t t1 = h + S1 + ch + SHA256_K[i] + w[i];
        uint32_t S0 = ROR(a, 2) ^ ROR(a, 13) ^ ROR(a, 22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t t2 = S0 + maj;
        h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
    }
    st[0] += a; st[1] += b; st[2] += c; st[3] += d;
    st[4] += e; st[5] += f; st[6] += g; st[7] += h;
}

static compress_fn_t g_fn = compress_scalar;
static int g_hw = 0;
static int g_ok = 0;
static int g_arm_pair_scan = 0;

static uint32_t be32(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}

static void digest_bytes(const uint32_t d[8], uint8_t h[32]) {
    for (int j = 0; j < 8; j++) {
        h[4 * j] = (uint8_t)(d[j] >> 24);
        h[4 * j + 1] = (uint8_t)(d[j] >> 16);
        h[4 * j + 2] = (uint8_t)(d[j] >> 8);
        h[4 * j + 3] = (uint8_t)d[j];
    }
}

/*
 * The displayed Bitcoin hash is the byte-reversed SHA-256 digest. Convert
 * the digest state directly to eight big-endian numeric words in that order.
 * This avoids writing a temporary 32-byte digest for every nonce in the hot
 * scan loop. Words are compared from most-significant to least-significant.
 */
static void digest_numeric_words(const uint32_t digest[8], uint32_t words[8]) {
    for (int i = 0; i < 8; i++) words[i] = __builtin_bswap32(digest[7 - i]);
}

static void hash_with(compress_fn_t fn, const uint8_t header[80], uint8_t out[32]) {
    uint32_t w1[16], w2[16], w3[16], mid[8], s[8], d[8];
    for (int i = 0; i < 16; i++) w1[i] = be32(header + 4 * i);
    memcpy(mid, IV, 32);
    fn(mid, w1);
    w2[0] = be32(header + 64);
    w2[1] = be32(header + 68);
    w2[2] = be32(header + 72);
    w2[3] = be32(header + 76);
    w2[4] = 0x80000000u;
    for (int i = 5; i < 15; i++) w2[i] = 0;
    w2[15] = 640;
    memcpy(s, mid, 32);
    fn(s, w2);
    memcpy(w3, s, 32);
    w3[8] = 0x80000000u;
    for (int i = 9; i < 15; i++) w3[i] = 0;
    w3[15] = 256;
    memcpy(d, IV, 32);
    fn(d, w3);
    digest_bytes(d, out);
}

static uint32_t scan_impl(compress_fn_t fn, const uint8_t header[80], uint32_t start, uint32_t count,
                          const uint8_t target[32], uint32_t *found, uint32_t max_found,
                          double *best_diff, uint32_t *best_nonce, uint32_t *overflow_count) {
    const double DIFF1 = 65535.0 * 0x1p208;
    uint32_t w1[16], w2[16], w3[16], mid[8];
    for (int i = 0; i < 16; i++) w1[i] = be32(header + 4 * i);
    memcpy(mid, IV, 32);
    fn(mid, w1); /* midstate: first 64 header bytes never change while scanning nonces */

    w2[0] = be32(header + 64);
    w2[1] = be32(header + 68);
    w2[2] = be32(header + 72);
    w2[3] = 0;
    w2[4] = 0x80000000u;
    for (int i = 5; i < 15; i++) w2[i] = 0;
    w2[15] = 640;

    for (int i = 8; i < 15; i++) w3[i] = 0;
    w3[8] = 0x80000000u;
    w3[15] = 256;

    uint32_t target_words[8], best_words[8];
    for (int i = 0; i < 8; i++) target_words[i] = be32(target + 4 * i);

    double bd = 0.0;
    uint32_t bn = 0, nfound = 0, total_found = 0;
    int have_best = 0;

    for (uint32_t i = 0; i < count; i++) {
        uint32_t n = start + i;
        uint32_t s[8], d[8];
        w2[3] = __builtin_bswap32(n); /* header nonce is little-endian */
        memcpy(s, mid, 32);
        fn(s, w2);
        memcpy(w3, s, 32);
        memcpy(d, IV, 32);
        fn(d, w3);

        /*
         * Compare the nonce hash with the supplied scan target and current best hash
         * directly as 32-bit words. Only a new best hash needs conversion to
         * floating point. Kotlin re-hashes returned scan-target hits and classifies
         * them independently against the pool share target and Bitcoin network target.
         */
        int target_order = 0;
        int best_order = 0;
        for (int word = 0; word < 8; word++) {
            const uint32_t value = __builtin_bswap32(d[7 - word]);
            if (target_order == 0) {
                if (value < target_words[word]) target_order = -1;
                else if (value > target_words[word]) target_order = 1;
            }
            if (have_best && best_order == 0) {
                if (value < best_words[word]) best_order = -1;
                else if (value > best_words[word]) best_order = 1;
            }
            if (target_order != 0 && (!have_best || best_order != 0)) break;
        }

        if (target_order <= 0) {
            if (nfound < max_found) found[nfound++] = n;
            if (total_found < UINT32_MAX) total_found++;
        }

        if (!have_best || best_order < 0) {
            digest_numeric_words(d, best_words);
            have_best = 1;
            double v = 0.0;
            for (int word = 0; word < 8; word++) {
                v = v * 4294967296.0 + (double)best_words[word];
            }
            double diff = v > 0.0 ? DIFF1 / v : 1e300;
            /* Exact 256-bit comparison selected this better hash. Always
             * update its nonce even when the displayed double rounds equally. */
            bd = diff;
            bn = n;
        }
    }
    *best_diff = bd;
    *best_nonce = bn;
    if (overflow_count != NULL) *overflow_count = total_found - nfound;
    return nfound;
}


#ifdef HAVE_ARM_SHA2
/*
 * Consume one completed SHA-256d digest into the exact target/best-hash
 * accounting shared by the paired ARM scanner. Comparison is integer-exact;
 * floating point is used only to display the selected best difficulty.
 */
static inline void record_arm_pair_digest(
    const uint32_t digest[8], uint32_t nonce,
    const uint32_t target_words[8], uint32_t *found, uint32_t max_found,
    uint32_t *stored, uint32_t *total_found, uint32_t best_words[8],
    int *have_best, double *best_diff, uint32_t *best_nonce
) {
    const double DIFF1 = 65535.0 * 0x1p208;
    int target_order = 0;
    int best_order = 0;
    for (int word = 0; word < 8; ++word) {
        const uint32_t value = __builtin_bswap32(digest[7 - word]);
        if (target_order == 0) {
            if (value < target_words[word]) target_order = -1;
            else if (value > target_words[word]) target_order = 1;
        }
        if (*have_best && best_order == 0) {
            if (value < best_words[word]) best_order = -1;
            else if (value > best_words[word]) best_order = 1;
        }
        if (target_order != 0 && (!*have_best || best_order != 0)) break;
    }

    if (target_order <= 0) {
        if (*stored < max_found) found[(*stored)++] = nonce;
        if (*total_found < UINT32_MAX) ++(*total_found);
    }

    if (!*have_best || best_order < 0) {
        digest_numeric_words(digest, best_words);
        *have_best = 1;
        double value = 0.0;
        for (int word = 0; word < 8; ++word) {
            value = value * 4294967296.0 + (double)best_words[word];
        }
        *best_diff = value > 0.0 ? DIFF1 / value : 1e300;
        *best_nonce = nonce;
    }
}

/*
 * ARM SHA2 paired nonce scanner. Two independent nonce candidates are kept
 * in flight together so the CPU can overlap their SHA256 instruction streams.
 * The first 64-byte header midstate is still computed once per scan call.
 */
static uint32_t scan_impl_arm_pair(
    const uint8_t header[80], uint32_t start, uint32_t count,
    const uint8_t target[32], uint32_t *found, uint32_t max_found,
    double *best_diff, uint32_t *best_nonce, uint32_t *overflow_count
) {
    uint32_t first_block[16], mid[8];
    for (int i = 0; i < 16; ++i) first_block[i] = be32(header + 4 * i);
    memcpy(mid, IV, 32);
    compress_arm(mid, first_block);

    uint32_t w2a[16], w2b[16], w3a[16], w3b[16];
    for (int i = 0; i < 3; ++i) {
        w2a[i] = be32(header + 64 + 4 * i);
        w2b[i] = w2a[i];
    }
    w2a[4] = w2b[4] = 0x80000000u;
    for (int i = 5; i < 15; ++i) w2a[i] = w2b[i] = 0;
    w2a[15] = w2b[15] = 640;

    w3a[8] = w3b[8] = 0x80000000u;
    for (int i = 9; i < 15; ++i) w3a[i] = w3b[i] = 0;
    w3a[15] = w3b[15] = 256;

    uint32_t target_words[8], best_words[8];
    for (int i = 0; i < 8; ++i) target_words[i] = be32(target + 4 * i);

    double bd = 0.0;
    uint32_t bn = 0, stored = 0, total_found = 0;
    int have_best = 0;
    uint32_t i = 0;

    for (; i + 1u < count; i += 2u) {
        const uint32_t nonce_a = start + i;
        const uint32_t nonce_b = start + i + 1u;
        uint32_t state_a[8], state_b[8], digest_a[8], digest_b[8];

        w2a[3] = __builtin_bswap32(nonce_a);
        w2b[3] = __builtin_bswap32(nonce_b);
        memcpy(state_a, mid, 32);
        memcpy(state_b, mid, 32);
        compress_arm_pair(state_a, w2a, state_b, w2b);

        memcpy(w3a, state_a, 32);
        memcpy(w3b, state_b, 32);
        memcpy(digest_a, IV, 32);
        memcpy(digest_b, IV, 32);
        compress_arm_pair(digest_a, w3a, digest_b, w3b);

        record_arm_pair_digest(
            digest_a, nonce_a, target_words, found, max_found,
            &stored, &total_found, best_words, &have_best, &bd, &bn
        );
        record_arm_pair_digest(
            digest_b, nonce_b, target_words, found, max_found,
            &stored, &total_found, best_words, &have_best, &bd, &bn
        );
    }

    /* An odd final nonce uses the already validated single-state ARM path. */
    if (i < count) {
        const uint32_t nonce = start + i;
        uint32_t state[8], digest[8];
        w2a[3] = __builtin_bswap32(nonce);
        memcpy(state, mid, 32);
        compress_arm(state, w2a);
        memcpy(w3a, state, 32);
        memcpy(digest, IV, 32);
        compress_arm(digest, w3a);
        record_arm_pair_digest(
            digest, nonce, target_words, found, max_found,
            &stored, &total_found, best_words, &have_best, &bd, &bn
        );
    }

    *best_diff = bd;
    *best_nonce = bn;
    if (overflow_count != NULL) *overflow_count = total_found - stored;
    return stored;
}

/*
 * Run the complete 80-byte double-SHA256 header flow for two independent
 * headers through the paired compressor. Startup compares both outputs with
 * the already validated single-state ARM implementation.
 */
static void hash_pair_with_arm(
    const uint8_t header_a[80], const uint8_t header_b[80],
    uint8_t out_a[32], uint8_t out_b[32]
) {
    uint32_t first_a[16], first_b[16], mid_a[8], mid_b[8];
    uint32_t second_a[16], second_b[16], state_a[8], state_b[8];
    uint32_t third_a[16], third_b[16], digest_a[8], digest_b[8];

    for (int i = 0; i < 16; ++i) {
        first_a[i] = be32(header_a + 4 * i);
        first_b[i] = be32(header_b + 4 * i);
    }
    memcpy(mid_a, IV, 32);
    memcpy(mid_b, IV, 32);
    compress_arm_pair(mid_a, first_a, mid_b, first_b);

    const uint8_t *headers[2] = {header_a, header_b};
    uint32_t *second_blocks[2] = {second_a, second_b};
    for (int which = 0; which < 2; ++which) {
        uint32_t *block = second_blocks[which];
        const uint8_t *header = headers[which];
        block[0] = be32(header + 64);
        block[1] = be32(header + 68);
        block[2] = be32(header + 72);
        block[3] = be32(header + 76);
        block[4] = 0x80000000u;
        for (int i = 5; i < 15; ++i) block[i] = 0;
        block[15] = 640;
    }
    memcpy(state_a, mid_a, 32);
    memcpy(state_b, mid_b, 32);
    compress_arm_pair(state_a, second_a, state_b, second_b);

    uint32_t *third_blocks[2] = {third_a, third_b};
    uint32_t *states[2] = {state_a, state_b};
    for (int which = 0; which < 2; ++which) {
        uint32_t *block = third_blocks[which];
        memcpy(block, states[which], 32);
        block[8] = 0x80000000u;
        for (int i = 9; i < 15; ++i) block[i] = 0;
        block[15] = 256;
    }
    memcpy(digest_a, IV, 32);
    memcpy(digest_b, IV, 32);
    compress_arm_pair(digest_a, third_a, digest_b, third_b);
    digest_bytes(digest_a, out_a);
    digest_bytes(digest_b, out_b);
}

/* Runtime equivalence gate: compare the paired scanner against the known-good
 * single-state ARM scanner, including odd counts, target hits and uint32 wrap. */
static int selftest_arm_pair_scan(void) {
    uint8_t header[80], target[32];
    for (size_t i = 0; i < sizeof(header); ++i) {
        header[i] = (uint8_t)(i * 37u + 19u);
    }

    /* First verify every block stage of the paired hash path against the
     * single-state ARM compressor, whose four independent vectors passed. */
    for (uint32_t trial = 0; trial < 8; ++trial) {
        uint8_t header_a[80], header_b[80], expected_a[32], expected_b[32];
        uint8_t actual_a[32], actual_b[32];
        for (size_t i = 0; i < 80; ++i) {
            header_a[i] = (uint8_t)(i * 29u + trial * 17u + 3u);
            header_b[i] = (uint8_t)(i * 43u + trial * 31u + 11u);
        }
        hash_with(compress_arm, header_a, expected_a);
        hash_with(compress_arm, header_b, expected_b);
        hash_pair_with_arm(header_a, header_b, actual_a, actual_b);
        if (memcmp(expected_a, actual_a, 32) != 0 ||
            memcmp(expected_b, actual_b, 32) != 0) return 0;
    }

    const uint8_t target_modes[3] = {0xffu, 0x7fu, 0x00u};
    for (size_t mode = 0; mode < 3; ++mode) {
        memset(target, target_modes[mode], sizeof(target));
        uint32_t found_single[8] = {0}, found_pair[8] = {0};
        double diff_single = 0.0, diff_pair = 0.0;
        uint32_t nonce_single = 0, nonce_pair = 0;
        uint32_t overflow_single = 0, overflow_pair = 0;

        const uint32_t stored_single = scan_impl(
            compress_arm, header, UINT32_MAX - 63u, 129u, target,
            found_single, 8u, &diff_single, &nonce_single, &overflow_single
        );
        const uint32_t stored_pair = scan_impl_arm_pair(
            header, UINT32_MAX - 63u, 129u, target,
            found_pair, 8u, &diff_pair, &nonce_pair, &overflow_pair
        );
        if (stored_single != stored_pair ||
            overflow_single != overflow_pair ||
            nonce_single != nonce_pair ||
            memcmp(&diff_single, &diff_pair, sizeof(diff_single)) != 0 ||
            memcmp(found_single, found_pair, stored_single * sizeof(uint32_t)) != 0) {
            return 0;
        }
    }
    return 1;
}

/*
 * A correct paired compressor is not necessarily faster on every ARM core:
 * interleaving can lose to register pressure or the CPU's issue width.
 * Benchmark both complete nonce scanners on this device and enable paired
 * scanning only when its median is at least 1.5% faster.
 *
 * The test uses real SHA-256d work, a zero target (so no shares are emitted),
 * equal nonce ranges, monotonic timing, one warm-up per path, and alternating
 * measurement order. The small one-time cost is paid during core init only.
 */
#define ARM_PAIR_BENCH_HASHES (1u << 18)
#define ARM_PAIR_BENCH_ROUNDS 5

static volatile uint32_t g_arm_benchmark_sink;

static uint64_t arm_benchmark_scan(int paired, const uint8_t header[80],
                                   const uint8_t target[32], uint32_t count) {
    struct timespec begin, end;
    uint32_t found[8] = {0}, best_nonce = 0, overflow = 0;
    double best_diff = 0.0;
    if (clock_gettime(CLOCK_MONOTONIC, &begin) != 0) return 0;

    uint32_t stored = paired
        ? scan_impl_arm_pair(header, 0x13572468u, count, target, found, 8,
                             &best_diff, &best_nonce, &overflow)
        : scan_impl(compress_arm, header, 0x13572468u, count, target, found, 8,
                    &best_diff, &best_nonce, &overflow);

    if (clock_gettime(CLOCK_MONOTONIC, &end) != 0) return 0;
    /* Keep observable use of all result paths without adding work per nonce. */
    g_arm_benchmark_sink ^= stored ^ best_nonce ^ overflow;
    const int64_t elapsed =
        ((int64_t)end.tv_sec - (int64_t)begin.tv_sec) * 1000000000LL +
        ((int64_t)end.tv_nsec - (int64_t)begin.tv_nsec);
    return elapsed > 0 ? (uint64_t)elapsed : 0;
}

static uint64_t median_u64(const uint64_t values[ARM_PAIR_BENCH_ROUNDS]) {
    uint64_t sorted[ARM_PAIR_BENCH_ROUNDS];
    memcpy(sorted, values, sizeof(sorted));
    for (size_t i = 1; i < ARM_PAIR_BENCH_ROUNDS; ++i) {
        const uint64_t value = sorted[i];
        size_t j = i;
        while (j > 0 && sorted[j - 1] > value) {
            sorted[j] = sorted[j - 1];
            --j;
        }
        sorted[j] = value;
    }
    return sorted[ARM_PAIR_BENCH_ROUNDS / 2];
}

static int benchmark_arm_pair_scan(void) {
    uint8_t header[80], target[32] = {0};
    for (size_t i = 0; i < sizeof(header); ++i) {
        header[i] = (uint8_t)(i * 41u + 23u);
    }

    /* Warm both implementations before collecting timed samples. */
    if (arm_benchmark_scan(0, header, target, 1u << 16) == 0 ||
        arm_benchmark_scan(1, header, target, 1u << 16) == 0) return 0;

    uint64_t single[ARM_PAIR_BENCH_ROUNDS];
    uint64_t paired[ARM_PAIR_BENCH_ROUNDS];
    for (uint32_t round = 0; round < ARM_PAIR_BENCH_ROUNDS; ++round) {
        /* Alternate order to reduce systematic clock/temperature bias. */
        if ((round & 1u) == 0) {
            single[round] = arm_benchmark_scan(0, header, target, ARM_PAIR_BENCH_HASHES);
            paired[round] = arm_benchmark_scan(1, header, target, ARM_PAIR_BENCH_HASHES);
        } else {
            paired[round] = arm_benchmark_scan(1, header, target, ARM_PAIR_BENCH_HASHES);
            single[round] = arm_benchmark_scan(0, header, target, ARM_PAIR_BENCH_HASHES);
        }
        if (single[round] == 0 || paired[round] == 0) return 0;
    }

    const uint64_t single_median = median_u64(single);
    const uint64_t paired_median = median_u64(paired);
    if (single_median == 0) return 0;

    /* Lower elapsed time is faster; require a 1.5% margin to avoid noisy flips. */
    return paired_median * 1000ULL < single_median * 985ULL;
}
#endif


static void hex2bin(const char *hex, uint8_t *out, int n) {
    for (int i = 0; i < n; i++) {
        unsigned v = 0;
        for (int k = 0; k < 2; k++) {
            char c = hex[2 * i + k];
            v = v * 16 + (unsigned)(c <= '9' ? c - '0' : c - 'a' + 10);
        }
        out[i] = (uint8_t)v;
    }
}

/* Bitcoin genesis block header (nonce 2083236893) */
static const char *GENESIS_HEX =
    "01000000"
    "0000000000000000000000000000000000000000000000000000000000000000"
    "3ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a"
    "29ab5f49"
    "ffff001d"
    "1dac2b7c";

static int selftest(compress_fn_t fn) {
    static const uint8_t expect_display[32] = {
        0x00, 0x00, 0x00, 0x00, 0x00, 0x19, 0xd6, 0x68, 0x9c, 0x08, 0x5a, 0xe1, 0x65, 0x83, 0x1e, 0x93,
        0x4f, 0xf7, 0x63, 0xae, 0x46, 0xa2, 0xa6, 0xc1, 0x72, 0xb3, 0xf1, 0xb6, 0x0a, 0x8c, 0xe2, 0x6f};
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

    uint8_t hdr[80], out[32];
    hex2bin(GENESIS_HEX, hdr, 80);
    hash_with(fn, hdr, out);
    for (int i = 0; i < 32; i++)
        if (out[31 - i] != expect_display[i]) return 0;

    /* These independent known-answer vectors are checked for every candidate
     * compressor, including ARM SHA2, before it is selected for mining. */
    for (size_t v = 0; v < sizeof(independent_vectors) / sizeof(independent_vectors[0]); v++) {
        uint8_t expected[32];
        hex2bin(independent_vectors[v].header_hex, hdr, 80);
        hex2bin(independent_vectors[v].digest_hex, expected, 32);
        hash_with(fn, hdr, out);
        if (memcmp(out, expected, 32) != 0) return 0;
    }

    /* Restore the genesis header before the known-share scan below. */
    hex2bin(GENESIS_HEX, hdr, 80);
    uint8_t tgt[32];
    memset(tgt, 0, 32);
    tgt[4] = 0xff;
    tgt[5] = 0xff;
    hdr[76] = hdr[77] = hdr[78] = hdr[79] = 0;
    uint32_t found[4], bn, overflow = 0;
    double bd;
    uint32_t nf = scan_impl(fn, hdr, 2083236893u - 40u, 100u, tgt, found, 4, &bd, &bn, &overflow);
    return nf == 1 && found[0] == 2083236893u && bn == 2083236893u;
}

void miner_core_init(void) {
    g_fn = compress_scalar;
    g_hw = 0;
    g_ok = selftest(compress_scalar);
    g_arm_pair_scan = 0;
#ifdef HAVE_ARM_SHA2
    if (arm_sha2_supported() && selftest(compress_arm)) {
        g_fn = compress_arm;
        g_hw = 1;
        /* Only select the paired hot loop when its full scan output matches
         * the validated single-state ARM scanner on multiple target patterns. */
        g_arm_pair_scan = selftest_arm_pair_scan() && benchmark_arm_pair_scan();
    }
#endif
}

int miner_core_uses_hw(void) { return g_hw; }
int miner_core_selftest_ok(void) { return g_ok; }
int miner_core_uses_arm_pair_scan(void) { return g_arm_pair_scan; }

void miner_sha256d_header(const uint8_t header[80], uint8_t out[32]) { hash_with(g_fn, header, out); }

uint32_t miner_scan(const uint8_t header[80], uint32_t start, uint32_t count,
                    const uint8_t target[32], uint32_t *found, uint32_t max_found,
                    double *best_diff, uint32_t *best_nonce, uint32_t *overflow_count) {
#ifdef HAVE_ARM_SHA2
    if (g_arm_pair_scan) {
        return scan_impl_arm_pair(header, start, count, target, found, max_found,
                                  best_diff, best_nonce, overflow_count);
    }
#endif
    return scan_impl(g_fn, header, start, count, target, found, max_found, best_diff, best_nonce, overflow_count);
}
