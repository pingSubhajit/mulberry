package com.subhajit.elaris.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subhajit.elaris.data.bootstrap.SessionBootstrapRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class InviteAcceptanceViewModel @Inject constructor(
    sessionBootstrapRepository: SessionBootstrapRepository,
    private val inviteRepository: InviteRepository
) : ViewModel() {
    private val loadingState = MutableStateFlow(false)
    private val errorState = MutableStateFlow<String?>(null)
    private val _effects = MutableSharedFlow<InviteAcceptanceEffect>()
    val effects = _effects.asSharedFlow()

    val uiState = combine(
        sessionBootstrapRepository.state,
        loadingState,
        errorState
    ) { state, isLoading, error ->
        InviteAcceptanceUiState(
            pendingInvite = state.pendingInvite,
            isLoading = isLoading,
            errorMessage = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = InviteAcceptanceUiState()
    )

    fun onAcceptClicked() {
        val inviteId = uiState.value.pendingInvite?.inviteId ?: return
        viewModelScope.launch {
            loadingState.value = true
            errorState.value = null
            inviteRepository.acceptInvite(inviteId)
                .onSuccess {
                    loadingState.value = false
                    _effects.emit(InviteAcceptanceEffect.NavigateToBootstrap)
                }
                .onFailure { error ->
                    loadingState.value = false
                    errorState.value = error.message ?: "Unable to accept invite"
                }
        }
    }

    fun onDeclineClicked() {
        val inviteId = uiState.value.pendingInvite?.inviteId ?: return
        viewModelScope.launch {
            loadingState.value = true
            errorState.value = null
            inviteRepository.declineInvite(inviteId)
                .onSuccess {
                    loadingState.value = false
                    _effects.emit(InviteAcceptanceEffect.NavigateToBootstrap)
                }
                .onFailure { error ->
                    loadingState.value = false
                    errorState.value = error.message ?: "Unable to decline invite"
                }
        }
    }
}
