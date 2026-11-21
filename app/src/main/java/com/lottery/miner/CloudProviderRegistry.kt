package com.lottery.miner

/**
 * Evidence-based, fail-closed registry for potential remote mining providers.
 *
 * This registry records policy eligibility only. It does not authenticate,
 * provision, or start workers. A remote worker may only be started after the
 * provider policy is satisfied, a mining-specific free quota is verified,
 * a supported provisioning integration exists, and the user authorizes it.
 */
object CloudProviderRegistry {
    enum class MiningPolicy {
        PROHIBITED,
        WRITTEN_APPROVAL_REQUIRED,
        ADVANCE_NOTICE_REQUIRED,
        EXPRESSLY_ALLOWED,
        NOT_VERIFIED
    }

    data class Provider(
        val name: String,
        val policy: MiningPolicy,
        val policyUrl: String,
        val note: String,
        val isFreeOffer: Boolean = false,
        val freeMiningQuotaVerified: Boolean = false,
        val provisioningApiVerified: Boolean = false
    )

    val providers = listOf(
        Provider(
            name = "Google Cloud Free Trial",
            policy = MiningPolicy.PROHIBITED,
            policyUrl = "https://support.google.com/cloud/answer/7002354",
            note = "Free Trial Services may not be used for cryptocurrency mining.",
            isFreeOffer = true
        ),
        Provider(
            name = "Google Cloud standard services",
            policy = MiningPolicy.WRITTEN_APPROVAL_REQUIRED,
            policyUrl = "https://cloud.google.com/terms",
            note = "Cryptocurrency mining requires prior written approval."
        ),
        Provider(
            name = "Google Colab managed runtimes",
            policy = MiningPolicy.PROHIBITED,
            policyUrl = "https://research.google.com/colaboratory/faq.html",
            note = "Cryptocurrency mining is disallowed on managed Colab runtimes.",
            isFreeOffer = true
        ),
        Provider(
            name = "Kaggle",
            policy = MiningPolicy.PROHIBITED,
            policyUrl = "https://www.kaggle.com/terms",
            note = "Terms prohibit cryptomining.",
            isFreeOffer = true
        ),
        Provider(
            name = "AWS Free Tier offers",
            policy = MiningPolicy.PROHIBITED,
            policyUrl = "https://aws.amazon.com/free/terms/",
            note = "Free Tier mining is prohibited; charges or suspension may apply.",
            isFreeOffer = true
        ),
        Provider(
            name = "AWS services",
            policy = MiningPolicy.PROHIBITED,
            policyUrl = "https://aws.amazon.com/service-terms/",
            note = "AWS Service Terms prohibit using or facilitating cryptocurrency mining."
        ),
        Provider(
            name = "Oracle Cloud (India agreement)",
            policy = MiningPolicy.PROHIBITED,
            policyUrl = "https://www.oracle.com/contracts/docs/cloud_csa_online_v062223_in_eng.pdf",
            note = "The India Cloud Services Agreement prohibits cryptocurrency mining."
        ),
        Provider(
            name = "Azure Lab Services",
            policy = MiningPolicy.PROHIBITED,
            policyUrl = "https://www.microsoft.com/licensing/terms/en-US/productoffering/MicrosoftAzureServices/OVOVS",
            note = "Product Terms prohibit cryptocurrency mining in Azure Lab Services."
        ),
        Provider(
            name = "DigitalOcean",
            policy = MiningPolicy.WRITTEN_APPROVAL_REQUIRED,
            policyUrl = "https://www.digitalocean.com/legal/acceptable-use-policy",
            note = "Explicit written permission is required; no free mining quota is verified."
        ),
        Provider(
            name = "GHive GPU rentals",
            policy = MiningPolicy.PROHIBITED,
            policyUrl = "https://ghive.co.in/legal/acceptable-use",
            note = "Signup credits may be offered, but its AUP explicitly bans crypto mining on rented compute and automatically ends detected mining rentals.",
            isFreeOffer = true
        ),
        Provider(
            name = "Joy Services dedicated servers",
            policy = MiningPolicy.WRITTEN_APPROVAL_REQUIRED,
            policyUrl = "https://joy.services/legal/aup",
            note = "Mining is permitted only on dedicated servers with prior written approval; shared VPS mining is prohibited. No free mining quota is verified."
        ),
        Provider(
            name = "OpenMetal cloud",
            policy = MiningPolicy.WRITTEN_APPROVAL_REQUIRED,
            policyUrl = "https://openmetal.io/cloud-services-agreement/",
            note = "Prior written approval is required; crypto-mining may trigger separate pricing with no grace period. Free trial eligibility for mining is not verified."
        ),
        Provider(
            name = "GPU Cloud HQ workstation GPUs",
            policy = MiningPolicy.ADVANCE_NOTICE_REQUIRED,
            policyUrl = "https://gpucloudhq.com/acceptable-use",
            note = "Mining is permitted on workstation-class GPUs only if disclosed in advance. Published workstation GPU rentals are paid; no free mining quota or app provisioning integration is verified."
        )
    )

    /**
     * Eligibility predicate only. No caller currently provisions resources.
     * Unknown, prohibited, incomplete, or unapproved providers fail closed.
     */
    fun canStartWorker(
        provider: Provider,
        writtenApprovalVerified: Boolean,
        userAuthorized: Boolean,
        advanceNoticeVerified: Boolean = false
    ): Boolean {
        val policyAllowsMining = when (provider.policy) {
            MiningPolicy.PROHIBITED, MiningPolicy.NOT_VERIFIED -> false
            MiningPolicy.WRITTEN_APPROVAL_REQUIRED -> writtenApprovalVerified
            MiningPolicy.ADVANCE_NOTICE_REQUIRED -> advanceNoticeVerified
            MiningPolicy.EXPRESSLY_ALLOWED -> true
        }
        return policyAllowsMining &&
            provider.freeMiningQuotaVerified &&
            provider.provisioningApiVerified &&
            userAuthorized
    }

    fun summary(): String = buildString {
        val freeMiningProviders = providers.count {
            it.policy == MiningPolicy.EXPRESSLY_ALLOWED &&
                it.isFreeOffer && it.freeMiningQuotaVerified && it.provisioningApiVerified
        }
        val prohibitedFreeOffers = providers.count {
            it.isFreeOffer && it.policy == MiningPolicy.PROHIBITED
        }
        val approvalProviders = providers.filter {
            it.policy == MiningPolicy.WRITTEN_APPROVAL_REQUIRED
        }
        val noticeProviders = providers.filter {
            it.policy == MiningPolicy.ADVANCE_NOTICE_REQUIRED
        }

        append("Cloud auto-connect: LOCKED\n")
        append("Verified free + mining-permitted providers: ").append(freeMiningProviders)
        append("\nFree offers with mining prohibited: ").append(prohibitedFreeOffers)
        append("\nPaid providers requiring written approval: ")
            .append(approvalProviders.size)
        append("\nPaid providers requiring advance notice: ")
            .append(noticeProviders.size)
        append("\nWritten-approval providers: ")
            .append(approvalProviders.joinToString { it.name })
        append("\nAdvance-notice providers: ")
            .append(noticeProviders.joinToString { it.name })
        append("\nVerified provider provisioning integrations: ")
            .append(providers.count { it.provisioningApiVerified })
        append("\nOAuth login: NOT AVAILABLE")
        append("\nNo eligible free provider or working cloud provisioning integration is configured.")
        append("\nNo cloud instance is provisioned; paid usage is blocked.")
    }
}
