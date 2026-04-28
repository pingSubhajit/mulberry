package com.subhajit.mulberry.auth

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
    private val inviteRepository: InviteRepository
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

    fun onGoogleSignInClicked(activity: ComponentActivity) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            authRepository.signInWithGoogle(activity)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    redeemInboundInviteIfPresent()
                    _effects.emit(AuthLandingEffect.NavigateToBootstrap)
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = error.toUserMessage()
                    )
                }
        }
    }

    private fun Throwable.toUserMessage(): String? {
        val message = message?.trim()
        return when {
            this is GetCredentialCancellationException -> null
            message.isNullOrBlank() -> "Unable to sign in with Google. Please try again."
            message.contains("Google Play services is missing or out of date", ignoreCase = true) ->
                "Google Play services is missing or out of date on this device. Update it and try again."
            message.contains("no provider dependencies found", ignoreCase = true) ->
                "Google Play services is missing or out of date on this device. Update it and try again."
            message.contains("Google server client id is not configured", ignoreCase = true) ->
                "Google sign-in is not configured for this build."
            message.contains("Unable to parse Google credential", ignoreCase = true) ->
                "Google sign-in returned an invalid credential. Please try again."
            else -> "Unable to sign in with Google. Please try again."
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

    private fun Throwable.toInviteUserMessage(): String? = when (message?.trim()) {
        "You cannot redeem your own invite" -> "That’s your invite code. Share it with your partner."
        "Invite code has expired" -> "That invite code has expired. Ask your partner to generate a new one."
        "Invite code is no longer valid" -> "That invite code is no longer valid. Ask your partner to generate a new one."
        "Invite code not found" -> "That invite code wasn’t found. Double-check the code and try again."
        "Already paired" -> "You’re already paired. Disconnect first to join a new invite."
        else -> message?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val INBOUND_INVITE_TTL_MS = 24 * 60 * 60 * 1000L
    }
}
