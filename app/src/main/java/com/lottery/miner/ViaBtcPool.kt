package com.lottery.miner

/** Explicit ViaBTC BTC Stratum profile. TLS transport is selected by port 551. */
internal object ViaBtcPool {
    const val HOST = "btc.viabtc.io"
    const val TLS_HOST = "btc-ssl.viabtc.io"
    const val TCP_PORT = 3333
    const val TLS_PORT = 551

    fun isViaBtcHost(host: String): Boolean =
        host.equals(HOST, ignoreCase = true) || host.equals(TLS_HOST, ignoreCase = true)

    fun isValidEndpoint(host: String, port: Int): Boolean = when {
        host.equals(HOST, ignoreCase = true) -> port == TCP_PORT || port == TLS_PORT
        host.equals(TLS_HOST, ignoreCase = true) -> port == TLS_PORT
        else -> true
    }

    fun isValidWorker(value: String): Boolean {
        if (value.length !in 3..128 || value.any { it.isWhitespace() }) return false
        val parts = value.split('.', limit = 2)
        if (parts.size != 2) return false
        val accountId = Regex("[A-Za-z0-9]{1,64}")
        val workerId = Regex("[a-z0-9]{1,64}")
        return accountId.matches(parts[0]) && workerId.matches(parts[1])
    }
}
