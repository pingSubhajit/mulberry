package com.subhajit.elaris.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class OnboardingNameViewModel @Inject constructor(
    private val draftRepository: OnboardingDraftRepository
) : ViewModel() {
    private val _effects = MutableSharedFlow<OnboardingNameEffect>()
    val effects = _effects.asSharedFlow()

    val uiState = draftRepository.draft.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UserProfileDraft()
    )

    fun onDisplayNameChanged(value: String) {
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
