package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudProviderRegistryTest {
    @Test fun workerRequiresEveryIndependentApprovalGate() {
        assertTrue(CloudProviderRegistry.canStartWorker(true, true, true))
        assertFalse(CloudProviderRegistry.canStartWorker(false, true, true))
        assertFalse(CloudProviderRegistry.canStartWorker(true, false, true))
        assertFalse(CloudProviderRegistry.canStartWorker(true, true, false))
        assertFalse(CloudProviderRegistry.canStartWorker(false, false, false))
    }

    @Test fun currentRegistryContainsNoApprovedFreeMiningProvider() {
        assertEquals(0, CloudProviderRegistry.providers.count {
            it.policy == CloudProviderRegistry.MiningPolicy.NOT_VERIFIED
        })
        assertEquals(1, CloudProviderRegistry.providers.count {
            it.policy == CloudProviderRegistry.MiningPolicy.WRITTEN_APPROVAL_REQUIRED
        })
        assertTrue(CloudProviderRegistry.providers.any {
            it.name == "Google Colab" && it.policy == CloudProviderRegistry.MiningPolicy.PROHIBITED
        })
    }

    @Test fun statusNeverClaimsCloudWorkersAreConnected() {
        val status = CloudProviderRegistry.summary()
        assertTrue(status.contains("Cloud auto-connect: LOCKED"))
        assertTrue(status.contains("Verified free + mining-permitted providers: 0"))
        assertTrue(status.contains("No instance is provisioned"))
        assertFalse(status.contains("CONNECTED"))
    }
}
