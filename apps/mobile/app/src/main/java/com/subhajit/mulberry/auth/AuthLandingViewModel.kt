package com.subhajit.mulberry.auth

import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    private val _effects = MutableSharedFlow<AuthLandingEffect>()
    val effects = _effects.asSharedFlow()

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
                        errorMessage = error.message ?: "Unable to sign in"
                    )
                }
        }
    }
}
