package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudProviderRegistryTest {

    @Test fun prohibitedProvidersNeverPassEvenWhenAllOtherEvidenceIsTrue() {
        val provider = CloudProviderRegistry.providers.first { it.name == "Google Colab managed runtimes" }
            .copy(
                freeMiningQuotaVerified = true,
                provisioningApiVerified = true
            )
        assertFalse(CloudProviderRegistry.canStartWorker(provider, true, true))
    }

    @Test fun writtenApprovalRouteStillRequiresFreeQuotaApiAndUserAuthorization() {
        val provider = CloudProviderRegistry.providers.first { it.name == "DigitalOcean" }
        assertFalse(CloudProviderRegistry.canStartWorker(provider, true, true))

        val fullyVerified = provider.copy(
            freeMiningQuotaVerified = true,
            provisioningApiVerified = true
        )
        assertTrue(CloudProviderRegistry.canStartWorker(fullyVerified, true, true))
        assertFalse(CloudProviderRegistry.canStartWorker(fullyVerified, false, true))
        assertFalse(CloudProviderRegistry.canStartWorker(fullyVerified, true, false))
        assertFalse(CloudProviderRegistry.canStartWorker(
            fullyVerified.copy(provisioningApiVerified = false), true, true
        ))
        assertFalse(CloudProviderRegistry.canStartWorker(
            fullyVerified.copy(freeMiningQuotaVerified = false), true, true
        ))
    }

    @Test fun expresslyAllowedProviderStillNeedsVerifiedQuotaApiAndUserAuthorization() {
        val provider = CloudProviderRegistry.Provider(
            name = "test-only fixture",
            policy = CloudProviderRegistry.MiningPolicy.EXPRESSLY_ALLOWED,
            policyUrl = "https://example.invalid/policy",
            note = "Test fixture only",
            isFreeOffer = true,
            freeMiningQuotaVerified = true,
            provisioningApiVerified = true
        )
        assertTrue(CloudProviderRegistry.canStartWorker(provider, false, true))
        assertFalse(CloudProviderRegistry.canStartWorker(provider, false, false))
        assertFalse(CloudProviderRegistry.canStartWorker(
            provider.copy(provisioningApiVerified = false), false, true
        ))
    }

    @Test fun currentProvidersContainNoFullyVerifiedFreeMiningOption() {
        assertEquals(0, CloudProviderRegistry.providers.count {
            it.policy == CloudProviderRegistry.MiningPolicy.EXPRESSLY_ALLOWED &&
                it.isFreeOffer && it.freeMiningQuotaVerified && it.provisioningApiVerified
        })
        assertEquals(4, CloudProviderRegistry.providers.count {
            it.isFreeOffer && it.policy == CloudProviderRegistry.MiningPolicy.PROHIBITED
        })
        assertEquals(2, CloudProviderRegistry.providers.count {
            it.policy == CloudProviderRegistry.MiningPolicy.WRITTEN_APPROVAL_REQUIRED
        })
        assertEquals(0, CloudProviderRegistry.providers.count { it.provisioningApiVerified })
    }

    @Test fun statusNeverClaimsCloudWorkersAreConnected() {
        val status = CloudProviderRegistry.summary()
        assertTrue(status.contains("Cloud auto-connect: LOCKED"))
        assertTrue(status.contains("Verified free + mining-permitted providers: 0"))
        assertTrue(status.contains("OAuth login: NOT AVAILABLE"))
        assertTrue(status.contains("No cloud instance is provisioned"))
        assertFalse(status.contains("CONNECTED"))
    }
}
