package com.mtgscanbuild

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Before
import com.mtgscanbuild.data.AdAudience
import com.mtgscanbuild.ui.settings
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun disableAdsForNavigationRegression() {
        compose.runOnIdle { compose.activity.application.settings.adAudience = AdAudience.UNDER_13 }
    }

    @Test
    fun freshLaunchStartsOnHomeAndExistingTabsStillWork() {
        compose.onNodeWithText("Latest MTG news").assertIsDisplayed()
        compose.onNodeWithText("Collection").performClick()
        compose.onNodeWithText("User Created Sets").assertIsDisplayed()
        compose.onAllNodesWithText("Home").onLast().performClick()
        compose.onNodeWithText("Latest MTG news").assertIsDisplayed()
    }

    @Test
    fun bansAreGroupedByMatchTypeAndFormat() {
        compose.onNodeWithText("Banned / Restricted").performClick()
        compose.onNodeWithText("Match type: Tabletop / 1v1").assertIsDisplayed().performClick()
        compose.onNodeWithText("MTG Arena").performClick()
        compose.onNodeWithText("Format: Alchemy").assertIsDisplayed().performClick()
        compose.onNodeWithText("Timeless").performClick()
        compose.onNodeWithText("Format: Timeless").assertIsDisplayed()
        compose.onNodeWithText("Timeless legality").assertIsDisplayed()
    }

    @Test
    fun rulesTabProvidesOfficialDocumentsAndLimitedModes() {
        compose.onNodeWithText("Rulebook").performClick()
        compose.onNodeWithText("Full Comprehensive Rules (PDF / text)").assertIsDisplayed()
        compose.onNodeWithText("Match type: Multiplayer").performScrollTo().performClick()
        compose.onNodeWithText("Limited").performClick()
        compose.onNodeWithText("Format: Booster Draft").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open official format rules").performScrollTo().assertIsDisplayed()
    }
}
