package com.lottery.miner

/** Shared defaults used by the UI and service so fallback configuration cannot drift. */
internal object MinerDefaults {
    const val DEFAULT_PAYOUT_ADDRESS = "bc1qe4xhqarfw9tqaxaad3cx6ayl0r93ehyqf5uses"
    const val POOL_HOST = "stratum.ckpool.org"
    const val POOL_PORT = 3333
}
