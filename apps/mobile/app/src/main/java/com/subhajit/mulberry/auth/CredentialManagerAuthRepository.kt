package com.subhajit.mulberry.auth

import androidx.activity.ComponentActivity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
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

@Singleton
class CredentialManagerAuthRepository @Inject constructor(
    private val apiService: MulberryApiService,
    private val sessionBootstrapRepository: SessionBootstrapRepository,
    private val appConfig: AppConfig,
    private val fcmTokenRepository: FcmTokenRepository
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
        val idToken = if (appConfig.enableDebugMenu && appConfig.googleServerClientId.isBlank()) {
            "dev-google:subhajit@elaris.dev:Subhajit"
        } else {
            check(appConfig.googleServerClientId.isNotBlank()) {
                "Google server client id is not configured"
            }
            val credentialManager = CredentialManager.create(activity)
            val response = try {
                credentialManager.getCredential(
                    context = activity,
                    request = GetCredentialRequest.Builder()
                        .addCredentialOption(
                            GetGoogleIdOption.Builder()
                                .setServerClientId(appConfig.googleServerClientId)
                                .setFilterByAuthorizedAccounts(false)
                                .setAutoSelectEnabled(false)
                                .build()
                        )
                        .build()
                )
            } catch (exception: NoCredentialException) {
                credentialManager.getCredential(
                    context = activity,
                    request = GetCredentialRequest.Builder()
                        .addCredentialOption(
                            GetSignInWithGoogleOption.Builder(appConfig.googleServerClientId)
                                .build()
                        )
                        .build()
                )
            }
            response.googleIdToken()
        }

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
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (exception: GoogleIdTokenParsingException) {
            throw IllegalStateException("Unable to parse Google credential", exception)
        }
    }
}
