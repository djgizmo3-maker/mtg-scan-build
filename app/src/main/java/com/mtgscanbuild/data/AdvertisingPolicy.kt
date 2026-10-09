package com.mtgscanbuild.data

object AdvertisingPolicy {
    fun mayRequestAds(hasPro: Boolean, audience: AdAudience, buildEnabled: Boolean): Boolean =
        buildEnabled && !hasPro && (audience == AdAudience.TEEN || audience == AdAudience.ADULT)

    fun showsBanner(route: String?, hasPro: Boolean): Boolean =
        route != null && route != "scan" && !hasPro
}
