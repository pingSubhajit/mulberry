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
import kotlinx.coroutines.launch

@HiltViewModel
class AuthLandingViewModel @Inject constructor(
    private val authRepository: AuthRepository
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
}
