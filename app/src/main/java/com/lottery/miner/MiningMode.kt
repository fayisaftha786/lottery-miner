package com.lottery.miner

/** Mining destinations supported by the Android Stratum V1 client. */
enum class MiningMode(val id: String, val defaultPort: Int) {
    POOL("pool", MinerDefaults.POOL_PORT),
    DATUM_SOLO("datum_solo", 23334);

    companion object {
        fun fromId(value: String?): MiningMode =
            values().firstOrNull { it.id == value } ?: POOL

        fun isValidWorkerName(value: String): Boolean =
            value.length in 1..64 &&
                value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
    }
}
