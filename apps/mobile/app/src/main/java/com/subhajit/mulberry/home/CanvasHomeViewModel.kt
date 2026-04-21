package com.subhajit.mulberry.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.subhajit.mulberry.core.config.AppConfig
import com.subhajit.mulberry.core.flags.FeatureFlagProvider
import com.subhajit.mulberry.core.flags.FeatureFlags
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapState
import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.drawing.engine.StrokeHitTester
import com.subhajit.mulberry.drawing.model.CanvasState
import com.subhajit.mulberry.drawing.model.DrawingDefaults
import com.subhajit.mulberry.drawing.model.DrawingTool
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.drawing.model.ToolState
import com.subhajit.mulberry.pairing.CreateInviteResult
import com.subhajit.mulberry.pairing.InviteRepository
import com.subhajit.mulberry.wallpaper.BackgroundImageRepository
import com.subhajit.mulberry.wallpaper.BackgroundImageState
import com.subhajit.mulberry.wallpaper.WallpaperCoordinator
import com.subhajit.mulberry.wallpaper.WallpaperStatusState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CanvasHomeUiState(
    val environmentLabel: String = "",
    val bootstrapState: SessionBootstrapState = SessionBootstrapState(),
    val featureFlags: FeatureFlags = FeatureFlags(),
    val canvasState: CanvasState = CanvasState(),
    val toolState: ToolState = ToolState(
        selectedColorArgb = DrawingDefaults.DEFAULT_COLOR_ARGB,
        selectedWidth = DrawingDefaults.DEFAULT_WIDTH
    ),
    val wallpaperStatus: WallpaperStatusState = WallpaperStatusState(),
    val backgroundImageState: BackgroundImageState = BackgroundImageState(),
    val currentInvite: CreateInviteResult? = null,
    val isInviteSheetVisible: Boolean = false,
    val isInviteLoading: Boolean = false,
    val inviteErrorMessage: String? = null,
    val showClearConfirmation: Boolean = false,
    val palette: List<Long> = DrawingDefaults.palette
)

