package com.mtgscanbuild.ui

import android.app.Application
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.BillingFlowParams
import com.mtgscanbuild.MtgApp
import com.mtgscanbuild.data.PlanAccess
import com.mtgscanbuild.data.ProFeature
import com.mtgscanbuild.data.ProCheckoutOffer
import com.mtgscanbuild.data.completedProTokens
import com.mtgscanbuild.data.hasPendingProPayment
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.resume

class ProViewModel(app: Application) : AndroidViewModel(app) {
    val access = app.access
    val entitlements = (app as MtgApp).entitlements
    var loading by mutableStateOf(false)
        private set
    var price by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    private var offer: ProCheckoutOffer? = null
    var pending by mutableStateOf(false)
        private set
    var checkoutOpen by mutableStateOf(false)
        private set
    val canPurchase: Boolean get() = access.checkoutEnabled && entitlements.signedIn &&
        !access.hasPro && !loading && !entitlements.working && !checkoutOpen && !pending && offer != null
    private val billing = if (access.developerUnlocked) null else BillingClient.newBuilder(app)
        .setListener { result, purchases ->
            viewModelScope.launch {
                checkoutOpen = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    if (purchases == null) {
                        billingFailure("Google Play returned no purchase result. Refresh purchases to check its status.")
                    } else {
                        loading = true
                        try { restore(purchases) } finally { loading = false }
                    }
                } else if (result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                    checkPlay()
                } else {
                    message = if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED)
                        "Purchase canceled. Your plan has not changed."
                    else "Google Play billing failed (${result.responseCode}). Refresh purchases before trying again."
                    if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
                        Log.w("ProBilling", "Purchase callback failed: ${result.responseCode}")
                    }
                }
            }
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    fun checkPlay() {
        val client = billing ?: return
        if (loading) return
        loading = true
        message = null
        price = null
        offer = null
        viewModelScope.launch {
            try {
                val purchases = withTimeout(30_000) {
                    if (!client.isReady) {
                        val result = suspendCancellableCoroutine { continuation ->
                            client.startConnection(object : BillingClientStateListener {
                                override fun onBillingSetupFinished(result: BillingResult) {
                                    if (continuation.isActive) continuation.resume(result)
                                }
                                override fun onBillingServiceDisconnected() {
                                    Log.w("ProBilling", "Google Play disconnected during purchase checks.")
                                }
                            })
                        }
                        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                            billingFailure("Google Play is unavailable (${result.responseCode}). Use a Play-installed test build and configured one-time product.")
                            return@withTimeout null
                        }
                    }
                    val catalog = queryCatalog(client)
                    if (catalog.first.responseCode != BillingClient.BillingResponseCode.OK) {
                        billingFailure("Could not load the Pro product (${catalog.first.responseCode}).")
                    } else {
                        val found = catalog.second.productDetailsList.singleOrNull { it.productId == PlanAccess.PRO_PRODUCT_ID }
                        offer = ProCheckoutOffer.from(found)
                        price = offer?.price
                        if (found == null) {
                            billingFailure("The Pro product is not available for this account/build. Configure ${PlanAccess.PRO_PRODUCT_ID} in Play Console.")
                        } else if (offer == null) {
                            billingFailure("The permanent pro-unlock purchase option is unavailable or ambiguous. Checkout was not enabled.")
                        }
                    }
                    val owned = queryExistingPurchases(client)
                    if (owned.first.responseCode != BillingClient.BillingResponseCode.OK) {
                        offer = null
                        billingFailure("Could not check existing purchases (${owned.first.responseCode}).")
                        null
                    } else owned.second
                }
                if (purchases != null) restore(purchases)
            } catch (error: TimeoutCancellationException) {
                offer = null
                billingFailure("Google Play purchase checks timed out. Reload Google Play and retry.")
            } finally {
                loading = false
                checkoutOpen = false
            }
        }
    }

    private suspend fun queryCatalog(client: BillingClient): Pair<BillingResult, QueryProductDetailsResult> =
        suspendCancellableCoroutine { continuation ->
            val product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PlanAccess.PRO_PRODUCT_ID).setProductType(BillingClient.ProductType.INAPP).build()
            client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder()
                .setProductList(listOf(product)).build()) { result, details ->
                if (continuation.isActive) continuation.resume(result to details)
            }
        }

    private suspend fun queryExistingPurchases(client: BillingClient): Pair<BillingResult, List<Purchase>> =
        suspendCancellableCoroutine { continuation ->
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP).build()) { result, purchases ->
                if (continuation.isActive) continuation.resume(result to purchases)
            }
        }

    private suspend fun restore(purchases: List<Purchase>) {
        pending = hasPendingProPayment(purchases)
        if (pending) {
            message = "A Pro payment is pending. It will not unlock Pro until Google Play confirms payment and the server verifies it."
        }
        entitlements.verifyPurchases(completedProTokens(purchases))
    }

    fun purchase(context: Context) {
        if (!canPurchase) {
            billingFailure("Checkout requires the internal test build, Google sign-in, an available product, and no existing or pending Pro purchase.")
            return
        }
        val activity = context.billingActivity()
        val client = billing
        if (activity == null || activity.isFinishing || activity.isDestroyed || client?.isReady != true) {
            billingFailure("Checkout requires an active app screen and a connected Google Play service. Reload Google Play and retry.")
            return
        }
        loading = true
        message = null
        viewModelScope.launch {
            try {
                val account = withTimeout(60_000) { entitlements.purchaseAccount() } ?: return@launch
                val catalog = withTimeout(30_000) { queryCatalog(client) }
                if (catalog.first.responseCode != BillingClient.BillingResponseCode.OK) {
                    billingFailure("Could not refresh the checkout product (${catalog.first.responseCode}). No purchase was started.")
                    return@launch
                }
                val freshProduct = catalog.second.productDetailsList.singleOrNull {
                    it.productId == PlanAccess.PRO_PRODUCT_ID
                }
                val freshOffer = ProCheckoutOffer.from(freshProduct)
                if (freshProduct == null || freshOffer == null) {
                    offer = null
                    billingFailure("The permanent Pro offer is no longer available. Reload Google Play. No purchase was started.")
                    return@launch
                }
                offer = freshOffer
                price = freshOffer.price
                val owned = withTimeout(30_000) { queryExistingPurchases(client) }
                if (owned.first.responseCode != BillingClient.BillingResponseCode.OK) {
                    billingFailure("Could not check existing purchases (${owned.first.responseCode}). No purchase was started.")
                    return@launch
                }
                if (!entitlements.isCurrentPurchaseAccount(account)) {
                    billingFailure("Your account changed. Reload Google Play before trying again.")
                    return@launch
                }
                if (owned.second.any { PlanAccess.PRO_PRODUCT_ID in it.products }) {
                    restore(owned.second)
                    if (!pending) message = "An existing Pro purchase was sent for server verification. No new checkout was started."
                    return@launch
                }
                if (!entitlements.isCurrentPurchaseAccount(account) || access.hasPro ||
                    activity.isFinishing || activity.isDestroyed || !client.isReady) {
                    billingFailure("Your account or checkout screen changed. Reload Google Play before trying again.")
                    return@launch
                }
                val params = BillingFlowParams.newBuilder()
                    .setObfuscatedAccountId(account.obfuscatedAccountId)
                    .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(freshProduct).setOfferToken(requireNotNull(freshOffer.offerToken)).build()))
                    .build()
                checkoutOpen = true
                val result = client.launchBillingFlow(activity, params)
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    checkoutOpen = false
                    if (result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                        message = "Google Play reports Pro already owned. Use Check Google Play / restore Pro."
                    } else {
                        billingFailure("Google Play could not start checkout (${result.responseCode}). Your plan has not changed.")
                    }
                }
            } catch (error: TimeoutCancellationException) {
                checkoutOpen = false
                billingFailure("Checkout checks timed out. Refresh purchases before trying again; no checkout was started.")
            } catch (error: IllegalArgumentException) {
                checkoutOpen = false
                billingFailure("Google Play rejected the checkout parameters. Reload the product and retry; no access was granted.")
            } finally {
                loading = false
            }
        }
    }

    private fun billingFailure(text: String) {
        Log.w("ProBilling", text)
        message = text
    }

    override fun onCleared() {
        billing?.endConnection()
        super.onCleared()
    }

    private fun Context.billingActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.billingActivity()
        else -> null
    }
}

