package com.subhajit.elaris.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subhajit.elaris.core.config.AppConfig
import com.subhajit.elaris.core.flags.FeatureFlagProvider
import com.subhajit.elaris.core.flags.FeatureFlags
import com.subhajit.elaris.data.bootstrap.SessionBootstrapRepository
import com.subhajit.elaris.data.bootstrap.SessionBootstrapState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CanvasHomeUiState(
    val environmentLabel: String = "",
    val bootstrapState: SessionBootstrapState = SessionBootstrapState(),
    val featureFlags: FeatureFlags = FeatureFlags()
)

@HiltViewModel
class CanvasHomeViewModel @Inject constructor(
    repository: SessionBootstrapRepository,
    featureFlagProvider: FeatureFlagProvider,
    appConfig: AppConfig
) : ViewModel() {
    val uiState = combine(repository.state, featureFlagProvider.flags) { state, flags ->
        CanvasHomeUiState(
            environmentLabel = appConfig.environment.displayName,
            bootstrapState = state,
            featureFlags = flags
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CanvasHomeUiState(
            environmentLabel = appConfig.environment.displayName
        )
    )

    private val sessionRepository = repository

    fun onWallpaperConfiguredChanged(configured: Boolean) {
        viewModelScope.launch {
            sessionRepository.setWallpaperConfigured(configured)
        }
    }
}
