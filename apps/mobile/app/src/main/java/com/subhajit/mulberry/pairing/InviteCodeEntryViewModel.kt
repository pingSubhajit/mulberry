package com.subhajit.mulberry.pairing

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
class InviteCodeEntryViewModel @Inject constructor(
    private val inviteRepository: InviteRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(InviteCodeEntryUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<InviteCodeEntryEffect>()
    val effects = _effects.asSharedFlow()

    fun onCodeChanged(value: String) {
        _uiState.value = _uiState.value.copy(code = value, errorMessage = null)
    }

    fun onSubmitClicked() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSubmitting = true, errorMessage = null)
            inviteRepository.redeemInvite(_uiState.value.code)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isSubmitting = false)
                    _effects.emit(InviteCodeEntryEffect.NavigateToBootstrap)
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isSubmitting = false,
                        errorMessage = error.message ?: "Unable to redeem invite"
                    )
                }
        }
    }
}
