package com.subhajit.mulberry.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.subhajit.mulberry.BuildConfig
import com.subhajit.mulberry.auth.AuthRepository
import com.subhajit.mulberry.core.config.AppConfig
import com.subhajit.mulberry.core.flags.FeatureFlag
import com.subhajit.mulberry.core.flags.FeatureFlagProvider
import com.subhajit.mulberry.core.flags.FeatureFlags
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapState
import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.network.DisplayNameRequest
import com.subhajit.mulberry.network.MulberryApiService
import com.subhajit.mulberry.network.PartnerProfileRequest
import com.subhajit.mulberry.network.toDomainBootstrap
import com.subhajit.mulberry.sync.BackgroundCanvasSyncCoordinator
import com.subhajit.mulberry.sync.CanvasSyncRepository
import com.subhajit.mulberry.sync.FcmTokenRepository
import com.subhajit.mulberry.sync.SyncMetadata
import com.subhajit.mulberry.sync.SyncMetadataRepository
import com.subhajit.mulberry.sync.SyncState
import com.subhajit.mulberry.wallpaper.BackgroundImageRepository
import com.subhajit.mulberry.wallpaper.CanvasSnapshotRenderer
import com.subhajit.mulberry.wallpaper.WallpaperCoordinator
import com.subhajit.mulberry.wallpaper.WallpaperSyncSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

data class SettingsUiState(
    val environmentLabel: String = "",
    val apiBaseUrl: String = "",
    val appVersionName: String = "",
    val appVersionCode: Int = 0,
    val buildType: String = "",
    val flavor: String = "",
    val enableDebugMenu: Boolean = false,
    val developerOptionsEnabled: Boolean = false,
    val featureFlags: FeatureFlags = FeatureFlags(),
    val bootstrapState: SessionBootstrapState = SessionBootstrapState(),
    val syncState: SyncState = SyncState.Disconnected,
    val syncMetadata: SyncMetadata = SyncMetadata(),
    val fcmRegistered: Boolean = false,
    val wallpaperSyncEnabled: Boolean = true,
    val isBusy: Boolean = false
) {
    val pendingOperationCount: Int
        get() = syncMetadata.pendingOperations.size
}

