package com.lottery.miner

/**
 * Conservative cloud-compute eligibility gate.
 *
 * A provider is never launched just because it offers free compute. Mining must be
 * expressly permitted, free quota must be verified, and the user must authorize
 * the provider through its official flow. No credentials or tokens belong here.
 */
object CloudProviderRegistry {

    enum class MiningPolicy { PROHIBITED, WRITTEN_APPROVAL_REQUIRED, NOT_VERIFIED }

    data class Provider(
        val name: String,
        val policy: MiningPolicy,
        val policyUrl: String,
        val note: String
    )

    // Only include providers whose published policy can be identified.
    // Neither listed provider is eligible to run a mining worker.
    val providers = listOf(
        Provider(
            name = "Hugging Face",
            policy = MiningPolicy.PROHIBITED,
            policyUrl = "https://huggingface.co/content-policy",
            note = "Cryptomining practices are restricted."
        ),
        Provider(
            name = "NVIDIA Cloud Services",
            policy = MiningPolicy.PROHIBITED,
            policyUrl = "https://www.nvidia.com/content/dam/en-zz/Solutions/license-agreements/cloud-services/NVIDIA-Cloud-Agreement-2024-06-13.pdf",
            note = "Cloud agreement prohibits crypto mining."
        )
    )

    /** Hard fail-closed rule: no worker starts without all three checks. */
    fun canStartWorker(
        miningExplicitlyAllowed: Boolean,
        freeQuotaVerified: Boolean,
        userAuthorized: Boolean
    ): Boolean = miningExplicitlyAllowed && freeQuotaVerified && userAuthorized

    fun summary(): String {
        val eligible = providers.count { it.policy == MiningPolicy.NOT_VERIFIED }
        return buildString {
            append("Automatic cloud workers: DISABLED (safe default)\n")
            append("Verified free + mining-permitted providers: 0\n")
            append("Policy-blocked providers checked: ")
            append(providers.count { it.policy == MiningPolicy.PROHIBITED })
            append("\n")
            append("No cloud instance will be created and no paid quota can be consumed.")
            if (eligible > 0) append("\nProviders awaiting verification: ").append(eligible)
            append("\nOfficial policy checks are required before adding any provider.")
        }
    }
}
