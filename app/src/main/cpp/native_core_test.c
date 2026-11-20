#include "miner_core.h"
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

int main(void) {
    miner_core_init();
    assert(miner_core_selftest_ok() == 1);

    uint8_t header[80] = {0};
    uint8_t target[32];
    memset(target, 0xff, sizeof(target));

    uint32_t found[1] = {0};
    uint32_t best_nonce = 0;
    uint32_t overflow = 0;
    double best_diff = 0.0;

    uint32_t stored = miner_scan(header, 0, 10, target, found, 1,
                                 &best_diff, &best_nonce, &overflow);
    assert(stored == 1);
    assert(overflow == 9);
    assert(best_diff > 0.0);

    puts("native SHA256d self-test and bounded-hit accounting passed");
    return 0;
}
