package com.mtgscanbuild

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Intent
import com.mtgscanbuild.data.StartPage
import com.mtgscanbuild.ui.settings
import com.mtgscanbuild.ui.repo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Before
import com.mtgscanbuild.data.AdAudience
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlanNavigationTest {
    private val UNLOCKED = BuildConfig.DEVELOPER_UNLOCK || com.mtgscanbuild.data.PlanAccess.ALL_FEATURES_UNLOCKED

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun disableAdsForNavigationRegression() {
        compose.runOnIdle { compose.activity.application.settings.adAudience = AdAudience.UNDER_13 }
    }

    @Test
    fun comparisonShowsActualPlanAndPublicCheckoutIsDisabled() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Compare Basic / Pro").performClick()
        if (UNLOCKED) {
            compose.onNodeWithText("Your plan: Developer - fully unlocked").assertIsDisplayed()
            compose.onNodeWithText("Developer testing build").performScrollTo().assertIsDisplayed()
        } else {
            compose.onNodeWithText("Your plan: Basic").assertIsDisplayed()
            compose.onNodeWithText("Purchase unavailable - setup pending").performScrollTo().assertIsNotEnabled()
        }
    }

    @Test
    fun deckGenerationIsGatedInPublicAndUnlockedInDeveloper() {
        compose.onNodeWithText("Decks").performClick()
        val action = if (UNLOCKED) "Build a deck" else "Generate deck (Pro)"
        compose.onNodeWithContentDescription(action).assertIsDisplayed().performClick()
        if (UNLOCKED) {
            compose.onNodeWithText("Format").assertIsDisplayed()
        } else {
            compose.onNodeWithText("Deck generation").assertIsDisplayed()
            compose.onNodeWithText("View Basic / Pro plans").performClick()
            compose.onNodeWithText("Your plan: Basic").assertIsDisplayed()
        }
    }

    @Test
    fun moxfieldEntryUsesTheSamePlanGate() {
        compose.onNodeWithText("Decks").performClick()
        compose.onNodeWithText(if (UNLOCKED) "Moxfield" else "Moxfield (Pro)").performClick()
        if (UNLOCKED) {
            compose.onNodeWithText("Search Moxfield & compare").assertIsDisplayed()
        } else {
            compose.onNodeWithText("Moxfield comparison").assertIsDisplayed()
        }
    }

    @Test
    fun manualDeckCreationIsAvailableInBothPlans() {
        val name = "Manual test ${UUID.randomUUID()}"
        val repo = compose.activity.application.repo
        try {
            compose.onNodeWithText("Decks").performClick()
            compose.onNodeWithText("New deck").performClick()
            compose.onNodeWithText("Deck name").performTextInput(name)
            compose.onNodeWithText("Create").performClick()
            compose.waitForIdle()
            compose.waitUntil(10000) {
                compose.onAllNodesWithContentDescription("Share").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            compose.onNodeWithText(name).assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForIdle()
        } finally {
            runBlocking { repo.savedDecks.first().filter { it.name == name }.forEach { repo.deleteDeck(it.id) } }
        }
    }

    @Test
    fun choosingOpeningPageIsProOnlyAndTakesEffectOnFreshLaunch() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Opening page").performClick()
        if (!UNLOCKED) {
            compose.onNodeWithText("Your plan: Basic").assertIsDisplayed()
        } else {
            val settings = compose.activity.application.settings
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            var launched: android.app.Activity? = null
            try {
                compose.onAllNodesWithText("Collection").onLast().performClick()
                compose.onAllNodesWithText("Settings").onLast().assertIsDisplayed()
                launched = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                compose.onNodeWithText("User Created Sets").assertIsDisplayed()
            } finally {
                instrumentation.runOnMainSync {
                    settings.startPage = StartPage.HOME
                    launched?.finish()
                }
            }
        }
    }
}
