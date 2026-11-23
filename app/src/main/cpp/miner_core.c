#include "miner_core.h"
#include <string.h>

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
         * Compare the nonce hash with the pool target and current best hash
         * directly as 32-bit words. Only a new best hash needs conversion to
         * floating point, and only a pool hit is later re-hashed for submit.
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
            /* The full 256-bit comparison above selected this better hash.
             * Always keep its nonce even if the displayed double rounds equal. */
            bd = diff;
            bn = n;
        }
    }
    *best_diff = bd;
    *best_nonce = bn;
    if (overflow_count != NULL) *overflow_count = total_found - nfound;
    return nfound;
}

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
    uint8_t hdr[80], out[32];
    hex2bin(GENESIS_HEX, hdr, 80);
    hash_with(fn, hdr, out);
    for (int i = 0; i < 32; i++)
        if (out[31 - i] != expect_display[i]) return 0;

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
#ifdef HAVE_ARM_SHA2
    if (arm_sha2_supported() && selftest(compress_arm)) {
        g_fn = compress_arm;
        g_hw = 1;
    }
#endif
}

int miner_core_uses_hw(void) { return g_hw; }
int miner_core_selftest_ok(void) { return g_ok; }

void miner_sha256d_header(const uint8_t header[80], uint8_t out[32]) { hash_with(g_fn, header, out); }

uint32_t miner_scan(const uint8_t header[80], uint32_t start, uint32_t count,
                    const uint8_t target[32], uint32_t *found, uint32_t max_found,
                    double *best_diff, uint32_t *best_nonce, uint32_t *overflow_count) {
    return scan_impl(g_fn, header, start, count, target, found, max_found, best_diff, best_nonce, overflow_count);
}
