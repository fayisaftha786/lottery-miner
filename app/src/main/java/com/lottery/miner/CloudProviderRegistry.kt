package com.lottery.miner

/**
 * Fail-closed cloud provider eligibility registry.
 * A free tier is not mining permission. Never store provider passwords or tokens here.
 */
object CloudProviderRegistry {

    enum class MiningPolicy {
        PROHIBITED,
        WRITTEN_APPROVAL_REQUIRED,
        NOT_VERIFIED
    }

    data class Provider(
        val name: String,
        val policy: MiningPolicy,
        val policyUrl: String,
        val note: String
    )

    val providers = listOf(
        Provider(
            name = "Google Cloud Free Trial",
            policy = MiningPolicy.WRITTEN_APPROVAL_REQUIRED,
            policyUrl = "https://support.google.com/cloud/answer/7002354",
            note = "Cryptocurrency mining requires Google's prior written approval."
        ),
        Provider(
            name = "Google Colab",
            policy = MiningPolicy.PROHIBITED,
            policyUrl = "https://colab.research.google.com/",
            note = "Managed runtimes prohibit cryptocurrency mining."
        ),
        Provider(
            name = "Kaggle",
            policy = MiningPolicy.PROHIBITED,
            policyUrl = "https://www.kaggle.com/terms",
            note = "Terms expressly prohibit cryptomining."
        ),
        Provider(
            name = "AWS Free Tier",
            policy = MiningPolicy.PROHIBITED,
            policyUrl = "https://aws.amazon.com/free/terms/",
            note = "Free Tier offers may not be used for cryptocurrency mining."
        ),
        Provider(
            name = "Oracle Cloud",
            policy = MiningPolicy.PROHIBITED,
            policyUrl = "https://www.oracle.com/il-en/contracts/docs/cloud_csa_online_v062223_us_eng.pdf",
            note = "Cloud Services Agreement prohibits cryptocurrency mining."
        ),
        Provider(
            name = "Azure Lab Services",
            policy = MiningPolicy.PROHIBITED,
            policyUrl = "https://www.microsoft.com/licensing/terms/en-US/productoffering/MicrosoftAzureServices/OVOVS",
            note = "Azure Lab Services terms prohibit cryptocurrency mining."
        )
    )

    /** A worker can start only when every independent safety condition is true. */
    fun canStartWorker(
        miningExplicitlyAllowed: Boolean,
        freeQuotaVerified: Boolean,
        userAuthorized: Boolean
    ): Boolean = miningExplicitlyAllowed && freeQuotaVerified && userAuthorized

    fun summary(): String = buildString {
        append("Cloud auto-connect: LOCKED\n")
        append("Verified free + mining-permitted providers: 0\n")
        append("Prior written approval required: ")
        append(providers.count { it.policy == MiningPolicy.WRITTEN_APPROVAL_REQUIRED })
        append("\nMining prohibited: ")
        append(providers.count { it.policy == MiningPolicy.PROHIBITED })
        append("\nLogin is unavailable until a provider passes policy and free-quota checks.")
        append("\nNo instance is provisioned; paid usage is blocked.")
    }
}
