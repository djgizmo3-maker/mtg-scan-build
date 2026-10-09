package com.mtgscanbuild

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtgscanbuild.data.Accent
import com.mtgscanbuild.data.AppSettings
import com.mtgscanbuild.data.ThemeMode
import com.mtgscanbuild.ui.repo
import com.mtgscanbuild.ui.settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Before
import com.mtgscanbuild.data.AdAudience
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ManaThemeNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun disableAdsForNavigationRegression() {
        compose.runOnIdle { compose.activity.application.settings.adAudience = AdAudience.UNDER_13 }
    }

    @Test
    fun everyThemeUpdatesLiveInBothDisplayModesAndPersists() {
        val settings = compose.activity.application.settings
        val previousAccent = settings.accent
        val previousMode = settings.themeMode
        try {
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Mana theme").performScrollTo().performClick()
            compose.onAllNodesWithText("Green mana - Forest").onLast().performClick()
            compose.onNodeWithTag("mana-wallpaper-FOREST").assertIsDisplayed()
            assertEquals(Accent.FOREST, settings.accent)
            for (mode in ThemeMode.entries) {
                for (accent in Accent.entries) {
                    compose.runOnIdle { settings.themeMode = mode; settings.accent = accent }
                    compose.onNodeWithTag("mana-wallpaper-${accent.land.name}").assertIsDisplayed()
                    assertEquals(accent, AppSettings(compose.activity).accent)
                    assertEquals(mode, AppSettings(compose.activity).themeMode)
                }
            }
        } finally {
            compose.runOnIdle { settings.accent = previousAccent; settings.themeMode = previousMode }
        }
    }

    @Test
    fun wallpaperCoversHomeTabsMainPagesAndDetailsButNeverScan() {
        val app = compose.activity.application
        val settings = app.settings
        val previousAccent = settings.accent
        val name = "Wallpaper test ${UUID.randomUUID()}"
        val id = runBlocking { app.repo.createManualDeck(name, "standard") }
        fun wallpaper() = compose.onNodeWithTag("mana-wallpaper-ISLAND").assertIsDisplayed()
        try {
            compose.runOnIdle { settings.accent = Accent.ISLAND }
            wallpaper()
            compose.onNodeWithText("Rulebook").performClick()
            wallpaper()
            compose.onNodeWithText("Banned / Restricted").performClick()
            wallpaper()
            compose.onNodeWithText("Collection").performClick()
            wallpaper()
            compose.onNodeWithText("Decks").performClick()
            wallpaper()
            compose.onNodeWithText(name).performScrollTo().performClick()
            compose.onNodeWithContentDescription("Share").assertIsDisplayed()
            wallpaper()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Settings").performClick()
            wallpaper()
            compose.onNodeWithText("Compare Basic / Pro").performClick()
            wallpaper()
            compose.onNodeWithContentDescription("Back").performClick()
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                "pm grant ${compose.activity.packageName} android.permission.CAMERA",
            )).use { it.readBytes() }
            compose.onNodeWithText("Scan").performClick()
            compose.onNodeWithTag("mana-wallpaper-ISLAND").assertDoesNotExist()
            compose.onAllNodesWithText("Home").onLast().performClick()
            wallpaper()
        } finally {
            compose.runOnIdle { settings.accent = previousAccent }
            runBlocking { app.repo.deleteDeck(id) }
        }
    }
}
