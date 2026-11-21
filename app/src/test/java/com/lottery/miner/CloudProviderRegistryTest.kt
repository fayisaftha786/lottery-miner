package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudProviderRegistryTest {
    @Test fun prohibitedProvidersNeverPassEvenWhenOtherEvidenceIsTrue() {
        val provider = CloudProviderRegistry.providers.first { it.name == "Google Colab managed runtimes" }
            .copy(freeMiningQuotaVerified = true, provisioningApiVerified = true)
        assertFalse(CloudProviderRegistry.canStartWorker(provider, true, true))
    }

    @Test fun approvalRouteStillRequiresApprovalFreeQuotaApiAndConsent() {
        val provider = CloudProviderRegistry.providers.first { it.name == "DigitalOcean" }
        assertFalse(CloudProviderRegistry.canStartWorker(provider, true, true))
        val verified = provider.copy(freeMiningQuotaVerified = true, provisioningApiVerified = true)
        assertTrue(CloudProviderRegistry.canStartWorker(verified, true, true))
        assertFalse(CloudProviderRegistry.canStartWorker(verified, false, true))
        assertFalse(CloudProviderRegistry.canStartWorker(verified, true, false))
        assertFalse(CloudProviderRegistry.canStartWorker(verified.copy(provisioningApiVerified = false), true, true))
        assertFalse(CloudProviderRegistry.canStartWorker(verified.copy(freeMiningQuotaVerified = false), true, true))
    }

    @Test fun advanceNoticeRouteRequiresNoticeQuotaApiAndConsent() {
        val provider = CloudProviderRegistry.providers.first {
            it.name == "GPU Cloud HQ workstation GPUs"
        }
        assertFalse(CloudProviderRegistry.canStartWorker(provider, false, true))
        val verified = provider.copy(freeMiningQuotaVerified = true, provisioningApiVerified = true)
        assertFalse(CloudProviderRegistry.canStartWorker(verified, false, true))
        assertTrue(CloudProviderRegistry.canStartWorker(
            verified,
            writtenApprovalVerified = false,
            userAuthorized = true,
            advanceNoticeVerified = true
        ))
        assertFalse(CloudProviderRegistry.canStartWorker(
            verified,
            writtenApprovalVerified = false,
            userAuthorized = false,
            advanceNoticeVerified = true
        ))
    }

    @Test fun expresslyAllowedProviderStillRequiresQuotaApiAndConsent() {
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
        assertFalse(CloudProviderRegistry.canStartWorker(provider.copy(provisioningApiVerified = false), false, true))
    }

    @Test fun currentRegistryHasNoVerifiedFreeMiningProvider() {
        assertEquals(0, CloudProviderRegistry.providers.count {
            it.policy == CloudProviderRegistry.MiningPolicy.EXPRESSLY_ALLOWED &&
                it.isFreeOffer && it.freeMiningQuotaVerified && it.provisioningApiVerified
        })
        assertEquals(5, CloudProviderRegistry.providers.count {
            it.isFreeOffer && it.policy == CloudProviderRegistry.MiningPolicy.PROHIBITED
        })
        assertEquals(4, CloudProviderRegistry.providers.count {
            it.policy == CloudProviderRegistry.MiningPolicy.WRITTEN_APPROVAL_REQUIRED
        })
        assertEquals(1, CloudProviderRegistry.providers.count {
            it.policy == CloudProviderRegistry.MiningPolicy.ADVANCE_NOTICE_REQUIRED
        })
        assertEquals(0, CloudProviderRegistry.providers.count { it.provisioningApiVerified })
    }

    @Test fun statusNeverPretendsRemoteWorkersAreConnected() {
        val status = CloudProviderRegistry.summary()
        assertTrue(status.contains("Cloud auto-connect: LOCKED"))
        assertTrue(status.contains("Verified free + mining-permitted providers: 0"))
        assertTrue(status.contains("No eligible free provider or working cloud provisioning integration"))
        assertTrue(status.contains("OAuth login: NOT AVAILABLE"))
        assertTrue(status.contains("No cloud instance is provisioned"))
        assertFalse(status.contains("CONNECTED"))
    }
}
