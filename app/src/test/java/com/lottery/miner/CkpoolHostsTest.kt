package com.lottery.miner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CkpoolHostsTest {
    @Test fun acceptsOfficialAutoAndRegionalSoloEndpoints() {
        listOf(
            "stratum.ckpool.org",
            "uwsolo.ckpool.org",
            "uesolo.ckpool.org",
            "eusolo.ckpool.org",
            "sgsolo.ckpool.org",
            "ausolo.ckpool.org"
        ).forEach { assertTrue(it, CkpoolHosts.isOfficialSoloEndpoint(it)) }
    }

    @Test fun normalizesCaseWhitespaceAndTrailingDnsRootDot() {
        assertTrue(CkpoolHosts.isOfficialSoloEndpoint(" STRATUM.CKPOOL.ORG. "))
    }

    @Test fun rejectsArbitrarySubdomainsAndOtherPoolHosts() {
        assertFalse(CkpoolHosts.isOfficialSoloEndpoint("fake.ckpool.org"))
        assertFalse(CkpoolHosts.isOfficialSoloEndpoint("stratum.example.org"))
        assertFalse(CkpoolHosts.isOfficialSoloEndpoint("ckpool.org"))
    }

    @Test fun allowsOnlyStandardSoloPortForMiningAndEstimator() {
        assertTrue(CkpoolHosts.isSupportedSoloEndpoint("stratum.ckpool.org", 3333))
        assertFalse(CkpoolHosts.isSupportedSoloEndpoint("stratum.ckpool.org", 4334))
        assertFalse(CkpoolHosts.isSupportedSoloEndpoint("fake.ckpool.org", 3333))
    }

}
