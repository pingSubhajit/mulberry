package com.subhajit.mulberry.auth

import com.subhajit.mulberry.analytics.AnalyticsEvent
import com.subhajit.mulberry.analytics.AppAnalytics
import com.subhajit.mulberry.analytics.OnboardingStep
import com.subhajit.mulberry.core.UserFacingError
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.credentials.exceptions.GetCredentialCancellationException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.subhajit.mulberry.pairing.InviteRepository
import com.subhajit.mulberry.pairing.inbound.InboundInviteRepository
import com.subhajit.mulberry.pairing.inbound.InstallReferrerInboundInviteIngester

@HiltViewModel
class AuthLandingViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val inboundInviteRepository: InboundInviteRepository,
    private val installReferrerInboundInviteIngester: InstallReferrerInboundInviteIngester,
    private val inviteRepository: InviteRepository,
    private val analytics: AppAnalytics
) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthLandingUiState())
    val uiState = _uiState.asStateFlow()
    private var hasAttemptedAutomaticGoogleSignIn = false

    private val _effects = MutableSharedFlow<AuthLandingEffect>()
    val effects = _effects.asSharedFlow()

    fun onScreenShown(activity: ComponentActivity) {
        if (hasAttemptedAutomaticGoogleSignIn || _uiState.value.isLoading) {
            return
        }
        hasAttemptedAutomaticGoogleSignIn = true
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            authRepository.tryAutomaticGoogleSignIn(activity)
                .onSuccess { didSignIn ->
                    if (didSignIn) {
                        _uiState.value = _uiState.value.copy(isLoading = false)
                        redeemInboundInviteIfPresent()
                        _effects.emit(AuthLandingEffect.NavigateToBootstrap)
                    } else {
                        _uiState.value = _uiState.value.copy(isLoading = false)
                    }
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = error.toUserMessage()
                    )
                }
        }
    }

    fun onSignInClicked(activity: ComponentActivity, method: SignInMethod) {
        if (_uiState.value.isLoading) return
        analytics.track(AnalyticsEvent.OnboardingStepViewed(OnboardingStep.SIGN_IN))
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, signingInWith = method, errorMessage = null)
            val result = when (method) {
                SignInMethod.GOOGLE -> authRepository.signInWithGoogle(activity)
                SignInMethod.APPLE -> authRepository.signInWithApple(activity)
            }
            result
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isLoading = false, signingInWith = null)
                    redeemInboundInviteIfPresent()
                    _effects.emit(AuthLandingEffect.NavigateToBootstrap)
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        signingInWith = null,
                        errorMessage = when (method) {
                            SignInMethod.GOOGLE -> error.toUserMessage()
                            SignInMethod.APPLE -> error.toAppleUserMessage()
                        }
                    )
                }
        }
    }

    /** Apple's own failures read as on iPhone; the server's go through the usual words. */
    private fun Throwable.toAppleUserMessage(): String? = when (this) {
        is AppleSignInException.Cancelled -> null
        is AppleSignInException.Failed -> APPLE_SIGN_IN_FAILED
        else -> UserFacingError.message(this, GENERIC_FAILURE)
    }

    private fun Throwable.toUserMessage(): String? {
        val message = message?.trim()
        return when {
            this is GetCredentialCancellationException -> null
            this is java.io.IOException -> UserFacingError.OFFLINE
            message.isNullOrBlank() -> "Couldn’t sign in with Google. Try again."
            message.contains("Google Play services is missing or out of date", ignoreCase = true) ->
                "Google Play services is missing or out of date on this device. Update it and try again."
            message.contains("no provider dependencies found", ignoreCase = true) ->
                "Google Play services is missing or out of date on this device. Update it and try again."
            message.contains("Google server client id is not configured", ignoreCase = true) ->
                "Google sign-in isn’t available in this build."
            message.contains("Unable to parse Google credential", ignoreCase = true) ->
                "Couldn’t sign in with Google. Try again."
            else -> "Couldn’t sign in with Google. Try again."
        }
    }

    private suspend fun redeemInboundInviteIfPresent() {
        // Ensure install-referrer ingestion has happened before we check for a pending inbound code.
        installReferrerInboundInviteIngester.ingestIfNeeded()
        val inbound = inboundInviteRepository.pendingInvite.first() ?: return
        val now = System.currentTimeMillis()
        if (now - inbound.receivedAtMs > INBOUND_INVITE_TTL_MS) {
            inboundInviteRepository.clearPendingInvite()
            return
        }
        if (inbound.dismissedAtMs != null) return

        inviteRepository.redeemInvite(inbound.code)
            .onSuccess {
                inboundInviteRepository.clearPendingInvite()
            }
            .onFailure { error ->
                // Keep the pending invite code so the user can manually continue via "Enter code".
                _uiState.value = _uiState.value.copy(errorMessage = error.toInviteUserMessage())
            }
    }

    private fun Throwable.toInviteUserMessage(): String? =
        UserFacingError.message(this, "Couldn’t open the invite. Try again.")

    private companion object {
        const val INBOUND_INVITE_TTL_MS = 24 * 60 * 60 * 1000L
        const val APPLE_SIGN_IN_FAILED = "Sign in with Apple didn’t finish. Try again."
        const val GENERIC_FAILURE = "Mulberry couldn’t finish that. Try again."
    }
}