@HiltViewModel
class CanvasHomeViewModel @Inject constructor(
    repository: SessionBootstrapRepository,
    featureFlagProvider: FeatureFlagProvider,
    private val drawingRepository: DrawingRepository,
    private val strokeHitTester: StrokeHitTester,
    private val inviteRepository: InviteRepository,
    private val backgroundImageRepository: BackgroundImageRepository,
    private val wallpaperCoordinator: WallpaperCoordinator,
    appConfig: AppConfig
) : ViewModel() {
    private val showClearConfirmation = MutableStateFlow(false)
    private val showInviteSheet = MutableStateFlow(false)
    private val inviteLoadingState = MutableStateFlow(false)
    private val inviteErrorState = MutableStateFlow<String?>(null)

    private val baseState = combine(
        combine(
            repository.state,
            featureFlagProvider.flags,
            drawingRepository.canvasState,
            drawingRepository.toolState
        ) { bootstrapState, flags, canvasState, toolState ->
            PartialCanvasHomeState(
                bootstrapState = bootstrapState,
                featureFlags = flags,
                canvasState = canvasState,
                toolState = toolState
            )
        },
        wallpaperCoordinator.wallpaperStatus(),
        backgroundImageRepository.backgroundState,
        inviteRepository.currentInvite
    ) { partialState, wallpaperStatus, backgroundState, currentInvite ->
        BaseCanvasHomeState(
            bootstrapState = partialState.bootstrapState,
            featureFlags = partialState.featureFlags,
            canvasState = partialState.canvasState,
            toolState = partialState.toolState,
            wallpaperStatus = wallpaperStatus,
            backgroundState = backgroundState,
            currentInvite = currentInvite
        )
    }

    val uiState = combine(
        baseState,
        showClearConfirmation,
        showInviteSheet,
        inviteLoadingState,
        inviteErrorState
    ) { baseState, clearDialogVisible, inviteSheetVisible, inviteLoading, inviteError ->
        CanvasHomeUiState(
            environmentLabel = appConfig.environment.displayName,
            bootstrapState = baseState.bootstrapState,
            featureFlags = baseState.featureFlags,
            canvasState = baseState.canvasState,
            toolState = baseState.toolState,
            wallpaperStatus = baseState.wallpaperStatus,
            backgroundImageState = baseState.backgroundState,
            currentInvite = baseState.currentInvite,
            isInviteSheetVisible = inviteSheetVisible,
            isInviteLoading = inviteLoading,
            inviteErrorMessage = inviteError,
            showClearConfirmation = clearDialogVisible
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

    fun refreshWallpaperStatus() {
        viewModelScope.launch {
            wallpaperCoordinator.notifyWallpaperUpdatedIfSelected()
        }
    }

    fun onInviteRequested() {
        showInviteSheet.value = true
        viewModelScope.launch {
            inviteLoadingState.value = true
            inviteErrorState.value = null
            inviteRepository.createInvite()
                .onSuccess {
                    inviteLoadingState.value = false
                }
                .onFailure { error ->
                    inviteLoadingState.value = false
                    inviteErrorState.value = error.message ?: "Unable to create invite"
                }
        }
    }

    fun onInviteSheetDismissed() {
        showInviteSheet.value = false
    }

    fun onCanvasPress(point: StrokePoint) {
        if (uiState.value.toolState.activeTool != DrawingTool.DRAW) return
        viewModelScope.launch {
            drawingRepository.startStroke(point)
        }
    }

    fun onCanvasDrag(point: StrokePoint) {
        if (uiState.value.toolState.activeTool != DrawingTool.DRAW) return
        viewModelScope.launch {
            drawingRepository.appendPoint(point)
        }
    }

    fun onCanvasRelease() {
        if (uiState.value.toolState.activeTool != DrawingTool.DRAW) return
        viewModelScope.launch {
            drawingRepository.finishStroke()
        }
    }

    fun onCanvasTap(point: StrokePoint) {
        if (uiState.value.toolState.activeTool != DrawingTool.ERASE) return

        val stroke = strokeHitTester.findStrokeHit(
            strokes = uiState.value.canvasState.strokes,
            point = point
        ) ?: return

        viewModelScope.launch {
            drawingRepository.eraseStroke(stroke.id)
        }
    }

    fun onCanvasViewportChanged(widthPx: Int, heightPx: Int) {
        viewModelScope.launch {
            drawingRepository.setCanvasViewport(widthPx, heightPx)
        }
    }

    fun onColorSelected(colorArgb: Long) {
        viewModelScope.launch {
            drawingRepository.setBrushColor(colorArgb)
            drawingRepository.setTool(DrawingTool.DRAW)
        }
    }

    fun onBrushWidthChanged(width: Float) {
        viewModelScope.launch {
            drawingRepository.setBrushWidth(width)
        }
    }

    fun onEraserToggle() {
        viewModelScope.launch {
            val nextTool = if (uiState.value.toolState.activeTool == DrawingTool.ERASE) {
                DrawingTool.DRAW
            } else {
                DrawingTool.ERASE
            }
            drawingRepository.setTool(nextTool)
        }
    }

    fun onClearRequested() {
        showClearConfirmation.value = true
    }

    fun onClearDismissed() {
        showClearConfirmation.value = false
    }

    fun onClearConfirmed() {
        viewModelScope.launch {
            drawingRepository.clearCanvas()
            showClearConfirmation.value = false
        }
    }

    fun onBackgroundImageSelected(uri: Uri) {
        viewModelScope.launch {
            backgroundImageRepository.importBackground(uri)
        }
    }

    fun onBackgroundImageCleared() {
        viewModelScope.launch {
            backgroundImageRepository.clearBackground()
        }
    }

    fun onSnapshotRefreshRequested() {
        viewModelScope.launch {
            wallpaperCoordinator.ensureSnapshotCurrent()
            wallpaperCoordinator.notifyWallpaperUpdatedIfSelected()
        }
    }

    private data class BaseCanvasHomeState(
        val bootstrapState: SessionBootstrapState,
        val featureFlags: FeatureFlags,
        val canvasState: CanvasState,
        val toolState: ToolState,
        val wallpaperStatus: WallpaperStatusState,
        val backgroundState: BackgroundImageState,
        val currentInvite: CreateInviteResult?
    )

    private data class PartialCanvasHomeState(
        val bootstrapState: SessionBootstrapState,
        val featureFlags: FeatureFlags,
        val canvasState: CanvasState,
        val toolState: ToolState
    )
}
