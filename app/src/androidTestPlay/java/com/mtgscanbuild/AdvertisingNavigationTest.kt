package com.mtgscanbuild

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.mtgscanbuild.data.AdAudience
import com.mtgscanbuild.ui.settings
import com.mtgscanbuild.ui.access
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdvertisingNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun debugOnlyUsesGoogleTestIdsAndProductionAdsAreDisabled() {
        assertEquals("ca-app-pub-3940256099942544/6300978111", BuildConfig.BANNER_AD_UNIT_ID)
        val context = compose.activity
        @Suppress("DEPRECATION")
        val info = context.packageManager.getApplicationInfo(context.packageName,
            android.content.pm.PackageManager.GET_META_DATA)
        assertEquals("ca-app-pub-3940256099942544~3347511713",
            info.metaData.getString("com.google.android.gms.ads.APPLICATION_ID"))
        assertFalse(BuildConfig.PRODUCTION_ADS_ENABLED)
    }

    @Test
    fun unknownAndUnderThirteenNeverRequestOrDisplayAdsAndCategoryCanBeChanged() {
        val settings = compose.activity.application.settings
        val previous = settings.adAudience
        try {
            compose.runOnIdle { settings.adAudience = AdAudience.UNSET }
            compose.onNodeWithText("Under 13").assertIsDisplayed()
            compose.onNodeWithTag("basic-ad-banner").assertDoesNotExist()
            compose.onNodeWithText("Under 13").performClick()
            compose.onNodeWithText("Latest MTG news").assertIsDisplayed()
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Age category: Under 13").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("basic-ad-banner").assertDoesNotExist()
            compose.onNodeWithText("Change age category").performScrollTo().performClick()
            compose.onNodeWithText("Under 13").assertIsDisplayed().performClick()
            compose.onAllNodesWithText("Home").onLast().performClick()
            compose.onNodeWithTag("basic-ad-banner").assertDoesNotExist()
        } finally {
            compose.runOnIdle { settings.adAudience = previous }
        }
    }

    @Test
    fun teenTestBannerIsSeparatedFromContentAndRemovedOnScan() {
        val settings = compose.activity.application.settings
        val previous = settings.adAudience
        try {
            compose.runOnIdle { settings.adAudience = AdAudience.TEEN }
            compose.waitUntil(60000) {
                compose.onAllNodesWithTag("basic-ad-banner").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("basic-ad-banner").assertIsDisplayed()
            compose.onNodeWithText("Advertisement").assertIsDisplayed()
            compose.onNodeWithText("Latest MTG news").assertIsDisplayed()
            compose.runOnIdle {
                compose.activity.application.access.setVerifiedDeadline(android.os.SystemClock.elapsedRealtime() + 60000)
            }
            compose.onNodeWithTag("basic-ad-banner").assertDoesNotExist()
            compose.runOnIdle { compose.activity.application.access.setVerifiedDeadline(null) }
            compose.waitUntil(60000) {
                compose.onAllNodesWithTag("basic-ad-banner").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("basic-ad-banner").assertIsDisplayed()
            @Suppress("DEPRECATION")
            assertEquals(RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_TRUE,
                MobileAds.getRequestConfiguration().tagForUnderAgeOfConsent)
            assertEquals(RequestConfiguration.MAX_AD_CONTENT_RATING_PG,
                MobileAds.getRequestConfiguration().maxAdContentRating)
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                "pm grant ${compose.activity.packageName} android.permission.CAMERA",
            )).use { it.readBytes() }
            compose.onNodeWithText("Scan").performClick()
            compose.onNodeWithTag("basic-ad-banner").assertDoesNotExist()
            compose.onAllNodesWithText("Home").onLast().performClick()
            compose.onNodeWithTag("basic-ad-banner").assertIsDisplayed()
            compose.runOnIdle { settings.adAudience = AdAudience.UNDER_13 }
            compose.onNodeWithTag("basic-ad-banner").assertDoesNotExist()
        } finally {
            compose.runOnIdle {
                compose.activity.application.access.setVerifiedDeadline(null)
                settings.adAudience = previous
            }
        }
    }
}
