package com.subhajit.mulberry.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
class PairingHubViewModel @Inject constructor(
    private val inviteRepository: InviteRepository
) : ViewModel() {
    private val loadingState = MutableStateFlow(false)
    private val errorState = MutableStateFlow<String?>(null)
    private val _effects = MutableSharedFlow<PairingHubEffect>()
    val effects = _effects.asSharedFlow()

    val uiState = combine(
        inviteRepository.currentInvite,
        loadingState,
        errorState
    ) { invite, isLoading, error ->
        PairingHubUiState(
            currentInvite = invite,
            isLoading = isLoading,
            errorMessage = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PairingHubUiState()
    )

    fun onCreateCodeClicked() {
        viewModelScope.launch {
            loadingState.value = true
            errorState.value = null
            inviteRepository.createInvite()
                .onSuccess { loadingState.value = false }
                .onFailure { error ->
                    loadingState.value = false
                    errorState.value = error.message ?: "Unable to create invite"
                }
        }
    }

    fun onEnterCodeClicked() {
        viewModelScope.launch {
            _effects.emit(PairingHubEffect.NavigateToCodeEntry)
        }
    }
}
