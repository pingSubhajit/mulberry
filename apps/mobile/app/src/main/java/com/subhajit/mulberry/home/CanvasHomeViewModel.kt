package com.subhajit.mulberry.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.config.AppConfig
import com.subhajit.mulberry.core.flags.FeatureFlagProvider
import com.subhajit.mulberry.core.flags.FeatureFlags
import com.subhajit.mulberry.bootstrap.BootstrapRepository
import com.subhajit.mulberry.canvas.CanvasRenderState
import com.subhajit.mulberry.canvas.CanvasRuntime
import com.subhajit.mulberry.canvas.CanvasRuntimeEvent
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapState
import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.drawing.model.CanvasState
import com.subhajit.mulberry.drawing.model.DrawingDefaults
import com.subhajit.mulberry.drawing.model.DrawingTool
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.drawing.model.ToolState
import com.subhajit.mulberry.drawing.render.CanvasStrokeRenderMode
import com.subhajit.mulberry.pairing.CreateInviteResult
import com.subhajit.mulberry.pairing.InviteRepository
import com.subhajit.mulberry.settings.PairingDisconnectCoordinator
import com.subhajit.mulberry.wallpaper.BackgroundImageRepository
import com.subhajit.mulberry.wallpaper.BackgroundImageState
import com.subhajit.mulberry.wallpaper.DefaultWallpaperPresets
import com.subhajit.mulberry.wallpaper.WallpaperCoordinator
import com.subhajit.mulberry.wallpaper.WallpaperPreset
import com.subhajit.mulberry.wallpaper.WallpaperStatusState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class MainAppTab {
    Canvas,
    LockScreen
}

enum class HomePairingSheetMode {
    Hidden,
    ShareInvite,
    JoinCodeEntry,
    PairingConfirmed
}

data class InviteSheetUiState(
    val code: String? = null,
    val expiresAt: String? = null,
    val remainingSeconds: Long = 0L,
    val isExpired: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
) {
    val hasCode: Boolean
        get() = !code.isNullOrBlank()
}

data class JoinCodeUiState(
    val code: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null
)

data class PairingConfirmationUiState(
    val isDisconnecting: Boolean = false,
    val errorMessage: String? = null
)

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
    val pairingSheetMode: HomePairingSheetMode = HomePairingSheetMode.Hidden,
    val inviteSheet: InviteSheetUiState = InviteSheetUiState(),
    val joinCode: JoinCodeUiState = JoinCodeUiState(),
    val pairingConfirmation: PairingConfirmationUiState = PairingConfirmationUiState(),
    val selectedWallpaperPresetResId: Int? = null,
    val isWallpaperBusy: Boolean = false,
    val wallpaperErrorMessage: String? = null,
    val showClearConfirmation: Boolean = false,
    val canvasStrokeRenderMode: CanvasStrokeRenderMode = CanvasStrokeRenderMode.Hybrid,
    val palette: List<Long> = DrawingDefaults.palette
)

sealed interface CanvasHomeEffect {
    data class ShareInvite(val message: String) : CanvasHomeEffect
    data object OpenWallpaperSetup : CanvasHomeEffect
}

