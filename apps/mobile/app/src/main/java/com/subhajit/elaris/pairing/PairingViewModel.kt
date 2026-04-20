package com.subhajit.elaris.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subhajit.elaris.core.config.AppConfig
import com.subhajit.elaris.core.flags.FeatureFlagProvider
import com.subhajit.elaris.data.bootstrap.SessionBootstrapRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PairingUiState(
    val environmentLabel: String = "",
    val apiBaseUrl: String = "",
    val showPlaceholderPairingControls: Boolean = true,
    val showDeveloperBootstrapActions: Boolean = false
)

sealed interface PairingEffect {
    data object NavigateToCanvasHome : PairingEffect
}

@HiltViewModel
class PairingViewModel @Inject constructor(
    repository: SessionBootstrapRepository,
    featureFlagProvider: FeatureFlagProvider,
    appConfig: AppConfig
) : ViewModel() {
    private val _effects = MutableSharedFlow<PairingEffect>()
    val effects = _effects.asSharedFlow()

    val uiState = combine(repository.state, featureFlagProvider.flags) { _, flags ->
        PairingUiState(
            environmentLabel = appConfig.environment.displayName,
            apiBaseUrl = appConfig.apiBaseUrl,
            showPlaceholderPairingControls = flags.showPlaceholderPairingControls,
            showDeveloperBootstrapActions = flags.showDeveloperBootstrapActions
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PairingUiState(
            environmentLabel = appConfig.environment.displayName,
            apiBaseUrl = appConfig.apiBaseUrl,
            showPlaceholderPairingControls = appConfig.defaultFeatureFlags.showPlaceholderPairingControls,
            showDeveloperBootstrapActions = appConfig.defaultFeatureFlags.showDeveloperBootstrapActions
        )
    )

    private val sessionRepository = repository

    fun onUseDemoSessionClicked() {
        viewModelScope.launch {
            sessionRepository.seedDemoSession()
            _effects.emit(PairingEffect.NavigateToCanvasHome)
        }
    }
}
