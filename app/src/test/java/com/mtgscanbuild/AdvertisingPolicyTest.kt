package com.mtgscanbuild

import com.mtgscanbuild.data.AdAudience
import com.mtgscanbuild.data.AdvertisingPolicy
import org.junit.Assert.*
import org.junit.Test

class AdvertisingPolicyTest {
    @Test
    fun adsRequireBasicEnabledBuildAndKnownEligibleAudience() {
        for (pro in listOf(false, true)) {
            for (enabled in listOf(false, true)) {
                for (audience in AdAudience.entries) {
                    assertEquals(!pro && enabled && audience in listOf(AdAudience.TEEN, AdAudience.ADULT),
                        AdvertisingPolicy.mayRequestAds(pro, audience, enabled))
                }
            }
        }
    }

    @Test
    fun scanAndProNeverShowBanner() {
        assertFalse(AdvertisingPolicy.showsBanner(null, false))
        assertFalse(AdvertisingPolicy.showsBanner("scan", false))
        for (route in listOf("home", "collection", "decks", "settings", "card/{id}", "deck/{id}", "pro", "builder", "moxfield")) {
            assertTrue(AdvertisingPolicy.showsBanner(route, false))
            assertFalse(AdvertisingPolicy.showsBanner(route, true))
        }
    }
}
