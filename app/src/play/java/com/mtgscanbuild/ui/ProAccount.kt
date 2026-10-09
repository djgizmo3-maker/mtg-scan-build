package com.mtgscanbuild.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.AuthCredential
import com.google.firebase.functions.FirebaseFunctions
import com.mtgscanbuild.R
import com.mtgscanbuild.MtgApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

internal class ProAccountViewModel(app: Application) : AndroidViewModel(app) {
    val entitlements = (app as MtgApp).entitlements
    private val auth = FirebaseAuth.getInstance()
    private val credentials = CredentialManager.create(app)
    var account by mutableStateOf(auth.currentUser?.email ?: auth.currentUser?.displayName)
        private set
    var signedIn by mutableStateOf(auth.currentUser != null)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var confirmSignOut by mutableStateOf(false)
    var confirmDelete by mutableStateOf(false)
    private val functions = FirebaseFunctions.getInstance("us-central1")
    private val listener = FirebaseAuth.AuthStateListener {
        signedIn = it.currentUser != null
        account = it.currentUser?.email ?: it.currentUser?.displayName
    }

    init {
        auth.addAuthStateListener(listener)
    }

    fun signIn(context: Context) {
        if (busy || signedIn) return
        val activity = context.activity()
        if (activity == null) {
            Log.w("ProAccount", "Sign-in requires a foreground Activity.")
            message = "Could not open Google sign-in. Reopen this page and try again."
            return
        }
        busy = true
        message = null
        viewModelScope.launch {
            try {
                auth.signInWithCredential(googleCredential(activity)).await()
                message = "Signed in. Pro requires server verification; sign-in alone does not unlock it."
            } catch (_: GetCredentialCancellationException) {
                message = "Sign-in canceled."
            } catch (error: GetCredentialException) {
                report(error, "Could not open Google sign-in. Check your connection and Google Play services, then try again.")
            } catch (error: GoogleIdTokenParsingException) {
                report(error, "Google returned an invalid sign-in response. Try again.")
            } catch (error: FirebaseException) {
                report(error, "Firebase could not complete sign-in. Check your connection and the app's sign-in configuration, then try again.")
            } catch (error: IllegalArgumentException) {
                report(error, "Google returned an unsupported sign-in response. Try again.")
            } finally {
                busy = false
            }
        }
    }