@HiltViewModel
class CanvasHomeViewModel @Inject constructor(
    repository: SessionBootstrapRepository,
    private val bootstrapRepository: BootstrapRepository,
    featureFlagProvider: FeatureFlagProvider,
    private val drawingRepository: DrawingRepository,
    private val inviteRepository: InviteRepository,
    private val canvasRuntime: CanvasRuntime,
    private val backgroundImageRepository: BackgroundImageRepository,
    private val wallpaperCoordinator: WallpaperCoordinator,
    private val pairingDisconnectCoordinator: PairingDisconnectCoordinator,
    appConfig: AppConfig
) : ViewModel() {
    private val showClearConfirmation = MutableStateFlow(false)
    private val pairingSheetMode = MutableStateFlow(HomePairingSheetMode.Hidden)
    private val inviteLoadingState = MutableStateFlow(false)
    private val inviteErrorState = MutableStateFlow<String?>(null)
    private val joinCodeState = MutableStateFlow(JoinCodeUiState())
    private val pairingConfirmationState = MutableStateFlow(PairingConfirmationUiState())
    private val selectedWallpaperPresetState = MutableStateFlow<Int?>(null)
    private val wallpaperBusyState = MutableStateFlow(false)
    private val wallpaperErrorState = MutableStateFlow<String?>(null)
    private val currentTimeMillis = MutableStateFlow(System.currentTimeMillis())
    private val _effects = MutableSharedFlow<CanvasHomeEffect>()
    val effects = _effects.asSharedFlow()
    val wallpaperPresets: List<WallpaperPreset> = DefaultWallpaperPresets

    private val baseState = combine(
        combine(
            repository.state,
            featureFlagProvider.flags,
            canvasRuntime.renderState
        ) { bootstrapState, flags, renderState ->
            PartialCanvasHomeState(
                bootstrapState = bootstrapState,
                featureFlags = flags,
                renderState = renderState
            )
        },
        wallpaperCoordinator.wallpaperStatus(),
        backgroundImageRepository.backgroundState,
        inviteRepository.currentInvite
    ) { partialState, wallpaperStatus, backgroundState, currentInvite ->
        BaseCanvasHomeState(
            bootstrapState = partialState.bootstrapState,
            featureFlags = partialState.featureFlags,
            renderState = partialState.renderState,
            wallpaperStatus = wallpaperStatus,
            backgroundState = backgroundState,
            currentInvite = currentInvite
        )
    }

    private val inviteControlsBase = combine(
        showClearConfirmation,
        pairingSheetMode,
        inviteLoadingState,
        inviteErrorState,
        currentTimeMillis
    ) { clearDialogVisible, sheetMode, inviteLoading, inviteError, nowMillis ->
        InviteControlState(
            clearDialogVisible = clearDialogVisible,
            sheetMode = sheetMode,
            inviteLoading = inviteLoading,
            inviteError = inviteError,
            nowMillis = nowMillis
        )
    }

    private val inviteControls = combine(
        inviteControlsBase,
        joinCodeState,
        pairingConfirmationState
    ) { controls, joinCode, confirmation ->
        controls.copy(
            joinCode = joinCode,
            confirmation = confirmation
        )
    }

    private val wallpaperControls = combine(
        selectedWallpaperPresetState,
        wallpaperBusyState,
        wallpaperErrorState
    ) { selectedWallpaperPreset, wallpaperBusy, wallpaperError ->
        WallpaperControlState(
            selectedPreset = selectedWallpaperPreset,
            isBusy = wallpaperBusy,
            error = wallpaperError
        )
    }

    val uiState = combine(
        baseState,
        inviteControls,
        wallpaperControls
    ) { baseState, inviteControls, wallpaperControls ->
        CanvasHomeUiState(
            environmentLabel = appConfig.environment.displayName,
            bootstrapState = baseState.bootstrapState,
            featureFlags = baseState.featureFlags,
            canvasState = baseState.renderState.toCanvasState(),
            toolState = baseState.renderState.toolState,
            wallpaperStatus = baseState.wallpaperStatus,
            backgroundImageState = baseState.backgroundState,
            currentInvite = baseState.currentInvite,
            pairingSheetMode = inviteControls.sheetMode,
            inviteSheet = baseState.currentInvite.toInviteSheetUiState(
                nowMillis = inviteControls.nowMillis,
                isLoading = inviteControls.inviteLoading,
                errorMessage = inviteControls.inviteError
            ),
            joinCode = inviteControls.joinCode,
            pairingConfirmation = inviteControls.confirmation,
            selectedWallpaperPresetResId = wallpaperControls.selectedPreset,
            isWallpaperBusy = wallpaperControls.isBusy,
            wallpaperErrorMessage = wallpaperControls.error,
            showClearConfirmation = inviteControls.clearDialogVisible,
            canvasStrokeRenderMode = appConfig.canvasStrokeRenderMode
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CanvasHomeUiState(
            environmentLabel = appConfig.environment.displayName
        )
    )

    private val sessionRepository = repository

    init {
        viewModelScope.launch {
            while (true) {
                currentTimeMillis.value = System.currentTimeMillis()
                delay(1_000)
            }
        }
        viewModelScope.launch {
            var previousStatus: PairingStatus? = null
            sessionRepository.state.collect { state ->
                val oldStatus = previousStatus
                previousStatus = state.pairingStatus
                if (
                    oldStatus != null &&
                    oldStatus != PairingStatus.PAIRED &&
                    state.pairingStatus == PairingStatus.PAIRED
                ) {
                    inviteRepository.clearCurrentInvite()
                    inviteLoadingState.value = false
                    joinCodeState.value = JoinCodeUiState()
                    pairingConfirmationState.value = PairingConfirmationUiState()
                    pairingSheetMode.value = HomePairingSheetMode.PairingConfirmed
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                val state = uiState.value
                val activeInvite = state.currentInvite
                if (
                    state.bootstrapState.pairingStatus != PairingStatus.PAIRED &&
                    activeInvite != null &&
                    !activeInvite.isExpiredAt(System.currentTimeMillis())
                ) {
                    bootstrapRepository.refreshBootstrap()
                }
                delay(2_000)
            }
        }
    }

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

    fun refreshBootstrapState() {
        viewModelScope.launch {
            bootstrapRepository.refreshBootstrap()
        }
    }

    fun onInviteRequested() {
        pairingSheetMode.value = HomePairingSheetMode.ShareInvite
        viewModelScope.launch {
            inviteRepository.clearCurrentInvite()
            inviteLoadingState.value = true
            inviteErrorState.value = null
            inviteRepository.createInvite()
                .onSuccess {
                    inviteLoadingState.value = false
                    bootstrapRepository.refreshBootstrap()
                }
                .onFailure { error ->
                    inviteLoadingState.value = false
                    inviteErrorState.value = error.message ?: "Unable to create invite"
                }
        }
    }

    fun onPairingSheetDismissed() {
        pairingSheetMode.value = HomePairingSheetMode.Hidden
        inviteErrorState.value = null
        joinCodeState.value = JoinCodeUiState()
        pairingConfirmationState.value = PairingConfirmationUiState()
        refreshBootstrapState()
    }

    fun onInviteSheetDismissed() {
        onPairingSheetDismissed()
    }

    fun onJoinCodeRequested() {
        pairingSheetMode.value = HomePairingSheetMode.JoinCodeEntry
        joinCodeState.value = JoinCodeUiState()
        inviteErrorState.value = null
        pairingConfirmationState.value = PairingConfirmationUiState()
    }

    fun onJoinCodeChanged(code: String) {
        val sanitizedCode = code.filter(Char::isDigit).take(6)
        joinCodeState.value = joinCodeState.value.copy(
            code = sanitizedCode,
            errorMessage = null
        )
    }

    fun onJoinCodeSubmitted() {
        val code = joinCodeState.value.code
        if (code.length != 6 || joinCodeState.value.isSubmitting) return

        viewModelScope.launch {
            joinCodeState.value = joinCodeState.value.copy(
                isSubmitting = true,
                errorMessage = null
            )
            val redeemedInvite = inviteRepository.redeemInvite(code).getOrElse { error ->
                joinCodeState.value = joinCodeState.value.copy(
                    isSubmitting = false,
                    errorMessage = error.message ?: "Unable to redeem invite code"
                )
                return@launch
            }
            inviteRepository.acceptInvite(redeemedInvite.inviteId)
                .onSuccess {
                    joinCodeState.value = JoinCodeUiState()
                    pairingConfirmationState.value = PairingConfirmationUiState()
                    bootstrapRepository.refreshBootstrap()
                    pairingSheetMode.value = HomePairingSheetMode.PairingConfirmed
                }
                .onFailure { error ->
                    joinCodeState.value = joinCodeState.value.copy(
                        isSubmitting = false,
                        errorMessage = error.message ?: "Unable to accept invite"
                    )
                }
        }
    }

    fun onDisconnectFromConfirmation() {
        if (pairingConfirmationState.value.isDisconnecting) return

        viewModelScope.launch {
            pairingConfirmationState.value = pairingConfirmationState.value.copy(
                isDisconnecting = true,
                errorMessage = null
            )
            pairingDisconnectCoordinator.disconnectPartner()
                .onSuccess {
                    pairingConfirmationState.value = PairingConfirmationUiState()
                    pairingSheetMode.value = HomePairingSheetMode.Hidden
                    inviteRepository.clearCurrentInvite()
                    bootstrapRepository.refreshBootstrap()
                }
                .onFailure { error ->
                    pairingConfirmationState.value = PairingConfirmationUiState(
                        isDisconnecting = false,
                        errorMessage = error.message ?: "Unable to disconnect partner"
                    )
                }
        }
    }

    fun onShareInviteClicked() {
        viewModelScope.launch {
            val invite = ensureShareableInvite().getOrElse { error ->
                inviteErrorState.value = error.message ?: "Unable to create invite"
                return@launch
            }
            _effects.emit(CanvasHomeEffect.ShareInvite(invite.toShareMessage()))
        }
    }

    private suspend fun ensureShareableInvite(): Result<CreateInviteResult> {
        inviteRepository.clearCurrentInvite()
        inviteLoadingState.value = true
        inviteErrorState.value = null
        return inviteRepository.createInvite()
            .onSuccess {
                inviteLoadingState.value = false
                bootstrapRepository.refreshBootstrap()
            }
            .onFailure {
                inviteLoadingState.value = false
            }
    }

    fun onCanvasPress(point: StrokePoint) {
        canvasRuntime.submit(CanvasRuntimeEvent.LocalPress(point))
    }

    fun onCanvasDrag(point: StrokePoint) {
        canvasRuntime.submit(CanvasRuntimeEvent.LocalDrag(point))
    }

    fun onCanvasRelease() {
        canvasRuntime.submit(CanvasRuntimeEvent.LocalRelease)
    }

    fun onCanvasTap(point: StrokePoint) {
        canvasRuntime.submit(CanvasRuntimeEvent.EraseAt(point))
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
        canvasRuntime.submit(CanvasRuntimeEvent.ClearCanvas)
        showClearConfirmation.value = false
    }

    fun onBackgroundImageSelected(uri: Uri) {
        viewModelScope.launch {
            runWallpaperUpdate {
                selectedWallpaperPresetState.value = null
                backgroundImageRepository.importBackground(uri)
            }
        }
    }

    fun onBackgroundImageCleared() {
        viewModelScope.launch {
            runWallpaperUpdate {
                selectedWallpaperPresetState.value = null
                backgroundImageRepository.clearBackground()
            }
        }
    }

    fun onWallpaperPresetSelected(drawableResId: Int) {
        viewModelScope.launch {
            runWallpaperUpdate {
                selectedWallpaperPresetState.value = drawableResId
                backgroundImageRepository.importBundledBackground(drawableResId)
            }
        }
    }

    fun onSetUpLockScreenClicked() {
        viewModelScope.launch {
            wallpaperBusyState.value = true
            wallpaperErrorState.value = null
            runCatching {
                ensureDefaultBackgroundIfNeeded()
                wallpaperCoordinator.ensureSnapshotCurrent()
            }.onSuccess {
                _effects.emit(CanvasHomeEffect.OpenWallpaperSetup)
            }.onFailure { error ->
                wallpaperErrorState.value = error.message ?: "Unable to prepare wallpaper setup"
            }
            wallpaperBusyState.value = false
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
        val renderState: CanvasRenderState,
        val wallpaperStatus: WallpaperStatusState,
        val backgroundState: BackgroundImageState,
        val currentInvite: CreateInviteResult?
    )

    private data class PartialCanvasHomeState(
        val bootstrapState: SessionBootstrapState,
        val featureFlags: FeatureFlags,
        val renderState: CanvasRenderState
    )

    private data class InviteControlState(
        val clearDialogVisible: Boolean,
        val sheetMode: HomePairingSheetMode,
        val inviteLoading: Boolean,
        val inviteError: String?,
        val nowMillis: Long,
        val joinCode: JoinCodeUiState = JoinCodeUiState(),
        val confirmation: PairingConfirmationUiState = PairingConfirmationUiState()
    )

    private data class WallpaperControlState(
        val selectedPreset: Int?,
        val isBusy: Boolean,
        val error: String?
    )

    private suspend fun runWallpaperUpdate(block: suspend () -> Unit) {
        wallpaperErrorState.value = null
        runCatching {
            block()
            wallpaperCoordinator.ensureSnapshotCurrent()
            wallpaperCoordinator.notifyWallpaperUpdatedIfSelected()
        }.onFailure { error ->
            wallpaperErrorState.value = error.message ?: "Unable to update background"
        }
    }

    private suspend fun ensureDefaultBackgroundIfNeeded() {
        if (!backgroundImageRepository.getCurrentBackgroundState().isConfigured) {
            selectedWallpaperPresetState.value = selectedWallpaperPresetState.value
                ?: R.drawable.wallpaper_default_bg
            backgroundImageRepository.importBundledBackground(R.drawable.wallpaper_default_bg)
        }
    }
}

private fun CreateInviteResult?.toInviteSheetUiState(
    nowMillis: Long,
    isLoading: Boolean,
    errorMessage: String?
): InviteSheetUiState {
    val expiresAtMillis = this?.expiresAt?.parseInviteInstantMillis()
    val remainingSeconds = expiresAtMillis
        ?.let { ((it - nowMillis) / 1_000).coerceAtLeast(0L) }
        ?: 0L
    return InviteSheetUiState(
        code = this?.code,
        expiresAt = this?.expiresAt,
        remainingSeconds = remainingSeconds,
        isExpired = this != null && remainingSeconds <= 0L,
        isLoading = isLoading,
        errorMessage = errorMessage
    )
}

private fun CreateInviteResult.isExpiredAt(nowMillis: Long): Boolean {
    val expiresAtMillis = expiresAt.parseInviteInstantMillis() ?: return false
    return expiresAtMillis <= nowMillis
}

private fun CreateInviteResult.toShareMessage(): String =
    "Join me on Mulberry. Use invite code $code to pair with me and share a canvas on our lock screens. This code expires soon."

private val inviteDateFormats = listOf(
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US),
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
).onEach { it.timeZone = TimeZone.getTimeZone("UTC") }

private fun String.parseInviteInstantMillis(): Long? =
    inviteDateFormats.firstNotNullOfOrNull { format ->
        runCatching { format.parse(this)?.time }.getOrNull()
    }
