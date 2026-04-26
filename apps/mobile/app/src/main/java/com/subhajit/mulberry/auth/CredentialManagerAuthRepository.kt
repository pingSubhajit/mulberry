package com.subhajit.mulberry.auth

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.ClearCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.subhajit.mulberry.core.config.AppConfig
import com.subhajit.mulberry.data.bootstrap.AppSession
import com.subhajit.mulberry.data.bootstrap.AuthStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.network.MulberryApiService
import com.subhajit.mulberry.network.GoogleAuthRequest
import com.subhajit.mulberry.network.RefreshRequest
import com.subhajit.mulberry.network.toDomainBootstrap
import com.subhajit.mulberry.sync.FcmTokenRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class CredentialManagerAuthRepository @Inject constructor(
    private val apiService: MulberryApiService,
    private val sessionBootstrapRepository: SessionBootstrapRepository,
    private val appConfig: AppConfig,
    private val fcmTokenRepository: FcmTokenRepository,
    @ApplicationContext private val context: Context
) : AuthRepository {

    override val authState: Flow<AuthState> = sessionBootstrapRepository.state.map { state ->
        when (state.authStatus) {
            AuthStatus.REFRESHING -> AuthState.Refreshing
            AuthStatus.SIGNED_IN -> {
                val session = sessionBootstrapRepository.getCurrentSession()
                if (session != null) {
                    AuthState.SignedIn(session)
                } else {
                    AuthState.SignedOut
                }
            }
            AuthStatus.SIGNED_OUT -> AuthState.SignedOut
        }
    }

    override suspend fun signInWithGoogle(activity: ComponentActivity): Result<Unit> = runCatching {
        completeGoogleSignInWithIdToken(
            if (appConfig.enableDebugMenu && appConfig.googleServerClientId.isBlank()) {
                "dev-google:subhajit@elaris.dev:Subhajit"
            } else {
                check(appConfig.googleServerClientId.isNotBlank()) {
                    "Google server client id is not configured"
                }
                val credentialManager = CredentialManager.create(activity)
                requestGoogleButtonCredential(
                    activity = activity,
                    credentialManager = credentialManager,
                    attemptLabel = "button"
                ).googleIdToken()
            }
        )
    }

    override suspend fun tryAutomaticGoogleSignIn(activity: ComponentActivity): Result<Boolean> = runCatching {
        if (appConfig.enableDebugMenu && appConfig.googleServerClientId.isBlank()) {
            completeGoogleSignInWithIdToken("dev-google:subhajit@elaris.dev:Subhajit")
            true
        } else {
            check(appConfig.googleServerClientId.isNotBlank()) {
                "Google server client id is not configured"
            }
            val credentialManager = CredentialManager.create(activity)
            val response = requestAutomaticGoogleCredential(
                activity = activity,
                credentialManager = credentialManager
            ) ?: return@runCatching false
            completeGoogleSignInWithIdToken(response.googleIdToken())
            true
        }
    }

    override suspend fun refreshSession(): Result<Unit> = runCatching {
        val session = sessionBootstrapRepository.getCurrentSession()
            ?: error("No session to refresh")
        val currentState = sessionBootstrapRepository.state.first()
        sessionBootstrapRepository.cacheBootstrap(currentState.copy(authStatus = AuthStatus.REFRESHING))
        val response = apiService.refreshSession(
            RefreshRequest(refreshToken = session.refreshToken)
        )
        sessionBootstrapRepository.cacheSession(
            AppSession(
                accessToken = response.accessToken,
                refreshToken = response.refreshToken,
                userId = response.userId
            )
        )
        sessionBootstrapRepository.cacheBootstrap(response.bootstrapState.toDomainBootstrap())
        fcmTokenRepository.syncTokenWithBackend()
    }

    override suspend fun logout(): Result<Unit> = runCatching {
        fcmTokenRepository.unregisterRegisteredToken()
        runCatching { apiService.logout() }
        runCatching {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        }.onFailure { error ->
            if (error is ClearCredentialException) {
                Log.w(
                    TAG,
                    "Unable to clear Credential Manager state during logout " +
                        "type=${error.javaClass.simpleName} message=${error.message}",
                    error
                )
            } else {
                Log.w(TAG, "Unexpected clearCredentialState failure during logout", error)
            }
        }
        val wallpaperConfigured = sessionBootstrapRepository.state.first().hasWallpaperConfigured
        sessionBootstrapRepository.cacheSession(null)
        sessionBootstrapRepository.cacheBootstrap(
            com.subhajit.mulberry.data.bootstrap.SessionBootstrapState(
                hasWallpaperConfigured = wallpaperConfigured
            )
        )
    }

    private fun GetCredentialResponse.googleIdToken(): String {
        val credential = credential
        return try {
            Log.i(TAG, "Google sign-in credential returned type=${credential.type}")
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (exception: GoogleIdTokenParsingException) {
            throw IllegalStateException("Unable to parse Google credential", exception)
        }
    }

    private suspend fun completeGoogleSignInWithIdToken(idToken: String) {
        val authResponse = apiService.authenticateWithGoogle(
            GoogleAuthRequest(idToken = idToken)
        )

        sessionBootstrapRepository.cacheSession(
            AppSession(
                accessToken = authResponse.accessToken,
                refreshToken = authResponse.refreshToken,
                userId = authResponse.userId
            )
        )
        sessionBootstrapRepository.cacheBootstrap(authResponse.bootstrapState.toDomainBootstrap())
        fcmTokenRepository.syncTokenWithBackend()
    }

    private suspend fun requestAutomaticGoogleCredential(
        activity: ComponentActivity,
        credentialManager: CredentialManager
    ): GetCredentialResponse? {
        val authorizedAccountsResponse = tryBottomSheetCredential(
            activity = activity,
            credentialManager = credentialManager,
            request = authorizedAccountsBottomSheetRequest(),
            attemptLabel = "authorized-accounts",
            isAutomaticAttempt = true
        )
        when (authorizedAccountsResponse) {
            is BottomSheetAttemptResult.Success -> return authorizedAccountsResponse.response
            BottomSheetAttemptResult.Canceled -> return null
            BottomSheetAttemptResult.NoCredential -> Unit
        }

        return when (
            val allAccountsResponse = tryBottomSheetCredential(
            activity = activity,
            credentialManager = credentialManager,
            request = allAccountsBottomSheetRequest(),
            attemptLabel = "all-accounts",
            isAutomaticAttempt = true
        )
        ) {
            is BottomSheetAttemptResult.Success -> allAccountsResponse.response
            BottomSheetAttemptResult.NoCredential,
            BottomSheetAttemptResult.Canceled -> null
        }
    }

    private suspend fun tryBottomSheetCredential(
        activity: ComponentActivity,
        credentialManager: CredentialManager,
        request: GetCredentialRequest,
        attemptLabel: String,
        isAutomaticAttempt: Boolean
    ): BottomSheetAttemptResult {
        return try {
            Log.i(
                TAG,
                "Attempting Google sign-in via GetGoogleIdOption bottom-sheet flow " +
                    "attempt=$attemptLabel automatic=$isAutomaticAttempt"
            )
            BottomSheetAttemptResult.Success(
                credentialManager.getCredential(
                    context = activity,
                    request = request
                )
            )
        } catch (exception: NoCredentialException) {
            Log.i(
                TAG,
                "Bottom-sheet Google sign-in returned no credential " +
                    "attempt=$attemptLabel automatic=$isAutomaticAttempt " +
                    "message=${exception.message}"
            )
            BottomSheetAttemptResult.NoCredential
        } catch (exception: GetCredentialCancellationException) {
            if (isAutomaticAttempt) {
                Log.i(
                    TAG,
                    "Automatic bottom-sheet Google sign-in was canceled or dismissed " +
                        "attempt=$attemptLabel message=${exception.message}"
                )
                BottomSheetAttemptResult.Canceled
            } else {
                val causeChain = exception.causeChainSummary()
                Log.w(
                    TAG,
                    "Bottom-sheet Google sign-in canceled " +
                        "attempt=$attemptLabel message=${exception.message} causeChain=$causeChain",
                    exception
                )
                throw wrapGetCredentialException(
                    branch = "bottom-sheet-$attemptLabel",
                    exception = exception,
                    causeChain = causeChain
                )
            }
        } catch (exception: GetCredentialException) {
            val causeChain = exception.causeChainSummary()
            Log.w(
                TAG,
                "Bottom-sheet Google sign-in failed before fallback " +
                    "attempt=$attemptLabel type=${exception.javaClass.simpleName} " +
                    "message=${exception.message} causeChain=$causeChain",
                exception
            )
            throw wrapGetCredentialException(
                branch = "bottom-sheet-$attemptLabel",
                exception = exception,
                causeChain = causeChain
            )
        }
    }

    private suspend fun requestGoogleButtonCredential(
        activity: ComponentActivity,
        credentialManager: CredentialManager,
        attemptLabel: String
    ): GetCredentialResponse {
        return try {
            Log.i(TAG, "Attempting Google sign-in via Sign in with Google button attempt=$attemptLabel")
            credentialManager.getCredential(
                context = activity,
                request = buttonRequest()
            )
        } catch (exception: GetCredentialException) {
            handleButtonFlowFailure(
                exception = exception,
                attemptLabel = attemptLabel
            )
        } catch (error: Throwable) {
            throw IllegalStateException("Unexpected Sign in with Google button failure", error)
        }
    }

    private suspend fun handleButtonFlowFailure(
        exception: GetCredentialException,
        attemptLabel: String
    ): GetCredentialResponse {
        if (exception is GetCredentialCancellationException && !isAccountReauthFailure(exception)) {
            Log.i(
                TAG,
                "Sign in with Google button flow was canceled or dismissed " +
                    "attempt=$attemptLabel message=${exception.message}"
            )
            throw exception
        }

        if (exception is GetCredentialProviderConfigurationException) {
            val causeChain = exception.causeChainSummary()
            Log.w(
                TAG,
                "Sign in with Google button flow failed due to missing or incompatible provider " +
                    "attempt=$attemptLabel message=${exception.message} causeChain=$causeChain",
                exception
            )
            throw IllegalStateException(
                "Google Play services is missing or out of date on this device.",
                exception
            )
        }

        val causeChain = exception.causeChainSummary()
        val recoverySummary = if (isAccountReauthFailure(exception)) {
            clearCredentialStateSummary("button flow failure recovery")
        } else {
            "skipped"
        }
        Log.w(
            TAG,
            "Sign in with Google button flow failed " +
                "attempt=$attemptLabel " +
                "type=${exception.javaClass.simpleName} " +
                "message=${exception.message} " +
                "causeChain=$causeChain " +
                "credentialStateReset=$recoverySummary",
            exception
        )

        if (isAccountReauthFailure(exception) && recoverySummary == "cleared") {
            throw IllegalStateException(
                "Unable to sign in with Google. Please try again.",
                exception
            )
        }

        throw IllegalStateException(
            "Unable to sign in with Google. Please try again.",
            exception
        )
    }

    private fun authorizedAccountsBottomSheetRequest(): GetCredentialRequest = GetCredentialRequest.Builder()
        .addCredentialOption(
            GetGoogleIdOption.Builder()
                .setServerClientId(appConfig.googleServerClientId)
                .setFilterByAuthorizedAccounts(true)
                .setAutoSelectEnabled(true)
                .build()
        )
        .build()

    private fun allAccountsBottomSheetRequest(): GetCredentialRequest = GetCredentialRequest.Builder()
        .addCredentialOption(
            GetGoogleIdOption.Builder()
                .setServerClientId(appConfig.googleServerClientId)
                .setFilterByAuthorizedAccounts(false)
                .setAutoSelectEnabled(false)
                .build()
        )
        .build()

    private fun buttonRequest(): GetCredentialRequest = GetCredentialRequest.Builder()
        .addCredentialOption(
            GetSignInWithGoogleOption.Builder(appConfig.googleServerClientId)
                .build()
        )
        .build()

    private sealed interface BottomSheetAttemptResult {
        data class Success(val response: GetCredentialResponse) : BottomSheetAttemptResult

        data object NoCredential : BottomSheetAttemptResult

        data object Canceled : BottomSheetAttemptResult
    }

    private fun wrapGetCredentialException(
        branch: String,
        exception: GetCredentialException,
        causeChain: String
    ): IllegalStateException {
        val kind = credentialErrorKind(exception)
        Log.w(
            TAG,
            "Google sign-in error surfaced to app " +
                "branch=$branch type=${exception.javaClass.simpleName} kind=$kind causeChain=$causeChain",
            exception
        )
        return IllegalStateException(
            "Unable to sign in with Google. Please try again.",
            exception
        )
    }

    private fun credentialErrorKind(exception: GetCredentialException): String = when (exception) {
        is GetCredentialCancellationException -> "canceled"
        is NoCredentialException -> "no-credential"
        else -> "error"
    }

    private fun isAccountReauthFailure(exception: GetCredentialException): Boolean =
        exception is GetCredentialCancellationException &&
            (exception.message?.contains("Account reauth failed", ignoreCase = true) == true ||
                exception.message?.contains("[16]", ignoreCase = true) == true)

    private fun Throwable.causeChainSummary(): String {
        val messages = mutableListOf<String>()
        var current: Throwable? = this.cause
        while (current != null && messages.size < 5) {
            messages += "${current.javaClass.simpleName}:${current.message ?: "no-message"}"
            current = current.cause
        }
        return if (messages.isEmpty()) "none" else messages.joinToString(" -> ")
    }

    private suspend fun clearCredentialStateSummary(reason: String): String {
        return runCatching {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
            "cleared"
        }.getOrElse { error ->
            if (error is ClearCredentialProviderConfigurationException) {
                Log.i(
                    TAG,
                    "Credential Manager clearCredentialState not supported on this device during $reason " +
                        "message=${error.message}"
                )
                return@getOrElse "not-supported"
            }
            if (error is ClearCredentialException) {
                Log.w(
                    TAG,
                    "Unable to clear Credential Manager state during $reason " +
                        "type=${error.javaClass.simpleName} message=${error.message}",
                    error
                )
                "failed [${error.javaClass.simpleName}]: ${error.message ?: "no message"}"
            } else {
                Log.w(TAG, "Unexpected clearCredentialState failure during $reason", error)
                "failed [${error.javaClass.simpleName}]: ${error.message ?: "no message"}"
            }
        }
    }

    private companion object {
        const val TAG = "MulberryAuth"
    }
}
