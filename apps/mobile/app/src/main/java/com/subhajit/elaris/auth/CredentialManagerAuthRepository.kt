package com.subhajit.elaris.auth

import androidx.activity.ComponentActivity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.subhajit.elaris.core.config.AppConfig
import com.subhajit.elaris.data.bootstrap.AppSession
import com.subhajit.elaris.data.bootstrap.AuthStatus
import com.subhajit.elaris.data.bootstrap.SessionBootstrapRepository
import com.subhajit.elaris.network.ElarisApiService
import com.subhajit.elaris.network.GoogleAuthRequest
import com.subhajit.elaris.network.RefreshRequest
import com.subhajit.elaris.network.toDomainBootstrap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class CredentialManagerAuthRepository @Inject constructor(
    private val apiService: ElarisApiService,
    private val sessionBootstrapRepository: SessionBootstrapRepository,
    private val appConfig: AppConfig
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
            val response = CredentialManager.create(activity).getCredential(
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
    }

    override suspend fun logout(): Result<Unit> = runCatching {
        runCatching { apiService.logout() }
        val wallpaperConfigured = sessionBootstrapRepository.state.first().hasWallpaperConfigured
        sessionBootstrapRepository.cacheSession(null)
        sessionBootstrapRepository.cacheBootstrap(
            com.subhajit.elaris.data.bootstrap.SessionBootstrapState(
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
