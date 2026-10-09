package com.mtgscanbuild

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.auth.FirebaseAuth
import com.mtgscanbuild.data.AdAudience
import com.mtgscanbuild.ui.access
import com.mtgscanbuild.ui.settings
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProAccountNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun prepareSignedOutBasicAccount() {
        compose.runOnIdle {
            compose.activity.application.settings.adAudience = AdAudience.UNDER_13
            FirebaseAuth.getInstance().signOut()
        }
    }

    @Test
    fun googleAccountEntryDoesNotEnableCheckoutOrUnlockBasic() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Compare Basic / Pro").performClick()
        compose.onNodeWithText("Your plan: Basic").assertIsDisplayed()
        compose.onNodeWithText("Sign in with Google").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Delete app account").assertDoesNotExist()
        compose.onNodeWithText("Optional during setup. Sign-in does not unlock Pro or upload your collection.")
            .assertIsDisplayed()
        compose.onNodeWithText("Purchase unavailable - setup pending").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertFalse(compose.activity.application.access.hasPro)
        }
    }
}
