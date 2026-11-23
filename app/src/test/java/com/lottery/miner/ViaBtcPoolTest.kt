package com.lottery.miner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViaBtcPoolTest {
    @Test fun recognizesOfficialBitcoinHosts() {
        assertTrue(ViaBtcPool.isViaBtcHost("btc.viabtc.io"))
        assertTrue(ViaBtcPool.isViaBtcHost("BTC-SSL.VIABTC.IO"))
        assertFalse(ViaBtcPool.isViaBtcHost("example.com"))
    }

    @Test fun validatesEndpointAndTlsPairings() {
        assertTrue(ViaBtcPool.isValidEndpoint(ViaBtcPool.HOST, ViaBtcPool.TCP_PORT))
        assertTrue(ViaBtcPool.isValidEndpoint(ViaBtcPool.HOST, ViaBtcPool.TLS_PORT))
        assertTrue(ViaBtcPool.isValidEndpoint(ViaBtcPool.TLS_HOST, ViaBtcPool.TLS_PORT))
        assertFalse(ViaBtcPool.isValidEndpoint(ViaBtcPool.TLS_HOST, ViaBtcPool.TCP_PORT))
    }

    @Test fun requiresAccountAndWorkerSeparatedByDot() {
        assertTrue(ViaBtcPool.isValidWorker("account01.phone01"))
        assertTrue(ViaBtcPool.isValidWorker("Account01.phone01"))
        assertFalse(ViaBtcPool.isValidWorker("account01.Phone01"))
        assertFalse(ViaBtcPool.isValidWorker("account_01.phone01"))
        assertFalse(ViaBtcPool.isValidWorker("account01.phone_01"))
        assertFalse(ViaBtcPool.isValidWorker("account01"))
        assertFalse(ViaBtcPool.isValidWorker(".worker"))
        assertFalse(ViaBtcPool.isValidWorker("account."))
        assertFalse(ViaBtcPool.isValidWorker("account.worker name"))
        assertFalse(ViaBtcPool.isValidWorker("account.worker.extra"))
    }
}