private data class PlanRow(val label: String, val basic: String, val pro: String)
private val planRows = listOf(
    PlanRow("Advertising", "Small banners", "Ad-free"),
    PlanRow("Card scanning & inventory", "Unlimited", "Unlimited"),
    PlanRow("Manual & saved decks", "Unlimited", "Unlimited"),
    PlanRow("Home: news, bans & rules", "Included", "Included"),
    PlanRow("Collection folders", "${PlanAccess.BASIC_FOLDER_LIMIT}", "Unlimited"),
    PlanRow("Folder colors & values", "Included", "Included"),
    PlanRow("Deck generation", "Locked", "Included"),
    PlanRow("Moxfield comparison", "Locked", "Included"),
    PlanRow("CSV / list import & CSV export", "Locked", "Included"),
    PlanRow("Choose opening page", "Home", "Any main tab"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("DEPRECATION")
fun ProScreen(onBack: () -> Unit, vm: ProViewModel = viewModel()) {
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.checkPlay() }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("MTG Scan & Build Pro") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Your plan: ${vm.access.label}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    ComparisonRow("Features", "Basic", "Pro", header = true)
                    planRows.forEach { row ->
                        HorizontalDivider()
                        ComparisonRow(row.label, row.basic, row.pro)
                    }
                }
            }
            if (vm.access.developerUnlocked) {
                Text("Developer testing build", style = MaterialTheme.typography.titleMedium)
                Text("All current Pro features are unlocked without payment. This separate app is for your testing, not Google Play distribution.")
            } else {
                ProAccountSection()
                Text("Planned: one-time Pro purchase", style = MaterialTheme.typography.titleMedium)
                Text("No subscription or automatic renewal. Pro unlocks only after server verification.")
                if (vm.access.checkoutEnabled) {
                    Text("Internal checkout test build. Use your Play license-tester account and confirm the purchase sheet shows a test payment method. If it shows a real payment method, cancel: internal testing alone does not make purchases free.")
                } else {
                    Text("Pro purchases are not available in this pre-release build.")
                }
                vm.price?.let { Text("Google Play product price: $it") }
                Button(onClick = { vm.purchase(context) }, enabled = vm.canPurchase, modifier = Modifier.fillMaxWidth()) {
                    Text(if (!vm.access.checkoutEnabled) "Purchase unavailable - setup pending"
                        else if (vm.access.hasPro) "Pro already verified"
                        else if (vm.pending) "Payment pending"
                        else "Test Pro checkout${vm.price?.let { " - $it" }.orEmpty()}")
                }
                OutlinedButton(onClick = vm::checkPlay, enabled = !vm.loading, modifier = Modifier.fillMaxWidth()) {
                    Text("Check Google Play / restore Pro")
                }
                if (vm.loading || vm.checkoutOpen) LinearProgressIndicator(Modifier.fillMaxWidth())
                vm.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Production checkout remains disabled until license-tester purchase, restoration, refund, and recovery checks pass.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("There are currently no ads. Cloud sync, trades, saved searches, and a deck simulator are not included or advertised.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ComparisonRow(label: String, basic: String, pro: String, header: Boolean = false) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1.55f).padding(12.dp),
            fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.weight(0.8f).fillMaxHeight().padding(8.dp), contentAlignment = Alignment.Center) {
            Text(basic, style = MaterialTheme.typography.bodySmall, fontWeight = if (header) FontWeight.Bold else FontWeight.Normal)
        }
        Box(Modifier.weight(0.8f).fillMaxHeight().background(MaterialTheme.colorScheme.primaryContainer).padding(8.dp),
            contentAlignment = Alignment.Center) {
            Text(pro, color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ProGate(feature: ProFeature, onPro: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(feature.label, style = MaterialTheme.typography.headlineSmall)
        Text("This feature is included with Pro. Your collection and saved decks remain accessible on Basic.")
        Button(onClick = onPro) { Text("View Basic / Pro plans") }
    }
}