sealed interface SettingsEffect {
    data object RestartFromBootstrap : SettingsEffect
    data object NavigateHome : SettingsEffect
    data class Message(val text: String) : SettingsEffect
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    repository: SessionBootstrapRepository,
    featureFlagProvider: FeatureFlagProvider,
    private val developerOptionsRepository: DeveloperOptionsRepository,
    private val wallpaperSyncSettingsRepository: WallpaperSyncSettingsRepository,
    private val authRepository: AuthRepository,
    private val drawingRepository: DrawingRepository,
    private val canvasSyncRepository: CanvasSyncRepository,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val backgroundCanvasSyncCoordinator: BackgroundCanvasSyncCoordinator,
    private val fcmTokenRepository: FcmTokenRepository,
    private val backgroundImageRepository: BackgroundImageRepository,
    private val canvasSnapshotRenderer: CanvasSnapshotRenderer,
    private val wallpaperCoordinator: WallpaperCoordinator,
    private val pairingDisconnectCoordinator: PairingDisconnectCoordinator,
    private val apiService: MulberryApiService,
    @ApplicationContext private val appContext: Context,
    appConfig: AppConfig
) : ViewModel() {
    private val _effects = MutableSharedFlow<SettingsEffect>()
    val effects = _effects.asSharedFlow()

    private val busyState = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val versionTapCount = kotlinx.coroutines.flow.MutableStateFlow(0)

    private val baseUiState = combine(
        repository.state,
        featureFlagProvider.flags,
        developerOptionsRepository.enabled,
        syncMetadataRepository.metadata,
        fcmTokenRepository.isRegistered
    ) { state, flags, developerOptionsEnabled, metadata, fcmRegistered ->
        SettingsUiState(
            environmentLabel = appConfig.environment.displayName,
            apiBaseUrl = appConfig.apiBaseUrl,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            buildType = BuildConfig.BUILD_TYPE,
            flavor = BuildConfig.FLAVOR,
            enableDebugMenu = appConfig.enableDebugMenu,
            developerOptionsEnabled = developerOptionsEnabled,
            featureFlags = flags,
            bootstrapState = state,
            syncMetadata = metadata,
            fcmRegistered = fcmRegistered
        )
    }

    private val baseUiStateWithWallpaperSync = combine(
        baseUiState,
        wallpaperSyncSettingsRepository.enabled
    ) { base, wallpaperSyncEnabled ->
        base.copy(wallpaperSyncEnabled = wallpaperSyncEnabled)
    }

    val uiState = combine(
        baseUiStateWithWallpaperSync,
        canvasSyncRepository.syncState,
        busyState
    ) { base, syncState, isBusy ->
        base.copy(syncState = syncState, isBusy = isBusy)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(
            environmentLabel = appConfig.environment.displayName,
            apiBaseUrl = appConfig.apiBaseUrl,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            buildType = BuildConfig.BUILD_TYPE,
            flavor = BuildConfig.FLAVOR,
            enableDebugMenu = appConfig.enableDebugMenu,
            featureFlags = appConfig.defaultFeatureFlags
        )
    )

    private val sessionRepository = repository
    private val flagsRepository = featureFlagProvider

    fun onVersionTapped() {
        if (uiState.value.developerOptionsEnabled) return
        val nextCount = versionTapCount.value + 1
        versionTapCount.value = nextCount
        if (nextCount >= DEVELOPER_UNLOCK_TAPS) {
            viewModelScope.launch {
                developerOptionsRepository.setEnabled(true)
                versionTapCount.value = 0
                _effects.emit(SettingsEffect.Message("Developer options enabled"))
            }
        }
    }

    fun onDeveloperOptionsEnabledChanged(enabled: Boolean) {
        viewModelScope.launch {
            developerOptionsRepository.setEnabled(enabled)
            if (!enabled) {
                _effects.emit(SettingsEffect.Message("Developer options disabled"))
            }
        }
    }

    fun onWallpaperSyncEnabledChanged(enabled: Boolean) {
        viewModelScope.launchWithBusy {
            wallpaperSyncSettingsRepository.setEnabled(enabled)
            if (enabled) {
                wallpaperCoordinator.ensureSnapshotCurrent()
            }
            wallpaperCoordinator.notifyWallpaperUpdatedIfSelected()
        }
    }

    fun onSendCrashlyticsTestEvent() {
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCrashlyticsCollectionEnabled(true)
        crashlytics.log("Crashlytics test: non-fatal event from developer options")
        crashlytics.recordException(
            RuntimeException("Crashlytics test non-fatal (${BuildConfig.FLAVOR}/${BuildConfig.BUILD_TYPE})")
        )
        viewModelScope.launch {
            _effects.emit(SettingsEffect.Message("Sent Crashlytics test event (non-fatal). Check Firebase console in a few minutes."))
        }
    }

    fun onCrashlyticsTestCrash() {
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCrashlyticsCollectionEnabled(true)
        crashlytics.log("Crashlytics test: fatal crash from developer options")
        throw RuntimeException("Crashlytics test crash")
    }

    fun onResetAppState() {
        viewModelScope.launchWithBusy {
            fcmTokenRepository.unregisterRegisteredToken()
            sessionRepository.reset()
            canvasSyncRepository.reset()
            drawingRepository.resetAllDrawingState()
            canvasSnapshotRenderer.clearSnapshots()
            backgroundImageRepository.clearBackground()
            wallpaperCoordinator.ensureSnapshotCurrent()
            wallpaperCoordinator.notifyWallpaperUpdated()
            _effects.emit(SettingsEffect.RestartFromBootstrap)
        }
    }

    fun onLogout() {
        viewModelScope.launchWithBusy {
            canvasSyncRepository.reset()
            authRepository.logout()
            _effects.emit(SettingsEffect.RestartFromBootstrap)
        }
    }

    fun onDisconnectPartner() {
        viewModelScope.launchWithBusy {
            pairingDisconnectCoordinator.disconnectPartner().getOrThrow()
            _effects.emit(SettingsEffect.RestartFromBootstrap)
        }
    }

    fun onDisplayNameSave(displayName: String) {
        viewModelScope.launchWithBusy {
            val bootstrap = apiService.updateDisplayName(
                DisplayNameRequest(displayName = displayName)
            ).toDomainBootstrap()
            sessionRepository.cacheBootstrap(bootstrap)
            _effects.emit(SettingsEffect.Message("Profile name updated"))
        }
    }

    fun onProfilePhotoSelected(uri: Uri) {
        viewModelScope.launchWithBusy {
            val bootstrap = apiService.updateProfilePhoto(uri.toImagePart()).toDomainBootstrap()
            sessionRepository.cacheBootstrap(bootstrap)
            _effects.emit(SettingsEffect.Message("Profile photo updated"))
        }
    }

    fun onPartnerProfileSave(partnerDisplayName: String, anniversaryDate: String) {
        viewModelScope.launchWithBusy {
            val bootstrap = apiService.updatePartnerProfile(
                PartnerProfileRequest(
                    partnerDisplayName = partnerDisplayName,
                    anniversaryDate = anniversaryDate.toBackendAnniversaryDate()
                )
            ).toDomainBootstrap()
            sessionRepository.cacheBootstrap(bootstrap)
            _effects.emit(SettingsEffect.Message("Partner details updated"))
        }
    }

    fun onPartnerProfilePhotoSelected(uri: Uri) {
        viewModelScope.launchWithBusy {
            val bootstrap = apiService.updatePartnerProfilePhoto(uri.toImagePart()).toDomainBootstrap()
            sessionRepository.cacheBootstrap(bootstrap)
            _effects.emit(SettingsEffect.Message("Partner photo updated"))
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
            _effects.emit(SettingsEffect.Message("Feature flag overrides reset"))
        }
    }

    fun onForceSyncNow() {
        viewModelScope.launchWithBusy {
            backgroundCanvasSyncCoordinator.syncToLatestSnapshot(
                pairSessionId = uiState.value.bootstrapState.pairSessionId,
                latestRevisionHint = null
            ).getOrThrow()
            _effects.emit(SettingsEffect.Message("Sync completed"))
        }
    }

    fun onRegenerateWallpaperSnapshot() {
        viewModelScope.launchWithBusy {
            wallpaperCoordinator.ensureSnapshotCurrent()
            wallpaperCoordinator.notifyWallpaperUpdatedIfSelected()
            _effects.emit(SettingsEffect.Message("Wallpaper snapshot regenerated"))
        }
    }

    fun onSendDebugPairingNotification() {
        viewModelScope.launchWithBusy {
            apiService.sendDebugPairingConfirmationPush()
            _effects.emit(SettingsEffect.Message("Pairing notification sent to partner"))
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.launchWithBusy(block: suspend () -> Unit) {
        launch {
            busyState.value = true
            runCatching { block() }
                .onFailure { error ->
                    _effects.emit(SettingsEffect.Message(error.message ?: "Something went wrong"))
                }
            busyState.value = false
        }
    }

    private fun Uri.toImagePart(): MultipartBody.Part {
        val bytes = appContext.contentResolver.openInputStream(this)?.use { it.readBytes() }
            ?: error("Unable to read selected image")
        val contentType = appContext.contentResolver.getType(this) ?: "image/jpeg"
        val requestBody = bytes.toRequestBody(contentType.toMediaType())
        return MultipartBody.Part.createFormData("image", "profile-photo", requestBody)
    }

    private companion object {
        const val DEVELOPER_UNLOCK_TAPS = 5
    }
}

private fun String.toBackendAnniversaryDate(): String =
    "${substring(6, 10)}-${substring(3, 5)}-${substring(0, 2)}"
