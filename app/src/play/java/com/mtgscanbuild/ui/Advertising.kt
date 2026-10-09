package com.mtgscanbuild.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.mtgscanbuild.BuildConfig
import com.mtgscanbuild.data.AdAudience
import com.mtgscanbuild.data.AdvertisingPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private class AdvertisingState {
    var ready by mutableStateOf(false)
    var privacyRequired by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var retry by mutableStateOf(0)

    fun report(message: String) {
        error = message
        Log.w("Advertising", message)
    }
}

private val LocalAdvertising = staticCompositionLocalOf<AdvertisingState?> { null }
private val adsBuildEnabled get() = BuildConfig.DEBUG || BuildConfig.PRODUCTION_ADS_ENABLED

@Composable
fun AdvertisingProvider(hasPro: Boolean, content: @Composable () -> Unit) {
    val activity = LocalContext.current as Activity
    val settings = (activity.applicationContext as Application).settings
    val state = remember { AdvertisingState() }
    val scope = rememberCoroutineScope()
    val audience = settings.adAudience
    var initialized by remember { mutableStateOf(false) }
    DisposableEffect(hasPro, audience, state.retry) {
        var active = true
        state.ready = false
        state.error = null
        state.privacyRequired = false
        if (AdvertisingPolicy.mayRequestAds(hasPro, audience, adsBuildEnabled)) {
            val consent = UserMessagingPlatform.getConsentInformation(activity)
            val params = ConsentRequestParameters.Builder()
                .setTagForUnderAgeOfConsent(audience == AdAudience.TEEN).build()
            consent.requestConsentInfoUpdate(activity, params, {
                if (!active) return@requestConsentInfoUpdate
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                    if (!active) return@loadAndShowConsentFormIfRequired
                    if (error != null) state.report("Ad privacy form: ${error.message} (${error.errorCode})")
                    state.privacyRequired = consent.privacyOptionsRequirementStatus ==
                        ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
                    if (consent.canRequestAds()) {
                        scope.launch {
                            if (!active) return@launch
                            MobileAds.setRequestConfiguration(RequestConfiguration.Builder()
                                .setTagForUnderAgeOfConsent(if (audience == AdAudience.TEEN)
                                    RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_TRUE
                                    else RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_FALSE)
                                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_PG)
                                .build())
                            if (!initialized) {
                                withContext(Dispatchers.IO) {
                                    suspendCancellableCoroutine { continuation ->
                                        MobileAds.initialize(activity.applicationContext) {
                                            if (continuation.isActive) continuation.resume(Unit)
                                        }
                                    }
                                }
                                if (!active) return@launch
                                initialized = true
                            }
                            state.ready = consent.canRequestAds()
                        }
                    }
                }
            }, { error ->
                if (active) state.report("Could not update ad privacy settings: ${error.message} (${error.errorCode})")
            })
        }
        onDispose { active = false; state.ready = false }
    }
    CompositionLocalProvider(LocalAdvertising provides state) {
        content()
        if (adsBuildEnabled && !hasPro && audience == AdAudience.UNSET) {
            AlertDialog(onDismissRequest = {}, title = { Text("Advertising privacy") },
                text = { Text("Choose your age group for appropriate ad privacy handling. " +
                    "Only this category is saved on your device, not your birth date. " +
                    "Users under 18 receive under-age-of-consent treatment; under 13 receive no ads.") },
                confirmButton = {
                    Column {
                        listOf(AdAudience.UNDER_13, AdAudience.TEEN, AdAudience.ADULT).forEach { option ->
                            TextButton(onClick = { settings.adAudience = option }) { Text(option.label) }
                        }
                    }
                })
        }
    }
}

@Composable
fun BasicBanner(route: String?, hasPro: Boolean) {
    val state = LocalAdvertising.current ?: return
    if (!AdvertisingPolicy.showsBanner(route, hasPro) || !state.ready) return
    val context = LocalContext.current
    val audience = (context.applicationContext as Application).settings.adAudience
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    Surface(Modifier.fillMaxWidth().testTag("basic-ad-banner")) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HorizontalDivider()
            Text("Advertisement", style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                contentAlignment = Alignment.Center) {
                val width = maxWidth.value.toInt()
                val size = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, width)
                val view = remember(width, audience) {
                    AdView(context).apply {
                        adUnitId = BuildConfig.BANNER_AD_UNIT_ID
                        setAdSize(size)
                        adListener = object : AdListener() {
                            override fun onAdFailedToLoad(error: LoadAdError) {
                                state.report("Banner unavailable: ${error.message} (${error.code})")
                            }

                            override fun onAdLoaded() { state.error = null }
                        }
                    }
                }
                DisposableEffect(view, lifecycle) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> view.resume()
                            Lifecycle.Event.ON_PAUSE -> view.pause()
                            else -> Unit
                        }
                    }
                    lifecycle.addObserver(observer)
                    val extras = Bundle().apply { putString("npa", "1") }
                    view.loadAd(AdRequest.Builder()
                        .addNetworkExtrasBundle(AdMobAdapter::class.java, extras).build())
                    onDispose {
                        lifecycle.removeObserver(observer)
                        view.destroy()
                    }
                }
                AndroidView(factory = { view }, modifier = Modifier.height(size.height.dp))
            }
        }
    }
}

@Composable
fun AdvertisingPrivacyOptions() {
    val state = LocalAdvertising.current ?: return
    val context = LocalContext.current
    val app = context.applicationContext as Application
    if (app.access.adFree || !adsBuildEnabled) return
    val activity = context as Activity
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider(Modifier.padding(top = 20.dp, bottom = 8.dp))
        Text("Advertising privacy", style = MaterialTheme.typography.titleMedium)
        Text("Age category: ${app.settings.adAudience.label}", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { state.ready = false; app.settings.adAudience = AdAudience.UNSET }) {
            Text("Change age category")
        }
        TextButton(onClick = { state.retry++ }) { Text("Retry ad privacy connection") }
        if (state.privacyRequired) TextButton(onClick = {
            state.ready = false
            UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
                if (error != null) state.report("Ad privacy options: ${error.message} (${error.errorCode})")
                val consent = UserMessagingPlatform.getConsentInformation(activity)
                state.ready = consent.canRequestAds()
                state.privacyRequired = consent.privacyOptionsRequirementStatus ==
                    ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
            }
        }) { Text("Manage ad privacy choices") }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
