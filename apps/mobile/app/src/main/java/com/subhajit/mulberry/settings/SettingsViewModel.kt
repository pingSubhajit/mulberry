package com.subhajit.mulberry.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subhajit.mulberry.auth.AuthRepository
import com.subhajit.mulberry.core.config.AppConfig
import com.subhajit.mulberry.core.flags.FeatureFlag
import com.subhajit.mulberry.core.flags.FeatureFlagProvider
import com.subhajit.mulberry.core.flags.FeatureFlags
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapState
import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.sync.CanvasSyncRepository
import com.subhajit.mulberry.wallpaper.BackgroundImageRepository
import com.subhajit.mulberry.wallpaper.CanvasSnapshotRenderer
import com.subhajit.mulberry.wallpaper.WallpaperCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val environmentLabel: String = "",
    val apiBaseUrl: String = "",
    val enableDebugMenu: Boolean = false,
    val featureFlags: FeatureFlags = FeatureFlags(),
    val bootstrapState: SessionBootstrapState = SessionBootstrapState()
)

sealed interface SettingsEffect {
    data object RestartFromBootstrap : SettingsEffect
    data object NavigateHome : SettingsEffect
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    repository: SessionBootstrapRepository,
    featureFlagProvider: FeatureFlagProvider,
    private val authRepository: AuthRepository,
    private val drawingRepository: DrawingRepository,
    private val canvasSyncRepository: CanvasSyncRepository,
    private val backgroundImageRepository: BackgroundImageRepository,
    private val canvasSnapshotRenderer: CanvasSnapshotRenderer,
    private val wallpaperCoordinator: WallpaperCoordinator,
    appConfig: AppConfig
) : ViewModel() {
    private val _effects = MutableSharedFlow<SettingsEffect>()
    val effects = _effects.asSharedFlow()

    val uiState = combine(repository.state, featureFlagProvider.flags) { state, flags ->
        SettingsUiState(
            environmentLabel = appConfig.environment.displayName,
            apiBaseUrl = appConfig.apiBaseUrl,
            enableDebugMenu = appConfig.enableDebugMenu,
            featureFlags = flags,
            bootstrapState = state
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(
            environmentLabel = appConfig.environment.displayName,
            apiBaseUrl = appConfig.apiBaseUrl,
            enableDebugMenu = appConfig.enableDebugMenu,
            featureFlags = appConfig.defaultFeatureFlags
        )
    )

    private val sessionRepository = repository
    private val flagsRepository = featureFlagProvider

    fun onResetAppState() {
        viewModelScope.launch {
            sessionRepository.reset()
            canvasSyncRepository.reset()
            drawingRepository.resetAllDrawingState()
            canvasSnapshotRenderer.clearSnapshots()
            backgroundImageRepository.clearBackground()
            wallpaperCoordinator.ensureSnapshotCurrent()
            wallpaperCoordinator.notifyWallpaperUpdatedIfSelected()
            _effects.emit(SettingsEffect.RestartFromBootstrap)
        }
    }

    fun onLogout() {
        viewModelScope.launch {
            canvasSyncRepository.reset()
            authRepository.logout()
            _effects.emit(SettingsEffect.RestartFromBootstrap)
        }
    }

    fun onSeedDemoSession() {
        viewModelScope.launch {
            sessionRepository.seedDemoSession()
            _effects.emit(SettingsEffect.NavigateHome)
        }
    }

    fun onFeatureFlagChanged(flag: FeatureFlag, enabled: Boolean) {
        viewModelScope.launch {
            flagsRepository.setOverride(flag, enabled)
        }
    }

    fun onClearFeatureOverrides() {
        viewModelScope.launch {
            flagsRepository.clearOverrides()
        }
    }
}
