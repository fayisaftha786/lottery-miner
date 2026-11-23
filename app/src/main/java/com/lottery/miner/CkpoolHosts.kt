package com.lottery.miner

import java.util.Locale

/** Official Solo CKPool Stratum V1 endpoints documented by the pool operator. */
object CkpoolHosts {
    private val officialSoloEndpoints = setOf(
        "stratum.ckpool.org",
        "uwsolo.ckpool.org",
        "uesolo.ckpool.org",
        "eusolo.ckpool.org",
        "sgsolo.ckpool.org",
        "ausolo.ckpool.org"
    )

    fun isOfficialSoloEndpoint(host: String): Boolean {
        val normalized = host.trim().trimEnd('.').lowercase(Locale.ROOT)
        return normalized in officialSoloEndpoints
    }
}
