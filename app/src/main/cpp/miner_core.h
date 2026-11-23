#ifndef MINER_CORE_H
#define MINER_CORE_H

#include <stdint.h>

/* SHA-256 compression: state[8] updated in place, w[16] = message words (host order, big-endian decoded). */
typedef void (*compress_fn_t)(uint32_t state[8], const uint32_t w[16]);

/* Picks the fastest implementation (ARM SHA2 hardware if present and self-test passes). */
void miner_core_init(void);
int miner_core_uses_hw(void);
int miner_core_selftest_ok(void);
/* 1 only when the experimental ARM dual compressor matches two scalar references. */
int miner_core_dual_selftest_ok(void);

/* Double SHA-256 of an 80 byte block header. out = digest bytes in internal order (little-endian number). */
void miner_sha256d_header(const uint8_t header[80], uint8_t out[32]);

/*
 * Scan nonces [start, start+count) (wrapping mod 2^32). The nonce is written little-endian into header[76..79].
 * target: 32 bytes, big-endian. A nonce is a share if hash <= target.
 * found: receives up to max_found share nonces. Returns number stored in found.
 * overflow_count: additional target hits beyond max_found (may be NULL).
 * best_diff / best_nonce: best (lowest) hash seen in this call, expressed as difficulty (diff1 / hash).
 */
uint32_t miner_scan(const uint8_t header[80], uint32_t start, uint32_t count,
                    const uint8_t target[32], uint32_t *found, uint32_t max_found,
                    double *best_diff, uint32_t *best_nonce, uint32_t *overflow_count);

extern const uint32_t SHA256_K[64];

#ifdef HAVE_ARM_SHA2
void compress_arm(uint32_t state[8], const uint32_t w[16]);
/* Experimental: compress two independent SHA-256 message blocks in interleaved ARM SHA2 lanes. */
void compress_arm_dual(uint32_t state_a[8], const uint32_t w_a[16],
                        uint32_t state_b[8], const uint32_t w_b[16]);
int arm_sha2_supported(void);
#endif

#endif
