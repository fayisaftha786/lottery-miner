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

    /** This Stratum V1 client uses CKPool's standard solo port, not its rental-only high-difficulty port. */
    fun isSupportedSoloEndpoint(host: String, port: Int): Boolean =
        port == 3333 && isOfficialSoloEndpoint(host)
}
