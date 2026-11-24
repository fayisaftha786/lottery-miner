package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiningModeTest {
    @Test fun unknownModeDefaultsToExistingPoolBehavior() {
        assertEquals(MiningMode.POOL, MiningMode.fromId(null))
        assertEquals(MiningMode.POOL, MiningMode.fromId("unknown"))
        assertEquals(MiningMode.DATUM_SOLO, MiningMode.fromId("datum_solo"))
    }

    @Test fun datumUsesGatewayStratumPort() {
        assertEquals(3333, MiningMode.POOL.defaultPort)
        assertEquals(23334, MiningMode.DATUM_SOLO.defaultPort)
    }

    @Test fun workerNameValidationRejectsCredentialsAndInvalidCharacters() {
        assertTrue(MiningMode.isValidWorkerName("miner1"))
        assertTrue(MiningMode.isValidWorkerName("worker_01"))
        assertTrue(MiningMode.isValidWorkerName("rig-1.test"))
        assertFalse(MiningMode.isValidWorkerName(""))
        assertFalse(MiningMode.isValidWorkerName(" miner1"))
        assertFalse(MiningMode.isValidWorkerName("miner one"))
        assertFalse(MiningMode.isValidWorkerName("user:password"))
        assertFalse(MiningMode.isValidWorkerName("x".repeat(65)))
    }
}
