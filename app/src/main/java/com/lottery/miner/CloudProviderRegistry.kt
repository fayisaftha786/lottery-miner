package com.lottery.miner

/**
 * Evidence-based, fail-closed registry for potential remote mining providers.
 *
 * Listing a provider is not an endorsement. A worker may start only when the
 * provider policy permits it, any required written approval is verified,
 * free mining quota is verified, the provisioning API is verified, and the
 * user explicitly authorizes the run. Never store provider passwords or tokens here.
 */
object CloudProviderRegistry {

    enum class MiningPolicy {
        PROHIBITED,
        WRITTEN_APPROVAL_REQUIRED,
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
            note = "Cryptocurrency mining requires Google's prior written approval."
        ),
        Provider(
            name = "Google Colab managed runtimes",
            policy = MiningPolicy.PROHIBITED,
            policyUrl = "https://research.google.com/colaboratory/faq.html",
            note = "Mining cryptocurrency is disallowed on all managed Colab runtimes.",
            isFreeOffer = true
        ),
        Provider(
            name = "Kaggle",
            policy = MiningPolicy.PROHIBITED,
            policyUrl = "https://www.kaggle.com/terms",
            note = "Terms of Use expressly prohibit cryptomining.",
            isFreeOffer = true
        ),
        Provider(
            name = "AWS Free Tier offers",
            policy = MiningPolicy.PROHIBITED,
            policyUrl = "https://aws.amazon.com/free/terms/",
            note = "Free Tier offers prohibit crypto mining and may incur standard charges or suspension.",
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
            note = "The India Cloud Services Agreement prohibits crypto-currency mining."
        ),
        Provider(
            name = "Azure Lab Services",
            policy = MiningPolicy.PROHIBITED,
            policyUrl = "https://www.microsoft.com/licensing/terms/en-US/productoffering/MicrosoftAzureServices/OVOVS",
            note = "Microsoft Product Terms expressly prohibit cryptocurrency mining in Azure Lab Services."
        ),
        Provider(
            name = "DigitalOcean",
            policy = MiningPolicy.WRITTEN_APPROVAL_REQUIRED,
            policyUrl = "https://www.digitalocean.com/legal/acceptable-use-policy",
            note = "Mining requires explicit written permission; no free mining quota has been verified."
        )
    )

    /**
     * Evidence is checked for the selected provider, not inferred from its name
     * or from the existence of a free trial. This method does not provision resources.
     */
    fun canStartWorker(
        provider: Provider,
        writtenApprovalVerified: Boolean,
        userAuthorized: Boolean
    ): Boolean {
        val policyAllowsMining = when (provider.policy) {
            MiningPolicy.PROHIBITED, MiningPolicy.NOT_VERIFIED -> false
            MiningPolicy.WRITTEN_APPROVAL_REQUIRED -> writtenApprovalVerified
            MiningPolicy.EXPRESSLY_ALLOWED -> true
        }
        return policyAllowsMining &&
            provider.freeMiningQuotaVerified &&
            provider.provisioningApiVerified &&
            userAuthorized
    }

    fun summary(): String = buildString {
        append("Cloud auto-connect: LOCKED\n")
        append("Verified free + mining-permitted providers: ")
        append(providers.count {
            it.policy == MiningPolicy.EXPRESSLY_ALLOWED &&
                it.isFreeOffer && it.freeMiningQuotaVerified && it.provisioningApiVerified
        })
        append("\nFree offers with mining prohibited: ")
        append(providers.count { it.isFreeOffer && it.policy == MiningPolicy.PROHIBITED })
        append("\nProviders requiring written approval: ")
        append(providers.count { it.policy == MiningPolicy.WRITTEN_APPROVAL_REQUIRED })
        append("\nVerified provider provisioning integrations: ")
        append(providers.count { it.provisioningApiVerified })
        append("\nOAuth login: NOT AVAILABLE")
        append("\nNo cloud instance is provisioned; paid usage is blocked.")
    }
}
