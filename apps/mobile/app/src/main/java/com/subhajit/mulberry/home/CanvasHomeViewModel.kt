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
import com.subhajit.mulberry.network.MulberryApiService
import com.subhajit.mulberry.network.PartnerProfileRequest
import com.subhajit.mulberry.network.toDomainBootstrap
import com.subhajit.mulberry.pairing.CreateInviteResult
import com.subhajit.mulberry.pairing.InviteRepository
import com.subhajit.mulberry.pairing.inbound.InboundInviteRepository
import com.subhajit.mulberry.pairing.inbound.PendingInboundInvite
import com.subhajit.mulberry.settings.PairingDisconnectCoordinator
import com.subhajit.mulberry.wallpaper.BackgroundImageRepository
import com.subhajit.mulberry.wallpaper.BackgroundImageState
import com.subhajit.mulberry.wallpaper.DefaultWallpaperPresets
import com.subhajit.mulberry.wallpaper.RemoteWallpaper
import com.subhajit.mulberry.wallpaper.WallpaperCatalogRepository
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
    PartnerDetails,
    PairingConfirmed,
    InboundInvitePaired
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

data class PartnerDetailsFormUiState(
    val partnerDisplayName: String = "",
    val anniversaryDate: String = ANNIVERSARY_DATE_PLACEHOLDER,
    val nextUpdateAt: String? = null,
    val isSaving: Boolean = false,
    val errorMessage: String? = null
) {
    val canSubmit: Boolean
        get() = partnerDisplayName.isNotBlank() &&
            anniversaryDate.isCompleteAnniversaryDate() &&
            nextUpdateAt == null &&
            !isSaving
}

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
    val partnerDetailsForm: PartnerDetailsFormUiState = PartnerDetailsFormUiState(),
    val pairingConfirmation: PairingConfirmationUiState = PairingConfirmationUiState(),
    val selectedWallpaperPresetResId: Int? = null,
    val selectedRemoteWallpaperId: String? = null,
    val applyingRemoteWallpaperId: String? = null,
    val recentRemoteWallpapers: List<RemoteWallpaper> = emptyList(),
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
    private val inboundInviteRepository: InboundInviteRepository,
    private val apiService: MulberryApiService,
    private val canvasRuntime: CanvasRuntime,
    private val backgroundImageRepository: BackgroundImageRepository,
    private val wallpaperCatalogRepository: WallpaperCatalogRepository,
    private val wallpaperCoordinator: WallpaperCoordinator,
    private val pairingDisconnectCoordinator: PairingDisconnectCoordinator,
    appConfig: AppConfig
) : ViewModel() {
    private val showClearConfirmation = MutableStateFlow(false)
    private val pairingSheetMode = MutableStateFlow(HomePairingSheetMode.Hidden)
    private val inviteLoadingState = MutableStateFlow(false)
    private val inviteErrorState = MutableStateFlow<String?>(null)
    private val joinCodeState = MutableStateFlow(JoinCodeUiState())
    private val partnerDetailsFormState = MutableStateFlow(PartnerDetailsFormUiState())
    private val pairingConfirmationState = MutableStateFlow(PairingConfirmationUiState())
    private val autoPromptedInboundInviteCode = MutableStateFlow<String?>(null)
    private val latestInboundInvite = MutableStateFlow<PendingInboundInvite?>(null)
    private val recentRemoteWallpapersState = MutableStateFlow<List<RemoteWallpaper>>(emptyList())
    private val applyingRemoteWallpaperIdState = MutableStateFlow<String?>(null)
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
        partnerDetailsFormState,
        pairingConfirmationState
    ) { controls, joinCode, partnerDetailsForm, confirmation ->
        controls.copy(
            joinCode = joinCode,
            partnerDetailsForm = partnerDetailsForm,
            confirmation = confirmation
        )
    }

    private val wallpaperControls = combine(
        recentRemoteWallpapersState,
        applyingRemoteWallpaperIdState,
        wallpaperBusyState,
        wallpaperErrorState
    ) { recentRemoteWallpapers, applyingRemoteWallpaperId, wallpaperBusy, wallpaperError ->
        WallpaperControlState(
            recentRemoteWallpapers = recentRemoteWallpapers,
            applyingRemoteWallpaperId = applyingRemoteWallpaperId,
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
            partnerDetailsForm = inviteControls.partnerDetailsForm,
            pairingConfirmation = inviteControls.confirmation,
            selectedWallpaperPresetResId = baseState.backgroundState.selectedPresetResId,
            selectedRemoteWallpaperId = baseState.backgroundState.selectedRemoteWallpaperId,
            applyingRemoteWallpaperId = wallpaperControls.applyingRemoteWallpaperId,
            recentRemoteWallpapers = wallpaperControls.recentRemoteWallpapers,
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
            runCatching {
                wallpaperCatalogRepository.fetchPage(cursor = null, limit = 4)
            }.onSuccess { page ->
                recentRemoteWallpapersState.value = page.items
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

        viewModelScope.launch {
            combine(sessionRepository.state, inboundInviteRepository.pendingInvite) { bootstrap, inbound ->
                bootstrap to inbound
            }.collect { (bootstrap, inbound) ->
                latestInboundInvite.value = inbound
                val pending = inbound ?: return@collect
                val now = System.currentTimeMillis()
                if (now - pending.receivedAtMs > INBOUND_INVITE_TTL_MS) {
                    inboundInviteRepository.clearPendingInvite()
                    autoPromptedInboundInviteCode.value = null
                    return@collect
                }
                if (pending.dismissedAtMs != null) return@collect

                when (bootstrap.pairingStatus) {
                    PairingStatus.UNPAIRED -> {
                        if (pairingSheetMode.value != HomePairingSheetMode.Hidden) return@collect
                        if (autoPromptedInboundInviteCode.value == pending.code) return@collect
                        pairingSheetMode.value = HomePairingSheetMode.JoinCodeEntry
                        joinCodeState.value = JoinCodeUiState(code = pending.code)
                        inviteErrorState.value = null
                        autoPromptedInboundInviteCode.value = pending.code
                    }

                    PairingStatus.PAIRED -> {
                        if (pairingSheetMode.value != HomePairingSheetMode.Hidden) return@collect
                        if (autoPromptedInboundInviteCode.value == pending.code) return@collect
                        pairingSheetMode.value = HomePairingSheetMode.InboundInvitePaired
                        pairingConfirmationState.value = PairingConfirmationUiState()
                        autoPromptedInboundInviteCode.value = pending.code
                    }

                    PairingStatus.INVITE_PENDING_ACCEPTANCE -> Unit
                }
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
            delay(500)
            wallpaperCoordinator.notifyWallpaperUpdatedIfSelected()
        }
    }

    fun refreshBootstrapState() {
        viewModelScope.launch {
            bootstrapRepository.refreshBootstrap()
        }
    }

    fun onInviteRequested() {
        if (uiState.value.bootstrapState.requiresPartnerDetailsForInvite()) {
            openPartnerDetailsForm()
            return
        }
        pairingSheetMode.value = HomePairingSheetMode.ShareInvite
        viewModelScope.launch {
            createInviteForSheet()
        }
    }

    fun onPairingSheetDismissed() {
        val inboundCode = autoPromptedInboundInviteCode.value
        if (inboundCode != null && pairingSheetMode.value == HomePairingSheetMode.JoinCodeEntry) {
            viewModelScope.launch {
                latestInboundInvite.value?.takeIf { it.code == inboundCode } ?: return@launch
                inboundInviteRepository.dismissPendingInvite()
            }
        }
        pairingSheetMode.value = HomePairingSheetMode.Hidden
        inviteErrorState.value = null
        joinCodeState.value = JoinCodeUiState()
        partnerDetailsFormState.value = PartnerDetailsFormUiState()
        pairingConfirmationState.value = PairingConfirmationUiState()
        refreshBootstrapState()
    }

    fun onInviteSheetDismissed() {
        onPairingSheetDismissed()
    }

    fun onJoinCodeRequested() {
        pairingSheetMode.value = HomePairingSheetMode.JoinCodeEntry
        val inboundCode = latestInboundInvite.value?.code
        joinCodeState.value = if (inboundCode != null) {
            JoinCodeUiState(code = inboundCode)
        } else {
            JoinCodeUiState()
        }
        inviteErrorState.value = null
        partnerDetailsFormState.value = PartnerDetailsFormUiState()
        pairingConfirmationState.value = PairingConfirmationUiState()
    }

    fun onPartnerDetailsRequested() {
        openPartnerDetailsForm()
    }

    fun onPartnerNameChanged(value: String) {
        partnerDetailsFormState.value = partnerDetailsFormState.value.copy(
            partnerDisplayName = value,
            errorMessage = null
        )
    }

    fun onPartnerAnniversaryChanged(value: String) {
        partnerDetailsFormState.value = partnerDetailsFormState.value.copy(
            anniversaryDate = value.toMaskedAnniversaryDate(),
            errorMessage = null
        )
    }

    fun onPartnerDetailsSubmitted() {
        val form = partnerDetailsFormState.value
        if (!form.canSubmit) return

        viewModelScope.launch {
            partnerDetailsFormState.value = form.copy(isSaving = true, errorMessage = null)
            runCatching {
                apiService.updatePartnerProfile(
                    PartnerProfileRequest(
                        partnerDisplayName = form.partnerDisplayName.trim(),
                        anniversaryDate = form.anniversaryDate.toBackendAnniversaryDate()
                    )
                )
            }.onSuccess { response ->
                sessionRepository.cacheBootstrap(response.toDomainBootstrap())
                partnerDetailsFormState.value = PartnerDetailsFormUiState()
                pairingSheetMode.value = HomePairingSheetMode.ShareInvite
                createInviteForSheet()
            }.onFailure { error ->
                partnerDetailsFormState.value = form.copy(
                    isSaving = false,
                    errorMessage = error.message ?: "Unable to update partner details"
                )
            }
        }
    }

    fun onPairingConfirmationRequested() {
        viewModelScope.launch {
            bootstrapRepository.refreshBootstrap()
            inviteRepository.clearCurrentInvite()
            inviteLoadingState.value = false
            inviteErrorState.value = null
            joinCodeState.value = JoinCodeUiState()
            pairingConfirmationState.value = PairingConfirmationUiState()
            pairingSheetMode.value = HomePairingSheetMode.PairingConfirmed
        }
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
                    errorMessage = friendlyInviteError(error) ?: "Unable to redeem invite code"
                )
                return@launch
            }
            inviteRepository.acceptInvite(redeemedInvite.inviteId)
                .onSuccess {
                    if (latestInboundInvite.value?.code == code) {
                        inboundInviteRepository.clearPendingInvite()
                    }
                    joinCodeState.value = JoinCodeUiState()
                    pairingConfirmationState.value = PairingConfirmationUiState()
                    bootstrapRepository.refreshBootstrap()
                    pairingSheetMode.value = HomePairingSheetMode.PairingConfirmed
                }
                .onFailure { error ->
                    joinCodeState.value = joinCodeState.value.copy(
                        isSubmitting = false,
                        errorMessage = friendlyInviteError(error) ?: "Unable to accept invite"
                    )
                }
        }
    }

    fun onKeepCurrentPairingClicked() {
        viewModelScope.launch {
            inboundInviteRepository.clearPendingInvite()
            pairingSheetMode.value = HomePairingSheetMode.Hidden
            autoPromptedInboundInviteCode.value = null
        }
    }

    fun onDisconnectAndSwitchFromInboundInvite() {
        if (pairingConfirmationState.value.isDisconnecting) return
        val pendingCode = latestInboundInvite.value?.code ?: return
        viewModelScope.launch {
            pairingConfirmationState.value = pairingConfirmationState.value.copy(
                isDisconnecting = true,
                errorMessage = null
            )
            pairingDisconnectCoordinator.disconnectPartner()
                .onSuccess {
                    bootstrapRepository.refreshBootstrap()
                    pairingConfirmationState.value = PairingConfirmationUiState()
                    pairingSheetMode.value = HomePairingSheetMode.JoinCodeEntry
                    joinCodeState.value = JoinCodeUiState(code = pendingCode)
                    inviteErrorState.value = null
                }
                .onFailure { error ->
                    pairingConfirmationState.value = PairingConfirmationUiState(
                        isDisconnecting = false,
                        errorMessage = error.message ?: "Unable to disconnect partner"
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
            if (uiState.value.bootstrapState.requiresPartnerDetailsForInvite()) {
                openPartnerDetailsForm()
                return@launch
            }
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

    private fun openPartnerDetailsForm() {
        val bootstrap = uiState.value.bootstrapState
        partnerDetailsFormState.value = PartnerDetailsFormUiState(
            partnerDisplayName = bootstrap.partnerDisplayName.orEmpty(),
            anniversaryDate = bootstrap.anniversaryDate.toMaskedAnniversaryDate(),
            nextUpdateAt = bootstrap.partnerProfileNextUpdateAt
        )
        inviteErrorState.value = null
        pairingSheetMode.value = HomePairingSheetMode.PartnerDetails
    }

    private suspend fun createInviteForSheet() {
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
                if (uiState.value.bootstrapState.requiresPartnerDetailsForInvite()) {
                    openPartnerDetailsForm()
                }
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
            canvasRuntime.submitAndAwait(CanvasRuntimeEvent.CanvasViewportChanged(widthPx, heightPx))
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
                backgroundImageRepository.importBackground(uri)
            }
        }
    }

    fun onBackgroundImageCleared() {
        viewModelScope.launch {
            runWallpaperUpdate {
                backgroundImageRepository.clearBackground()
            }
        }
    }

    fun onWallpaperPresetSelected(drawableResId: Int) {
        viewModelScope.launch {
            runWallpaperUpdate {
                backgroundImageRepository.importBundledBackground(drawableResId)
            }
        }
    }

    fun onRemoteWallpaperSelected(wallpaper: RemoteWallpaper) {
        viewModelScope.launch {
            applyingRemoteWallpaperIdState.value = wallpaper.id
            runWallpaperUpdate {
                backgroundImageRepository.importRemoteBackground(wallpaper)
            }
            applyingRemoteWallpaperIdState.value = null
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
        val partnerDetailsForm: PartnerDetailsFormUiState = PartnerDetailsFormUiState(),
        val confirmation: PairingConfirmationUiState = PairingConfirmationUiState()
    )

    private data class WallpaperControlState(
        val recentRemoteWallpapers: List<RemoteWallpaper>,
        val applyingRemoteWallpaperId: String?,
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
    "Join me on Mulberry: https://mulberry.my/invite?code=$code\n\n" +
        "If the link doesn’t work, enter invite code $code in Mulberry. This code expires soon."

private fun SessionBootstrapState.requiresPartnerDetailsForInvite(): Boolean =
    partnerDisplayName.isNullOrBlank() || anniversaryDate.isNullOrBlank()

private fun friendlyInviteError(error: Throwable): String? = when (error.message) {
    "You cannot redeem your own invite" -> "That’s your invite code. Share it with your partner."
    "Invite code has expired" -> "That invite code has expired. Ask your partner to generate a new one."
    "Invite code is no longer valid" -> "That invite code is no longer valid. Ask your partner to generate a new one."
    "Invite code not found" -> "That invite code wasn’t found. Double-check the code and try again."
    "Already paired" -> "You’re already paired. Disconnect first to join a new invite."
    else -> error.message
}

private const val ANNIVERSARY_DATE_PLACEHOLDER = "DD-MM-YYYY"

private fun String?.toMaskedAnniversaryDate(): String {
    val raw = this.orEmpty()
    val digits = if (raw.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) {
        raw.substring(8, 10) + raw.substring(5, 7) + raw.substring(0, 4)
    } else {
        raw.filter(Char::isDigit).take(8)
    }
    val slots = ANNIVERSARY_DATE_PLACEHOLDER.toCharArray()
    var digitIndex = 0
    for (index in slots.indices) {
        if (slots[index] == 'Y' || slots[index] == 'M' || slots[index] == 'D') {
            if (digitIndex < digits.length) {
                slots[index] = digits[digitIndex]
                digitIndex += 1
            }
        }
    }
    return slots.concatToString()
}

private fun String.isCompleteAnniversaryDate(): Boolean =
    matches(Regex("""\d{2}-\d{2}-\d{4}"""))

private fun String.toBackendAnniversaryDate(): String =
    "${substring(6, 10)}-${substring(3, 5)}-${substring(0, 2)}"

private val inviteDateFormats = listOf(
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US),
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
).onEach { it.timeZone = TimeZone.getTimeZone("UTC") }

private const val INBOUND_INVITE_TTL_MS = 24 * 60 * 60 * 1000L

private fun String.parseInviteInstantMillis(): Long? =
    inviteDateFormats.firstNotNullOfOrNull { format ->
        runCatching { format.parse(this)?.time }.getOrNull()
    }
