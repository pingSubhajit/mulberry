package com.subhajit.elaris.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subhajit.elaris.data.bootstrap.SessionBootstrapRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

sealed interface WelcomeEffect {
    data object NavigateToPairing : WelcomeEffect
}

@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val repository: SessionBootstrapRepository
) : ViewModel() {
    private val _effects = MutableSharedFlow<WelcomeEffect>()
    val effects = _effects.asSharedFlow()

    fun onContinueClicked() {
        viewModelScope.launch {
            repository.completeOnboarding()
            _effects.emit(WelcomeEffect.NavigateToPairing)
        }
    }
}
