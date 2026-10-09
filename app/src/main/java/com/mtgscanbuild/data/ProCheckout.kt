package com.mtgscanbuild.data

import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase

internal fun completedProTokens(purchases: List<Purchase>): List<String> = purchases
    .filter { PlanAccess.PRO_PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
    .map { it.purchaseToken }.distinct()

internal fun hasPendingProPayment(purchases: List<Purchase>): Boolean = purchases.any {
    PlanAccess.PRO_PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PENDING
}

internal data class ProCheckoutOffer(
    val purchaseOptionId: String?,
    val offerId: String?,
    val offerToken: String?,
    val rental: Boolean,
    val price: String,
) {
    companion object {
        fun select(offers: List<ProCheckoutOffer>): ProCheckoutOffer? = offers.singleOrNull {
            it.purchaseOptionId == PlanAccess.PRO_PURCHASE_OPTION_ID &&
                it.offerId == null && !it.rental && !it.offerToken.isNullOrBlank()
        }

        fun from(product: ProductDetails?): ProCheckoutOffer? {
            if (product?.productId != PlanAccess.PRO_PRODUCT_ID ||
                product.productType != "inapp") return null
            return select(product.oneTimePurchaseOfferDetailsList.orEmpty().map {
                ProCheckoutOffer(it.purchaseOptionId, it.offerId, it.offerToken,
                    it.rentalDetails != null, it.formattedPrice)
            })
        }
    }
}
