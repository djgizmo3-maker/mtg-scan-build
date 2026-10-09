package com.mtgscanbuild.data

import com.mtgscanbuild.BuildConfig
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ProFeature(val label: String) {
    UNLIMITED_FOLDERS("Unlimited collection folders"),
    DECK_GENERATION("Deck generation"),
    MOXFIELD("Moxfield comparison"),
    CSV("CSV / list import and CSV export"),
    START_PAGE("Choose your opening page"),
}

class ProRequiredException(feature: ProFeature) : IllegalStateException("${feature.label} requires Pro.")

/** Only the backend entitlement coordinator supplies a public-build lease. */
class PlanAccess(
    val developerUnlocked: Boolean = BuildConfig.DEVELOPER_UNLOCK,
    val featuresUnlocked: Boolean = ALL_FEATURES_UNLOCKED,
    private val elapsedTime: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var proDeadline by mutableStateOf<Long?>(null)
    val adFree: Boolean get() = developerUnlocked || proDeadline?.let { elapsedTime() < it } == true
    val hasPro: Boolean get() = featuresUnlocked || adFree
    val checkoutEnabled: Boolean get() = !developerUnlocked && !featuresUnlocked && (CHECKOUT_ENABLED || BuildConfig.INTERNAL_PRO_CHECKOUT)
    val label: String get() = when {
        developerUnlocked -> "Developer - fully unlocked"
        featuresUnlocked && !adFree -> "Unlocked"
        hasPro -> "Pro"
        else -> "Basic"
    }

    internal fun setVerifiedDeadline(deadline: Long?) {
        proDeadline = deadline
    }

    internal fun expireVerifiedDeadline(): Boolean {
        if (proDeadline != null && !adFree) {
            proDeadline = null
            return true
        }
        return false
    }

    fun requirePro(feature: ProFeature) {
        if (!hasPro) throw ProRequiredException(feature)
    }

    fun canCreateFolder(currentCount: Int): Boolean = hasPro || currentCount < BASIC_FOLDER_LIMIT

    fun requireNewFolder(currentCount: Int) {
        if (!canCreateFolder(currentCount)) throw ProRequiredException(ProFeature.UNLIMITED_FOLDERS)
    }

    fun openingPage(preference: StartPage): StartPage = if (hasPro) preference else StartPage.HOME

    companion object {
        const val BASIC_FOLDER_LIMIT = 5
        const val PRO_PRODUCT_ID = "mtg_pro_unlock"
        const val PRO_PURCHASE_OPTION_ID = "pro-unlock"
        const val CHECKOUT_ENABLED = false
        /** Every feature is free while Pro is paused; set to false to restore the Basic/Pro split. */
        const val ALL_FEATURES_UNLOCKED = true
    }
}

enum class StartPage(val label: String, val route: String) {
    HOME("Home", "home"),
    SCAN("Scan", "scan"),
    COLLECTION("Collection", "collection"),
    DECKS("Decks", "decks"),
    SETTINGS("Settings", "settings"),
}
