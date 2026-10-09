package com.mtgscanbuild

import com.mtgscanbuild.data.PlanAccess
import com.mtgscanbuild.data.ProFeature
import com.mtgscanbuild.data.ProRequiredException
import com.mtgscanbuild.data.PRO_OFFLINE_WINDOW_MS
import com.mtgscanbuild.data.StartPage
import com.mtgscanbuild.data.VerifiedProResponse
import org.junit.Assert.*
import org.junit.Test

class ProEntitlementTest {
    private val verifiedAt = 1_800_000_000_000L
    private fun response() = mapOf(
        "hasPro" to true, "verifiedAt" to verifiedAt,
        "serverTime" to verifiedAt + 1000,
        "expiresAt" to verifiedAt + PRO_OFFLINE_WINDOW_MS,
    )

    @Test
    fun exactServerExpiryUsesMonotonicRequestStartNotPhoneDate() {
        val lease = VerifiedProResponse.parse(response())
        assertEquals(10_000L + PRO_OFFLINE_WINDOW_MS - 1000, lease.deadline(10_000))
    }

    @Test
    fun malformedOrExtendedLeasesFailClosed() {
        val invalid = listOf(
            null, true, emptyMap<String, Any>(),
            response() + ("hasPro" to "true"),
            response() + ("serverTime" to Double.NaN),
            response() + ("verifiedAt" to 0L),
            response() + ("expiresAt" to verifiedAt + PRO_OFFLINE_WINDOW_MS + 1),
            response() + ("serverTime" to verifiedAt - 1),
            response() + ("serverTime" to verifiedAt + PRO_OFFLINE_WINDOW_MS),
            response() + ("expiresAt" to null),
            response() + ("serverTime" to 1.5),
        )
        invalid.forEach { data ->
            assertThrows(IllegalArgumentException::class.java) { VerifiedProResponse.parse(data) }
        }
    }

    @Test
    fun basicResponseCannotContainAHiddenProLease() {
        val basic = mapOf("hasPro" to false, "verifiedAt" to null, "expiresAt" to null, "serverTime" to verifiedAt)
        assertNull(VerifiedProResponse.parse(basic).deadline(0))
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedProResponse.parse(basic + ("expiresAt" to verifiedAt + PRO_OFFLINE_WINDOW_MS))
        }
    }

    @Test
    fun serverLeaseUnlocksEveryGateThenExpiresAtExactDeadline() {
        var elapsed = 100L
        val access = PlanAccess(false, false) { elapsed }
        access.setVerifiedDeadline(200)
        assertTrue(access.hasPro)
        assertEquals("Pro", access.label)
        assertTrue(access.canCreateFolder(1000))
        assertEquals(StartPage.SCAN, access.openingPage(StartPage.SCAN))
        ProFeature.entries.forEach { access.requirePro(it) }
        elapsed = 199
        assertTrue(access.hasPro)
        elapsed = 200
        assertFalse(access.hasPro)
        assertTrue(access.expireVerifiedDeadline())
        assertFalse(access.expireVerifiedDeadline())
        assertEquals("Basic", access.label)
        assertFalse(access.canCreateFolder(5))
        assertEquals(StartPage.HOME, access.openingPage(StartPage.SCAN))
        ProFeature.entries.forEach {
            assertThrows(ProRequiredException::class.java) { access.requirePro(it) }
        }
    }

    @Test
    fun signOutOrRevocationClearsLeaseButNeverDeveloperUnlock() {
        val access = PlanAccess(false, false) { 100 }
        access.setVerifiedDeadline(1000)
        assertTrue(access.hasPro)
        access.setVerifiedDeadline(null)
        assertFalse(access.hasPro)
        val developer = PlanAccess(true) { 100 }
        developer.setVerifiedDeadline(null)
        assertTrue(developer.hasPro)
        assertEquals("Developer - fully unlocked", developer.label)
    }
}