    private suspend fun googleCredential(activity: Activity): AuthCredential {
        val option = GetSignInWithGoogleOption.Builder(
            getApplication<Application>().getString(R.string.default_web_client_id)
        ).build()
        val credential = credentials.getCredential(
            context = activity,
            request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        ).credential
        require(credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
        return GoogleAuthProvider.getCredential(
            GoogleIdTokenCredential.createFrom(credential.data).idToken, null
        )
    }

    fun deleteAccount(context: Context) {
        if (busy) return
        val activity = context.activity()
        val user = auth.currentUser
        if (activity == null || user == null) {
            Log.w("ProAccount", "Account deletion requires a signed-in foreground Activity.")
            message = "Sign in and reopen this page before deleting your account."
            return
        }
        confirmDelete = false
        busy = true
        message = null
        viewModelScope.launch {
            var deletionRequested = false
            try {
                user.reauthenticate(googleCredential(activity)).await()
                user.getIdToken(true).await()
                entitlements.clearLocalAccess()
                deletionRequested = true
                val result = functions.getHttpsCallable("deleteProAccount").call().await().data
                check(result is Map<*, *> && result["deleted"] == true) {
                    "Invalid account deletion response."
                }
                entitlements.clearLocalAccess()
                auth.signOut()
                credentials.clearCredentialState(ClearCredentialStateRequest())
                message = "Account deleted. Cards, folders, and decks remain on this device. A valid Pro purchase can be recovered using the same Google account."
            } catch (_: GetCredentialCancellationException) {
                message = "Account deletion canceled."
            } catch (error: GetCredentialException) {
                report(error, "Could not confirm your Google identity. Account deletion was not requested.")
            } catch (error: GoogleIdTokenParsingException) {
                report(error, "Google returned an invalid identity response. Account deletion was not requested.")
            } catch (error: FirebaseException) {
                if (deletionRequested) {
                    auth.signOut()
                    report(error, "Could not confirm account deletion. You were signed out and cached Pro access was cleared. If the request reached the server, cleanup may still finish. Reconnect and sign in to check before trying again; your local collection is unchanged.")
                } else {
                    report(error, "Could not confirm your Google identity. Account deletion was not requested; your local collection is unchanged.")
                }
            } catch (error: ClearCredentialException) {
                report(error, "Account deleted and signed out, but Google account selection could not be reset. Your local collection is unchanged.")
            } catch (error: IllegalArgumentException) {
                report(error, "Could not validate your Google identity. Account deletion was not requested.")
            } catch (error: IllegalStateException) {
                entitlements.clearLocalAccess()
                auth.signOut()
                report(error, "The deletion response could not be confirmed. You were signed out; contact support to check the deletion status.")
            } finally {
                busy = false
            }
        }
    }

    fun signOut() {
        if (busy) return
        confirmSignOut = false
        busy = true
        message = null
        entitlements.clearLocalAccess()
        auth.signOut()
        viewModelScope.launch {
            try {
                credentials.clearCredentialState(ClearCredentialStateRequest())
                message = "Signed out. Your on-device collection and decks are unchanged."
            } catch (error: ClearCredentialException) {
                report(error, "Signed out of the app, but Google account selection could not be reset. Try signing in again.")
            } finally {
                busy = false
            }
        }
    }

    private fun report(error: Exception, text: String) {
        // Provider exceptions can contain account details; log the error class only.
        Log.w("ProAccount", "Account operation failed: ${error.javaClass.simpleName}")
        message = text
    }

    override fun onCleared() {
        auth.removeAuthStateListener(listener)
        super.onCleared()
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
internal fun ProAccountSection(vm: ProAccountViewModel = viewModel()) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Google account", style = MaterialTheme.typography.titleMedium)
        if (vm.signedIn) {
            Text("Signed in: ${vm.account ?: "Google account"}")
            OutlinedButton(
                onClick = { vm.confirmSignOut = true },
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Sign out") }
        } else {
            Text("Optional during setup. Sign-in does not unlock Pro or upload your collection.")
            OutlinedButton(
                onClick = { vm.signIn(context) },
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Sign in with Google") }
        }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (vm.signedIn) {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            OutlinedButton(
                onClick = { scope.launch { vm.entitlements.refresh() } },
                enabled = !vm.busy && !vm.entitlements.working,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Refresh verified Pro access") }
            TextButton(
                onClick = { vm.confirmDelete = true },
                enabled = !vm.busy && !vm.entitlements.working,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Delete app account") }
            if (vm.entitlements.working) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.entitlements.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        if (vm.confirmDelete) {
            AlertDialog(
                onDismissRequest = { vm.confirmDelete = false },
                title = { Text("Delete app account?") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("This permanently deletes your Firebase app account and clears sign-in and cached Pro access. Your cards, folders, and decks remain on this device. We retain a hashed Google-account identifier, purchase token, and verification status for valid purchase recovery and fraud prevention, not your email or display name. A temporary deletion marker is kept during cleanup, then removed by hourly cleanup after 24 hours; outages may delay removal. Deleting the account does not refund a Google Play purchase or delete your Google account. Confirm your identity with the same Google account to continue.")
                    }
                },
                confirmButton = { TextButton(onClick = { vm.deleteAccount(context) }) { Text("Confirm identity and delete") } },
                dismissButton = { TextButton(onClick = { vm.confirmDelete = false }) { Text("Cancel") } },
            )
        }
    }
    if (vm.confirmSignOut) {
        AlertDialog(
            onDismissRequest = { vm.confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Your collection, folders, and decks stay on this device. Signing out does not delete your Firebase account.") },
            confirmButton = { TextButton(onClick = vm::signOut) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { vm.confirmSignOut = false }) { Text("Cancel") } }
        )
    }
}
