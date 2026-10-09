package com.mtgscanbuild

import android.app.Application
import com.mtgscanbuild.data.PlanAccess
import com.mtgscanbuild.data.ProEntitlements
import com.mtgscanbuild.data.ProPurchaseAccount

internal fun createProEntitlements(app: Application, access: PlanAccess): ProEntitlements =
    object : ProEntitlements {
        override val signedIn = false
        override val working = false
        override val message: String? = null
        override fun refreshOnResume() = Unit
        override fun clearLocalAccess() = Unit
        override suspend fun refresh() = Unit
        override suspend fun verifyPurchases(tokens: List<String>) = Unit
        override suspend fun purchaseAccount(): ProPurchaseAccount? = null
        override fun isCurrentPurchaseAccount(account: ProPurchaseAccount) = false
    }
