package com.mtgscanbuild

import com.mtgscanbuild.data.PlanAccess
import com.mtgscanbuild.data.ProFeature
import com.mtgscanbuild.data.ProRequiredException
import com.mtgscanbuild.data.StartPage
import com.mtgscanbuild.data.MoxfieldApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlanAccessTest {
    @Test
    fun basicAllowsExactlyFiveFolders() {
        val basic = PlanAccess(false, false)
        for (count in 0..4) {
            assertTrue(basic.canCreateFolder(count))
            basic.requireNewFolder(count)
        }
        for (count in listOf(5, 6, 100)) {
            assertFalse(basic.canCreateFolder(count))
            assertThrows(ProRequiredException::class.java) { basic.requireNewFolder(count) }
        }
    }

    @Test
    fun developerUnlocksAllProFeaturesAndUnlimitedFolders() {
        val developer = PlanAccess(true)
        assertTrue(developer.hasPro)
        ProFeature.entries.forEach { developer.requirePro(it) }
        assertTrue(developer.canCreateFolder(10000))
        developer.requireNewFolder(10000)
    }

    @Test
    fun basicCannotUseAnyPremiumOperation() {
        val basic = PlanAccess(false, false)
        assertFalse(basic.hasPro)
        ProFeature.entries.forEach { feature ->
            assertThrows(ProRequiredException::class.java) { basic.requirePro(feature) }
        }
    }

    @Test
    fun publicBuildNeverUsesDebuggableFlagAsAnUnlock() {
        assertEquals(BuildConfig.FLAVOR == "developer", PlanAccess().developerUnlocked)
        assertEquals(BuildConfig.FLAVOR == "developer", PlanAccess().adFree)
        assertEquals(PlanAccess.ALL_FEATURES_UNLOCKED || BuildConfig.FLAVOR == "developer", PlanAccess().hasPro)
        assertFalse(PlanAccess.CHECKOUT_ENABLED)
    }

    @Test
    fun basicAlwaysOpensHomeEvenWithStoredProPreference() {
        StartPage.entries.forEach { page ->
            assertEquals(StartPage.HOME, PlanAccess(false, false).openingPage(page))
            assertEquals(page, PlanAccess(true).openingPage(page))
        }
        assertEquals(StartPage.entries.size, StartPage.entries.map { it.route }.distinct().size)
    }

    @Test
    fun basicMoxfieldCallsFailBeforeNetworkEvenForInvalidLinks() = runBlocking {
        val api = MoxfieldApi(PlanAccess(false, false))
        try {
            api.search("modern")
            fail("Expected a Pro gate")
        } catch (e: ProRequiredException) {
            assertTrue(e.message!!.contains("Moxfield"))
        }
        try {
            api.deck("not-a-deck")
            fail("Expected a Pro gate")
        } catch (e: ProRequiredException) {
            assertTrue(e.message!!.contains("Moxfield"))
        }
    }
}
