package com.subhajit.mulberry.onboarding

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subhajit.mulberry.R
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapState
import com.subhajit.mulberry.wallpaper.BackgroundImageRepository
import com.subhajit.mulberry.wallpaper.BackgroundImageState
import com.subhajit.mulberry.wallpaper.WallpaperCoordinator
import com.subhajit.mulberry.wallpaper.WallpaperStatusState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WallpaperPreset(
    @DrawableRes val drawableResId: Int,
    @DrawableRes val previewDrawableResId: Int,
    @DrawableRes val thumbnailDrawableResId: Int,
    val label: String
)

data class OnboardingWallpaperUiState(
    val bootstrapState: SessionBootstrapState = SessionBootstrapState(),
    val wallpaperStatus: WallpaperStatusState = WallpaperStatusState(),
    val backgroundImageState: BackgroundImageState = BackgroundImageState(),
    @DrawableRes val selectedPresetResId: Int? = null,
    val isBusy: Boolean = false,
    val errorMessage: String? = null
) {
    val canComplete: Boolean
        get() = wallpaperStatus.isWallpaperSelected
}

sealed interface OnboardingWallpaperEffect {
    data object OpenWallpaperSetup : OnboardingWallpaperEffect
    data object NavigateHome : OnboardingWallpaperEffect
}

@HiltViewModel
class OnboardingWallpaperViewModel @Inject constructor(
    sessionBootstrapRepository: SessionBootstrapRepository,
    private val backgroundImageRepository: BackgroundImageRepository,
    private val wallpaperCoordinator: WallpaperCoordinator
) : ViewModel() {
    private val busyState = MutableStateFlow(false)
    private val errorState = MutableStateFlow<String?>(null)
    private val selectedPresetState = MutableStateFlow<Int?>(null)

    val presets: List<WallpaperPreset> = listOf(
        WallpaperPreset(
            drawableResId = R.drawable.wallpaper_preset_1,
            previewDrawableResId = R.drawable.wallpaper_preset_1_preview,
            thumbnailDrawableResId = R.drawable.wallpaper_preset_1_thumb,
            label = "Warm canyon"
        ),
        WallpaperPreset(
            drawableResId = R.drawable.wallpaper_preset_2,
            previewDrawableResId = R.drawable.wallpaper_preset_2_preview,
            thumbnailDrawableResId = R.drawable.wallpaper_preset_2_thumb,
            label = "Red waves"
        ),
        WallpaperPreset(
            drawableResId = R.drawable.wallpaper_preset_3,
            previewDrawableResId = R.drawable.wallpaper_preset_3_preview,
            thumbnailDrawableResId = R.drawable.wallpaper_preset_3_thumb,
            label = "Quiet dusk"
        ),
        WallpaperPreset(
            drawableResId = R.drawable.wallpaper_preset_4,
            previewDrawableResId = R.drawable.wallpaper_preset_4_preview,
            thumbnailDrawableResId = R.drawable.wallpaper_preset_4_thumb,
            label = "Mulberry glow"
        )
    )

    private val baseState = combine(
        sessionBootstrapRepository.state,
        wallpaperCoordinator.wallpaperStatus(),
        backgroundImageRepository.backgroundState
    ) { bootstrapState, wallpaperStatus, backgroundState ->
        WallpaperOnboardingBaseState(
            bootstrapState = bootstrapState,
            wallpaperStatus = wallpaperStatus,
            backgroundState = backgroundState
        )
    }

    val uiState = combine(
        baseState,
        selectedPresetState,
        busyState,
        errorState
    ) { baseState, selectedPreset, isBusy, errorMessage ->
        OnboardingWallpaperUiState(
            bootstrapState = baseState.bootstrapState,
            wallpaperStatus = baseState.wallpaperStatus,
            backgroundImageState = baseState.backgroundState,
            selectedPresetResId = selectedPreset,
            isBusy = isBusy,
            errorMessage = errorMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = OnboardingWallpaperUiState()
    )

    private val sessionRepository = sessionBootstrapRepository

    private val _effects = MutableSharedFlow<OnboardingWallpaperEffect>()
    val effects = _effects.asSharedFlow()

    fun onGalleryBackgroundSelected(uri: Uri) {
        viewModelScope.launch {
            runBackgroundUpdate {
                selectedPresetState.value = null
                backgroundImageRepository.importBackground(uri)
            }
        }
    }

    fun onPresetSelected(@DrawableRes drawableResId: Int) {
        viewModelScope.launch {
            runBackgroundUpdate {
                selectedPresetState.value = drawableResId
                backgroundImageRepository.importBundledBackground(drawableResId)
            }
        }
    }

    fun onSetUpLockScreenClicked() {
        viewModelScope.launch {
            busyState.value = true
            errorState.value = null
            runCatching {
                ensureDefaultBackgroundIfNeeded()
                wallpaperCoordinator.ensureSnapshotCurrent()
            }.onSuccess {
                _effects.emit(OnboardingWallpaperEffect.OpenWallpaperSetup)
            }.onFailure { error ->
                errorState.value = error.message ?: "Unable to prepare wallpaper setup"
            }
            busyState.value = false
        }
    }

    fun refreshWallpaperStatus() {
        viewModelScope.launch {
            wallpaperCoordinator.notifyWallpaperUpdatedIfSelected()
        }
    }

    fun onAllDoneClicked() {
        viewModelScope.launch {
            if (!uiState.value.canComplete) return@launch
            sessionRepository.setWallpaperConfigured(true)
            _effects.emit(OnboardingWallpaperEffect.NavigateHome)
        }
    }

    private suspend fun runBackgroundUpdate(block: suspend () -> Unit) {
        errorState.value = null
        runCatching {
            block()
            wallpaperCoordinator.ensureSnapshotCurrent()
            wallpaperCoordinator.notifyWallpaperUpdatedIfSelected()
        }.onFailure { error ->
            errorState.value = error.message ?: "Unable to update background"
        }
    }

    private suspend fun ensureDefaultBackgroundIfNeeded() {
        if (!backgroundImageRepository.getCurrentBackgroundState().isConfigured) {
            selectedPresetState.update { it ?: R.drawable.wallpaper_default_bg }
            backgroundImageRepository.importBundledBackground(R.drawable.wallpaper_default_bg)
        }
    }

    private data class WallpaperOnboardingBaseState(
        val bootstrapState: SessionBootstrapState,
        val wallpaperStatus: WallpaperStatusState,
        val backgroundState: BackgroundImageState
    )
}
