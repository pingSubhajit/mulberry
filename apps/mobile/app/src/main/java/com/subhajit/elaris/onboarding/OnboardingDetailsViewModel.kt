package com.subhajit.elaris.onboarding

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
class OnboardingDetailsViewModel @Inject constructor(
    private val draftRepository: OnboardingDraftRepository,
    private val profileRepository: ProfileRepository
) : ViewModel() {
    private val submitState = MutableStateFlow(false)
    private val errorState = MutableStateFlow<String?>(null)
    private val _effects = MutableSharedFlow<OnboardingDetailsEffect>()
    val effects = _effects.asSharedFlow()

    val uiState = combine(
        draftRepository.draft,
        submitState,
        errorState
    ) { draft, isSubmitting, error ->
        OnboardingDetailsUiState(
            draft = draft,
            isSubmitting = isSubmitting,
            errorMessage = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = OnboardingDetailsUiState()
    )

    fun onPartnerNameChanged(value: String) {
        viewModelScope.launch {
            draftRepository.updatePartnerDetails(
                partnerDisplayName = value,
                anniversaryDate = uiState.value.draft.anniversaryDate
            )
        }
    }

    fun onAnniversaryChanged(value: String) {
        viewModelScope.launch {
            draftRepository.updatePartnerDetails(
                partnerDisplayName = uiState.value.draft.partnerDisplayName,
                anniversaryDate = value
            )
        }
    }

    fun onSubmitClicked() {
        viewModelScope.launch {
            submitState.value = true
            errorState.value = null
            profileRepository.submitProfile(uiState.value.draft)
                .onSuccess {
                    submitState.value = false
                    _effects.emit(OnboardingDetailsEffect.NavigateToBootstrap)
                }
                .onFailure { error ->
                    submitState.value = false
                    errorState.value = error.message ?: "Unable to complete onboarding"
                }
        }
    }
}
