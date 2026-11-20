/* Host-side test of the C core:  gcc -O3 -o coretest hosttest/test.c app/src/main/cpp/miner_core.c && ./coretest */
#include "../app/src/main/cpp/miner_core.h"
#include <stdio.h>
#include <string.h>
#include <time.h>

int main(void) {
    miner_core_init();
    printf("selftest (genesis hash + nonce scan): %s\n", miner_core_selftest_ok() ? "OK" : "FAILED");
    printf("hardware SHA2 in use: %d\n", miner_core_uses_hw());
    if (!miner_core_selftest_ok()) return 1;

    /* benchmark: scan 2^24 nonces against an impossible target */
    uint8_t hdr[80];
    memset(hdr, 0x11, 80);
    uint8_t tgt[32];
    memset(tgt, 0, 32);
    uint32_t found[8], bn;
    double bd;
    const uint32_t N = 1u << 24;
    clock_t t0 = clock();
    uint32_t nf = miner_scan(hdr, 0, N, tgt, found, 8, &bd, &bn);
    double sec = (double)(clock() - t0) / CLOCKS_PER_SEC;
    printf("benchmark: %.2f MH/s (shares=%u, best diff in range=%.3f at nonce %u)\n", N / sec / 1e6, nf, bd, bn);
    return 0;
}
