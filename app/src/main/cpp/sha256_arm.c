#include "miner_core.h"

#if defined(__aarch64__) && defined(HAVE_ARM_SHA2)
#include <arm_neon.h>
#include <sys/auxv.h>

#ifndef HWCAP_SHA2
#define HWCAP_SHA2 (1 << 6)
#endif

int arm_sha2_supported(void) { return (getauxval(AT_HWCAP) & HWCAP_SHA2) != 0; }

/* SHA-256 compression using the ARMv8 Crypto Extension. 4 rounds per iteration. */
void compress_arm(uint32_t state[8], const uint32_t w[16]) {
    uint32x4_t s0 = vld1q_u32(&state[0]);
    uint32x4_t s1 = vld1q_u32(&state[4]);
    const uint32x4_t save0 = s0, save1 = s1;
    uint32x4_t m[4];
    m[0] = vld1q_u32(&w[0]);
    m[1] = vld1q_u32(&w[4]);
    m[2] = vld1q_u32(&w[8]);
    m[3] = vld1q_u32(&w[12]);

#pragma clang loop unroll(full)
    for (int i = 0; i < 16; i++) {
        uint32x4_t cur = m[i & 3];
        uint32x4_t wk = vaddq_u32(cur, vld1q_u32(&SHA256_K[4 * i]));
        uint32x4_t prev0 = s0;
        s0 = vsha256hq_u32(s0, s1, wk);
        s1 = vsha256h2q_u32(s1, prev0, wk);
        if (i < 12) {
            m[i & 3] = vsha256su1q_u32(vsha256su0q_u32(cur, m[(i + 1) & 3]),
                                       m[(i + 2) & 3], m[(i + 3) & 3]);
        }
    }
    vst1q_u32(&state[0], vaddq_u32(s0, save0));
    vst1q_u32(&state[4], vaddq_u32(s1, save1));
}
#endif


/*
 * Experimental dual-stream ARM SHA-256 compression.
 *
 * The two states and message schedules remain fully independent. The
 * instruction stream alternates between them to expose independent work
 * while one SHA pipeline is waiting on round dependencies. This function
 * is intentionally not selected by the production miner until device
 * benchmarks demonstrate a repeatable end-to-end gain.
 */
void compress_arm_dual(uint32_t state_a[8], const uint32_t w_a[16],
                       uint32_t state_b[8], const uint32_t w_b[16]) {
    uint32x4_t a0 = vld1q_u32(&state_a[0]);
    uint32x4_t a1 = vld1q_u32(&state_a[4]);
    uint32x4_t b0 = vld1q_u32(&state_b[0]);
    uint32x4_t b1 = vld1q_u32(&state_b[4]);
    const uint32x4_t a_save0 = a0, a_save1 = a1;
    const uint32x4_t b_save0 = b0, b_save1 = b1;
    uint32x4_t am[4], bm[4];
    for (int j = 0; j < 4; j++) {
        am[j] = vld1q_u32(&w_a[4 * j]);
        bm[j] = vld1q_u32(&w_b[4 * j]);
    }

#pragma clang loop unroll(full)
    for (int i = 0; i < 16; i++) {
        uint32x4_t acur = am[i & 3];
        uint32x4_t awk = vaddq_u32(acur, vld1q_u32(&SHA256_K[4 * i]));
        uint32x4_t aprev = a0;
        a0 = vsha256hq_u32(a0, a1, awk);
        a1 = vsha256h2q_u32(a1, aprev, awk);

        uint32x4_t bcur = bm[i & 3];
        uint32x4_t bwk = vaddq_u32(bcur, vld1q_u32(&SHA256_K[4 * i]));
        uint32x4_t bprev = b0;
        b0 = vsha256hq_u32(b0, b1, bwk);
        b1 = vsha256h2q_u32(b1, bprev, bwk);

        if (i < 12) {
            am[i & 3] = vsha256su1q_u32(vsha256su0q_u32(acur, am[(i + 1) & 3]),
                                         am[(i + 2) & 3], am[(i + 3) & 3]);
            bm[i & 3] = vsha256su1q_u32(vsha256su0q_u32(bcur, bm[(i + 1) & 3]),
                                         bm[(i + 2) & 3], bm[(i + 3) & 3]);
        }
    }
    vst1q_u32(&state_a[0], vaddq_u32(a0, a_save0));
    vst1q_u32(&state_a[4], vaddq_u32(a1, a_save1));
    vst1q_u32(&state_b[0], vaddq_u32(b0, b_save0));
    vst1q_u32(&state_b[4], vaddq_u32(b1, b_save1));
}
