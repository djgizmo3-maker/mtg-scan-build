package com.mtgscanbuild

import com.mtgscanbuild.data.PlanAccess
import com.mtgscanbuild.data.ProCheckoutOffer
import com.mtgscanbuild.data.ProPurchaseAccount
import com.mtgscanbuild.data.completedProTokens
import com.mtgscanbuild.data.hasPendingProPayment
import com.android.billingclient.api.Purchase
import org.junit.Assert.*
import org.junit.Test

class ProCheckoutTest {
    private val offer = ProCheckoutOffer("pro-unlock", null, "play-offer-token", false, "$4.99")

    @Test
    fun checkoutSelectsOnlyThePermanentBasePurchaseOption() {
        assertEquals(offer, ProCheckoutOffer.select(listOf(
            offer.copy(purchaseOptionId = "another-option"),
            offer.copy(offerId = "discount"),
            offer.copy(rental = true),
            offer,
        )))
        assertNull(ProCheckoutOffer.select(listOf(offer.copy(purchaseOptionId = "another-option"))))
        assertNull(ProCheckoutOffer.select(listOf(offer.copy(rental = true))))
        assertNull(ProCheckoutOffer.select(listOf(offer.copy(offerId = "discount"))))
    }

    @Test
    fun missingOrAmbiguousOfferTokensCannotStartCheckout() {
        assertNull(ProCheckoutOffer.select(emptyList()))
        assertNull(ProCheckoutOffer.select(listOf(offer, offer.copy(offerToken = "second-token"))))
        for (token in listOf(null, "", " ")) {
            assertNull(ProCheckoutOffer.select(listOf(offer.copy(offerToken = token))))
        }
    }

    @Test
    fun checkoutIdentityMustBeTheExactServerIssuedHash() {
        val binding = "a".repeat(64)
        val account = ProPurchaseAccount.parse(mapOf("obfuscatedAccountId" to binding), "firebase-user", 3)
        assertEquals(binding, account.obfuscatedAccountId)
        assertEquals("firebase-user", account.uid)
        assertEquals(3L, account.generation)
        for (value in listOf(null, 42, "", "a".repeat(63), "A".repeat(64), "g".repeat(64), " " + binding)) {
            assertThrows(IllegalArgumentException::class.java) {
                ProPurchaseAccount.parse(mapOf("obfuscatedAccountId" to value), "firebase-user", 3)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProPurchaseAccount.parse(emptyMap<String, String>(), "firebase-user", 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProPurchaseAccount.parse(mapOf("obfuscatedAccountId" to binding), "", 3)
        }
    }

    @Test
    fun checkoutGateCannotUnlockProOrEnableDeveloperCheckout() {
        assertFalse(PlanAccess.CHECKOUT_ENABLED)
        assertFalse(PlanAccess(true).checkoutEnabled)
        assertEquals(BuildConfig.INTERNAL_PRO_CHECKOUT, PlanAccess(false, false).checkoutEnabled)
        assertFalse(PlanAccess(false, false).hasPro)
        if (BuildConfig.BUILD_TYPE != "internalTesting") assertFalse(BuildConfig.INTERNAL_PRO_CHECKOUT)
        assertFalse(BuildConfig.PRODUCTION_ADS_ENABLED)
    }

    @Test
    fun onlyCompletedProTokensReachVerificationAndPendingPaymentsRemainSeparate() {
        fun purchase(product: String, token: String, state: Int) = Purchase(
            """{"productId":"$product","purchaseToken":"$token","purchaseState":$state}""", "test-signature"
        )
        val completed = purchase(PlanAccess.PRO_PRODUCT_ID, "completed-token", 0)
        val pending = purchase(PlanAccess.PRO_PRODUCT_ID, "pending-token", 4)
        val unrelated = purchase("other-product", "unrelated-token", 0)
        assertEquals(Purchase.PurchaseState.PURCHASED, completed.purchaseState)
        assertEquals(Purchase.PurchaseState.PENDING, pending.purchaseState)
        assertEquals(listOf("completed-token"), completedProTokens(listOf(completed, pending, unrelated, completed)))
        assertTrue(hasPendingProPayment(listOf(completed, pending)))
        assertFalse(hasPendingProPayment(listOf(completed, unrelated)))
        assertTrue(completedProTokens(listOf(pending)).isEmpty())
    }
}
