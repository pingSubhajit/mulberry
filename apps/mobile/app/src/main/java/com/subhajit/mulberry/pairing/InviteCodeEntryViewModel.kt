package com.subhajit.mulberry.pairing

import com.subhajit.mulberry.core.UserFacingError
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.subhajit.mulberry.pairing.inbound.InboundInviteRepository

@HiltViewModel
class InviteCodeEntryViewModel @Inject constructor(
    private val inviteRepository: InviteRepository,
    private val inboundInviteRepository: InboundInviteRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(InviteCodeEntryUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<InviteCodeEntryEffect>()
    val effects = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            val inboundCode = inboundInviteRepository.pendingInvite.first()?.code
            if (!inboundCode.isNullOrBlank()) {
                _uiState.value = _uiState.value.copy(code = inboundCode)
            }
        }
    }

    fun onCodeChanged(value: String) {
        _uiState.value = _uiState.value.copy(code = value, errorMessage = null)
    }

    fun onSubmitClicked() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSubmitting = true, errorMessage = null)
            inviteRepository.redeemInvite(_uiState.value.code)
                .onSuccess {
                    if (inboundInviteRepository.pendingInvite.first()?.code == _uiState.value.code) {
                        inboundInviteRepository.clearPendingInvite()
                    }
                    _uiState.value = _uiState.value.copy(isSubmitting = false)
                    _effects.emit(InviteCodeEntryEffect.NavigateToBootstrap)
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isSubmitting = false,
                        errorMessage = UserFacingError.message(error, "Couldn’t join with that code. Try again.")
                    )
                }
        }
    }
}

