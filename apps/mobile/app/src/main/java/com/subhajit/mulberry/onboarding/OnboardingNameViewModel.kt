package com.subhajit.mulberry.onboarding

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

@HiltViewModel
class OnboardingNameViewModel @Inject constructor(
    private val draftRepository: OnboardingDraftRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(UserProfileDraft())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<OnboardingNameEffect>()
    val effects = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            _uiState.value = draftRepository.draft.first()
        }
    }

    fun onDisplayNameChanged(value: String) {
        _uiState.value = _uiState.value.copy(displayName = value)
        viewModelScope.launch {
            draftRepository.updateDisplayName(value)
        }
    }

    fun onContinueClicked() {
        viewModelScope.launch {
            _effects.emit(OnboardingNameEffect.NavigateToDetails)
        }
    }

    fun onEnterCodeClicked() {
        viewModelScope.launch {
            _effects.emit(OnboardingNameEffect.NavigateToCodeEntry)
        }
    }
}
