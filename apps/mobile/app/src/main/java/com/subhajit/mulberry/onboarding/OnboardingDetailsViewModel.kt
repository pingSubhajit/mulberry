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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class OnboardingDetailsViewModel @Inject constructor(
    private val draftRepository: OnboardingDraftRepository,
    private val profileRepository: ProfileRepository
) : ViewModel() {
    private val submitState = MutableStateFlow(false)
    private val errorState = MutableStateFlow<String?>(null)
    private val draftState = MutableStateFlow(UserProfileDraft())
    private val _uiState = MutableStateFlow(OnboardingDetailsUiState())
    val uiState = _uiState.asStateFlow()
    private val _effects = MutableSharedFlow<OnboardingDetailsEffect>()
    val effects = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            draftState.value = draftRepository.draft.first()
            publishState()
        }
    }

    fun onPartnerNameChanged(value: String) {
        draftState.update { it.copy(partnerDisplayName = value) }
        errorState.value = null
        publishState()
        viewModelScope.launch {
            draftRepository.updatePartnerDetails(
                partnerDisplayName = value,
                anniversaryDate = draftState.value.anniversaryDate
            )
        }
    }

    fun onAnniversaryChanged(value: String) {
        draftState.update { it.copy(anniversaryDate = value) }
        errorState.value = null
        publishState()
        viewModelScope.launch {
            draftRepository.updatePartnerDetails(
                partnerDisplayName = draftState.value.partnerDisplayName,
                anniversaryDate = value
            )
        }
    }

    fun onSubmitClicked() {
        viewModelScope.launch {
            submitState.value = true
            errorState.value = null
            publishState()
            profileRepository.submitProfile(draftState.value)
                .onSuccess {
                    submitState.value = false
                    publishState()
                    _effects.emit(OnboardingDetailsEffect.NavigateToBootstrap)
                }
                .onFailure { error ->
                    submitState.value = false
                    errorState.value = error.message ?: "Unable to complete onboarding"
                    publishState()
                }
        }
    }

    private fun publishState() {
        _uiState.value = OnboardingDetailsUiState(
            draft = draftState.value,
            isSubmitting = submitState.value,
            errorMessage = errorState.value
        )
    }
}
