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
 * Compress two independent SHA-256 states in an interleaved instruction stream.
 * This exposes independent work to the CPU while the SHA2 pipeline processes
 * each message. It is used only by the paired nonce scanner after runtime
 * equivalence tests pass; the single-state compressor remains the fallback.
 */
void compress_arm_pair(
    uint32_t state_a[8], const uint32_t w_a[16],
    uint32_t state_b[8], const uint32_t w_b[16]
) {
    uint32x4_t a0 = vld1q_u32(&state_a[0]);
    uint32x4_t a1 = vld1q_u32(&state_a[4]);
    uint32x4_t b0 = vld1q_u32(&state_b[0]);
    uint32x4_t b1 = vld1q_u32(&state_b[4]);
    const uint32x4_t save_a0 = a0, save_a1 = a1;
    const uint32x4_t save_b0 = b0, save_b1 = b1;
    uint32x4_t ma[4], mb[4];
    for (int i = 0; i < 4; ++i) {
        ma[i] = vld1q_u32(&w_a[4 * i]);
        mb[i] = vld1q_u32(&w_b[4 * i]);
    }

#pragma clang loop unroll(full)
    for (int i = 0; i < 16; ++i) {
        const int slot = i & 3;
        uint32x4_t cur_a = ma[slot];
        uint32x4_t cur_b = mb[slot];
        uint32x4_t wk_a = vaddq_u32(cur_a, vld1q_u32(&SHA256_K[4 * i]));
        uint32x4_t wk_b = vaddq_u32(cur_b, vld1q_u32(&SHA256_K[4 * i]));
        uint32x4_t prev_a0 = a0;
        uint32x4_t prev_b0 = b0;

        a0 = vsha256hq_u32(a0, a1, wk_a);
        a1 = vsha256h2q_u32(a1, prev_a0, wk_a);
        b0 = vsha256hq_u32(b0, b1, wk_b);
        b1 = vsha256h2q_u32(b1, prev_b0, wk_b);

        if (i < 12) {
            ma[slot] = vsha256su1q_u32(
                vsha256su0q_u32(cur_a, ma[(i + 1) & 3]),
                ma[(i + 2) & 3], ma[(i + 3) & 3]
            );
            mb[slot] = vsha256su1q_u32(
                vsha256su0q_u32(cur_b, mb[(i + 1) & 3]),
                mb[(i + 2) & 3], mb[(i + 3) & 3]
            );
        }
    }

    vst1q_u32(&state_a[0], vaddq_u32(a0, save_a0));
    vst1q_u32(&state_a[4], vaddq_u32(a1, save_a1));
    vst1q_u32(&state_b[0], vaddq_u32(b0, save_b0));
    vst1q_u32(&state_b[4], vaddq_u32(b1, save_b1));
}

