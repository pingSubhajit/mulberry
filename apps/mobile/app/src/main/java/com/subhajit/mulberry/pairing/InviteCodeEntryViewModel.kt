package com.subhajit.mulberry.pairing

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
                        errorMessage = friendlyInviteError(error) ?: "Unable to redeem invite"
                    )
                }
        }
    }
}

private fun friendlyInviteError(error: Throwable): String? = when (error.message) {
    "You cannot redeem your own invite" -> "That’s your invite code. Share it with your partner."
    "Invite code has expired" -> "That invite code has expired. Ask your partner to generate a new one."
    "Invite code is no longer valid" -> "That invite code is no longer valid. Ask your partner to generate a new one."
    "Invite code not found" -> "That invite code wasn’t found. Double-check the code and try again."
    "Already paired" -> "You’re already paired. Disconnect first to join a new invite."
    else -> error.message
}
