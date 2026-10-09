package com.mtgscanbuild

import android.app.Application
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import com.mtgscanbuild.data.PlanAccess
import com.mtgscanbuild.data.ProEntitlements
import com.mtgscanbuild.data.ProPurchaseAccount
import com.mtgscanbuild.data.completedProTokens
import com.mtgscanbuild.data.hasPendingProPayment
import com.mtgscanbuild.data.VerifiedProResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.resume
import java.io.IOException
import java.security.GeneralSecurityException
import org.json.JSONException

internal fun createProEntitlements(app: Application, access: PlanAccess): ProEntitlements =
    FirebaseProEntitlements(app, access)

internal class FirebaseProEntitlements(app: Application, private val access: PlanAccess) : ProEntitlements {
    private val auth = FirebaseAuth.getInstance()
    private val functions = FirebaseFunctions.getInstance("us-central1")
    private val cache = ProLeaseCache(app)
    private val boot = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var userId by mutableStateOf(auth.currentUser?.uid)
    override val signedIn: Boolean get() = userId != null
    private var generation = 0L
    private var expiry: Job? = null
    private var autoRefresh: Job? = null
    private var lastAttempt = Long.MIN_VALUE
    private val restoreBilling by lazy {
        BillingClient.newBuilder(app)
            .setListener { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                    scope.launch { verifyCompletedPlayPurchases(purchases) }
                } else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
                    Log.w("ProEntitlements", "Background purchase callback failed: ${result.responseCode}")
                    message = "Could not read the Play purchase update. Open Pro and restore purchases."
                }
            }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
    }
    override var working by mutableStateOf(false)
        private set
    override var message by mutableStateOf<String?>(null)
        private set

    init {
        restoreCache()
        auth.addAuthStateListener {
            val next = it.currentUser?.uid
            if (next != userId) {
                generation++
                autoRefresh?.cancel()
                userId = next
                clearLocalAccess()
                restoreCache()
            }
            refreshOnResume()
        }
    }

    private fun restoreCache() {
        val uid = userId ?: return
        if (boot < 0) {
            message = "Offline verification is unavailable on this device. Reconnect to verify Pro."
            Log.w("ProEntitlements", "Android boot counter unavailable.")
            return
        }
        try {
            val deadline = cache.load(uid, boot, SystemClock.elapsedRealtime())
            if (deadline != null) applyDeadline(deadline)
        } catch (error: IOException) {
            cacheFailure(error)
        } catch (error: GeneralSecurityException) {
            cacheFailure(error)
        } catch (error: JSONException) {
            cacheFailure(error)
        } catch (error: IllegalArgumentException) {
            cacheFailure(error)
        }
    }

    private fun cacheFailure(error: Exception) {
        Log.w("ProEntitlements", "Entitlement cache unavailable: ${error.javaClass.simpleName}")
        message = "Offline Pro verification could not be loaded. Reconnect to verify your purchase."
        access.setVerifiedDeadline(null)
    }

    private fun applyDeadline(deadline: Long?) {
        expiry?.cancel()
        access.setVerifiedDeadline(deadline)
        if (deadline != null) {
            expiry = scope.launch {
                delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                access.setVerifiedDeadline(null)
                message = "Offline Pro verification expired. Reconnect and refresh your purchase."
            }
        }
    }

    override fun clearLocalAccess() {
        generation++
        applyDeadline(null)
        try {
            cache.clear()
        } catch (error: IllegalStateException) {
            cacheFailure(error)
        }
        lastAttempt = Long.MIN_VALUE
    }

    override fun refreshOnResume() {
        if (access.expireVerifiedDeadline()) {
            expiry?.cancel()
            message = "Offline Pro verification expired. Reconnect and refresh your purchase."
        }
        if (userId == null || autoRefresh?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (lastAttempt != Long.MIN_VALUE && now - lastAttempt < 60_000) return
        lastAttempt = now
        autoRefresh = scope.launch {
            refresh()
            restorePlayPurchases()
        }
    }

    private suspend fun restorePlayPurchases() {
        val uid = userId ?: return
        val requestGeneration = generation
        try {
            withTimeout(30_000) {
                val client = restoreBilling
                if (!client.isReady) {
                    val setup = suspendCancellableCoroutine { continuation ->
                        client.startConnection(object : BillingClientStateListener {
                            override fun onBillingSetupFinished(result: BillingResult) {
                                if (continuation.isActive) continuation.resume(result)
                            }
                            override fun onBillingServiceDisconnected() {
                                Log.w("ProEntitlements", "Play restore connection disconnected.")
                            }
                        })
                    }
                    if (setup.responseCode != BillingClient.BillingResponseCode.OK) {
                        Log.w("ProEntitlements", "Play restore setup failed: ${setup.responseCode}")
                        message = "Google Play restoration is unavailable. Existing verified access keeps its original expiry. Open Pro and retry."
                        return@withTimeout
                    }
                }
                val result = suspendCancellableCoroutine { continuation ->
                    client.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.INAPP).build()) { response, purchases ->
                        if (continuation.isActive) continuation.resume(response to purchases)
                    }
                }
                if (generation != requestGeneration || userId != uid) return@withTimeout
                if (result.first.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.w("ProEntitlements", "Play restore query failed: ${result.first.responseCode}")
                    message = "Could not restore Google Play purchases. Open Pro and retry; no access was extended."
                    return@withTimeout
                }
                verifyCompletedPlayPurchases(result.second)
            }
        } catch (error: TimeoutCancellationException) {
            if (generation != requestGeneration || userId != uid) return
            Log.w("ProEntitlements", "Play restoration timed out.")
            message = "Google Play restoration timed out. Open Pro and retry; no access was extended."
        }
    }

    private suspend fun verifyCompletedPlayPurchases(purchases: List<Purchase>) {
        val tokens = completedProTokens(purchases)
        if (tokens.isNotEmpty()) verifyPurchases(tokens)
        if (hasPendingProPayment(purchases)) {
            message = "A Pro payment is pending. Access requires completed payment and server verification."
        }
    }

    override suspend fun refresh() {
        request("refreshProEntitlement", emptyMap<String, String>())
    }

    override fun isCurrentPurchaseAccount(account: ProPurchaseAccount): Boolean =
        account.uid == userId && account.uid == auth.currentUser?.uid && account.generation == generation

    override suspend fun purchaseAccount(): ProPurchaseAccount? = mutex.withLock {
        val uid = userId
        if (uid == null) {
            message = "Sign in with Google before starting checkout."
            return@withLock null
        }
        val requestGeneration = generation
        working = true
        message = null
        try {
            val result = functions.getHttpsCallable("proPurchaseAccount").call().await()
            val account = ProPurchaseAccount.parse(result.data, uid, requestGeneration)
            if (!isCurrentPurchaseAccount(account)) {
                message = "Your account changed. Reload Google Play before starting checkout."
                return@withLock null
            }
            account
        } catch (error: FirebaseException) {
            if (generation != requestGeneration || userId != uid) throw CancellationException("Account changed.", error)
            failure(error, "Could not authorize checkout. Check your sign-in and connection, then retry. No purchase was started.")
            null
        } catch (error: IllegalArgumentException) {
            failure(error, "The server returned an invalid checkout identity. No purchase was started.")
            null
        } finally {
            working = false
        }
    }

    override suspend fun verifyPurchases(tokens: List<String>) {
        for (token in tokens.distinct()) {
            if (!request("verifyProPurchase", mapOf("purchaseToken" to token))) return
        }
        // Querying a catalog or a local Play purchase list never supplies an unlock.
        refresh()
    }

    private suspend fun request(name: String, data: Map<String, String>): Boolean = mutex.withLock {
        val uid = userId
        if (uid == null) {
            message = "Sign in with the Google account used for Pro before restoring purchases."
            return@withLock false
        }
        val requestGeneration = generation
        working = true
        message = null
        val started = SystemClock.elapsedRealtime()
        try {
            val result = functions.getHttpsCallable(name).call(data).await()
            if (generation != requestGeneration || userId != uid) throw CancellationException("Account changed.")
            val response = VerifiedProResponse.parse(result.data)
            val deadline = response.deadline(started)
            applyDeadline(deadline)
            if (deadline == null) cache.clear()
            else if (boot >= 0) cache.save(uid, boot, started, deadline)
            message = if (response.verificationIncomplete)
                "Some purchases could not be checked. Confirmed refunds were applied; any retained Pro access keeps its existing server expiry. Retry verification."
                else if (response.hasPro) "Pro verified. Offline access lasts up to 30 days; reconnect after restarting your phone."
                else "No active Pro entitlement was verified. Your saved collection and decks are unchanged."
            !response.verificationIncomplete
        } catch (error: FirebaseException) {
            if (generation != requestGeneration) throw CancellationException("Account changed.", error)
            if (error is FirebaseFunctionsException &&
                error.code == FirebaseFunctionsException.Code.UNAUTHENTICATED) {
                clearLocalAccess()
                failure(error, "Your sign-in could not be authenticated. Pro access was cleared. Sign out and sign in again, then retry.")
            } else {
                failure(error, "Could not verify Pro online. Existing verified access is kept only until its original expiry. Check your connection and retry.")
            }
            false
        } catch (error: IllegalArgumentException) {
            failure(error, "The server returned an invalid entitlement response. Pro access was not extended. Retry verification.")
            false
        } catch (error: IOException) {
            failure(error, "Pro was checked, but offline verification could not be saved. Retry while connected.")
            false
        } catch (error: GeneralSecurityException) {
            failure(error, "Pro was checked, but secure offline storage is unavailable. Retry while connected.")
            false
        } catch (error: IllegalStateException) {
            failure(error, "Pro was checked, but local verification storage could not be cleared. Retry.")
            false
        } finally {
            working = false
        }
    }

    private fun failure(error: Exception, text: String) {
        Log.w("ProEntitlements", "Pro operation failed: ${error.javaClass.simpleName}")
        message = text
    }
}
