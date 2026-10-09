package com.mtgscanbuild.data

internal const val PRO_OFFLINE_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

internal data class VerifiedProResponse(
    val hasPro: Boolean,
    val verifiedAt: Long?,
    val expiresAt: Long?,
    val serverTime: Long,
    val verificationIncomplete: Boolean = false,
) {
    fun deadline(requestStarted: Long): Long? {
        if (!hasPro) return null
        val expires = requireNotNull(expiresAt)
        val remaining = expires - serverTime
        require(requestStarted >= 0 && requestStarted <= Long.MAX_VALUE - remaining)
        return requestStarted + remaining
    }

    companion object {
        fun parse(data: Any?): VerifiedProResponse {
            require(data is Map<*, *>) { "Invalid entitlement response." }
            val hasPro = data["hasPro"]
            require(hasPro is Boolean) { "Missing entitlement state." }
            fun timestamp(key: String): Long {
                val value = data[key]
                require(value is Number) { "Invalid $key." }
                val number = value.toDouble()
                require(number.isFinite() && number >= 0 && number <= 9_007_199_254_740_991.0 &&
                    number == value.toLong().toDouble()) { "Invalid $key." }
                return value.toLong()
            }
            val serverTime = timestamp("serverTime")
            val incomplete = data["verificationIncomplete"]
            require(incomplete == null || incomplete is Boolean) { "Invalid verification result." }
            if (!hasPro) {
                require(data.containsKey("verifiedAt") && data["verifiedAt"] == null &&
                    data.containsKey("expiresAt") && data["expiresAt"] == null)
                return VerifiedProResponse(false, null, null, serverTime, incomplete == true)
            }
            val verifiedAt = timestamp("verifiedAt")
            val expiresAt = timestamp("expiresAt")
            require(verifiedAt <= serverTime && expiresAt > serverTime &&
                expiresAt - verifiedAt == PRO_OFFLINE_WINDOW_MS) { "Invalid Pro expiry." }
            return VerifiedProResponse(true, verifiedAt, expiresAt, serverTime, incomplete == true)
        }
    }
}

interface ProEntitlements {
    val signedIn: Boolean
    val working: Boolean
    val message: String?
    fun refreshOnResume()
    fun clearLocalAccess()
    suspend fun refresh()
    suspend fun verifyPurchases(tokens: List<String>)
    suspend fun purchaseAccount(): ProPurchaseAccount?
    fun isCurrentPurchaseAccount(account: ProPurchaseAccount): Boolean
}

class ProPurchaseAccount internal constructor(
    val obfuscatedAccountId: String,
    internal val uid: String,
    internal val generation: Long,
) {
    companion object {
        internal fun parse(data: Any?, uid: String, generation: Long): ProPurchaseAccount {
            require(data is Map<*, *>)
            val binding = data["obfuscatedAccountId"]
            require(binding is String && binding.matches(Regex("[a-f0-9]{64}")))
            require(uid.isNotBlank())
            return ProPurchaseAccount(binding, uid, generation)
        }
    }
}
