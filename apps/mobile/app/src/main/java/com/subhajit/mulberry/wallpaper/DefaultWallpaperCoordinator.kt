package com.subhajit.mulberry.wallpaper

import android.app.WallpaperManager
import android.app.WallpaperInfo
import android.content.ComponentName
import android.content.Context
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.subhajit.mulberry.app.shortcut.WallpaperDoodlesShortcutPublisher
import com.subhajit.mulberry.app.di.ApplicationScope
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.drawing.data.local.CanvasMetadataDao
import com.subhajit.mulberry.drawing.data.local.CanvasMetadataEntity
import com.subhajit.mulberry.drawing.render.CanvasStrokeRenderMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.lang.reflect.Method
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val CANVAS_FONTS_ASSET_PACK = "canvas_fonts"

@Singleton
class DefaultWallpaperCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val canvasMetadataDao: CanvasMetadataDao,
    private val snapshotRenderer: CanvasSnapshotRenderer,
    private val backgroundImageRepository: BackgroundImageRepository,
    private val wallpaperStatusCalculator: WallpaperStatusCalculator,
    private val sessionBootstrapRepository: SessionBootstrapRepository,
    private val wallpaperSyncSettingsRepository: WallpaperSyncSettingsRepository,
    @ApplicationScope private val applicationScope: CoroutineScope
) : WallpaperCoordinator {

    private val selectedOnHomeState = MutableStateFlow(false)
    private val selectedOnLockState = MutableStateFlow(false)
    private val renderMutex = Mutex()
    private var wallpaperSyncEnabled: Boolean = true
    private val wallpaperInfoByWhichMethod: Method? by lazy(LazyThreadSafetyMode.NONE) {
        runCatching {
            WallpaperManager::class.java.getMethod("getWallpaperInfo", Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    init {
        applicationScope.launch {
            refreshWallpaperSelection()
        }

        observeCanvasFontPack()

        applicationScope.launch {
            var lastObservedMode: CanvasStrokeRenderMode? = null
            sessionBootstrapRepository.state
                .map { it.canvasStrokeRenderMode }
                .distinctUntilChanged()
                .collect { mode ->
                    if (lastObservedMode == null) {
                        lastObservedMode = mode
                        return@collect
                    }
                    if (lastObservedMode == mode) return@collect
                    lastObservedMode = mode

                    // Stroke rendering is cached as a snapshot PNG for the wallpaper. If the stroke style changes
                    // (manually or programmatically), re-render the snapshot so the wallpaper reflects the new style.
                    //
                    // Do not delete the snapshot file here; the wallpaper engine may be decoding it concurrently.
                    if (wallpaperSyncSettingsRepository.enabled.first()) {
                        renderMutex.withLock {
                            snapshotRenderer.renderCurrentSnapshot()
                        }
                    }
                    notifyWallpaperUpdatedIfSelected()
                }
        }

        applicationScope.launch {
            wallpaperSyncSettingsRepository.enabled.collect { enabled ->
                wallpaperSyncEnabled = enabled
                WallpaperDoodlesShortcutPublisher.publish(context, enabled)
            }
        }

        applicationScope.launch {
            canvasMetadataDao.observeMetadata()
                .distinctUntilChangedByRelevantFields()
                .collect { metadata ->
                    if (wallpaperSyncEnabled && (metadata.isSnapshotDirty || !metadata.hasSnapshotFile())) {
                        ensureSnapshotCurrent()
                    }
                    notifyWallpaperUpdatedIfSelected()
                }
        }

        applicationScope.launch {
            backgroundImageRepository.backgroundState
                .distinctUntilChanged()
                .collect {
                    notifyWallpaperUpdatedIfSelected()
                }
        }
    }

    override suspend fun ensureSnapshotCurrent() {
        if (!wallpaperSyncSettingsRepository.enabled.first()) return
        renderMutex.withLock {
            val metadata = canvasMetadataDao.getMetadata() ?: CanvasMetadataEntity.default()
            if (!metadata.isSnapshotDirty && metadata.hasSnapshotFile()) return
            snapshotRenderer.renderCurrentSnapshot()
        }
    }

    override suspend fun notifyWallpaperUpdatedIfSelected() {
        val isSelected = refreshWallpaperSelection()
        if (isSelected) {
            WallpaperRenderBus.requestRedraw()
        }
    }

    override suspend fun notifyWallpaperUpdated() {
        refreshWallpaperSelection()
        WallpaperRenderBus.requestRedraw()
    }

    override fun wallpaperStatus(): Flow<WallpaperStatusState> = combine(
        selectedOnHomeState,
        selectedOnLockState,
        backgroundImageRepository.backgroundState,
        canvasMetadataDao.observeMetadata().mapDefault()
    ) { isSelectedOnHome, isSelectedOnLock, backgroundState, metadata ->
        wallpaperStatusCalculator.calculate(
            isWallpaperSelectedOnHome = isSelectedOnHome,
            isWallpaperSelectedOnLock = isSelectedOnLock,
            backgroundState = backgroundState,
            metadata = metadata,
            hasSnapshotFile = metadata.hasSnapshotFile(),
            hasBackgroundAsset = backgroundState.assetPath?.let { File(it).exists() } == true
        )
    }.distinctUntilChanged()

    private suspend fun refreshWallpaperSelection(): Boolean {
        val manager = WallpaperManager.getInstance(context)
        val expectedComponent = ComponentName(context, MulberryWallpaperService::class.java)

        fun isMulberry(info: WallpaperInfo?): Boolean = info?.component == expectedComponent

        val isSelectedOnHome = isMulberry(
            manager.getWallpaperInfoCompat(WallpaperManager.FLAG_SYSTEM) ?: manager.wallpaperInfo
        )
        val lockInfo = manager.getWallpaperInfoCompat(WallpaperManager.FLAG_LOCK)
        val lockId = manager.getWallpaperIdSafely(WallpaperManager.FLAG_LOCK)
        val systemId = manager.getWallpaperIdSafely(WallpaperManager.FLAG_SYSTEM)
        val isSelectedOnLock = isMulberry(lockInfo) || (
            lockInfo == null &&
                isSelectedOnHome &&
                lockId != null &&
                systemId != null &&
                lockId == systemId
            )
        val isSelected = isSelectedOnHome || isSelectedOnLock

        selectedOnHomeState.value = isSelectedOnHome
        selectedOnLockState.value = isSelectedOnLock
        sessionBootstrapRepository.setWallpaperConfigured(isSelected)
        return isSelected
    }

    private fun WallpaperManager.getWallpaperInfoCompat(which: Int): WallpaperInfo? {
        val method = wallpaperInfoByWhichMethod ?: return null
        return runCatching { method.invoke(this, which) as? WallpaperInfo }.getOrNull()
    }

    private fun WallpaperManager.getWallpaperIdSafely(which: Int): Int? {
        val id = runCatching { getWallpaperId(which) }.getOrNull() ?: return null
        return if (id > 0) id else null
    }

    private fun Flow<CanvasMetadataEntity?>.mapDefault(): Flow<CanvasMetadataEntity> = map {
        it ?: CanvasMetadataEntity.default()
    }

    private fun Flow<CanvasMetadataEntity?>.distinctUntilChangedByRelevantFields():
        Flow<CanvasMetadataEntity> = mapDefault().distinctUntilChanged { old, new ->
            old.revision == new.revision &&
                old.isSnapshotDirty == new.isSnapshotDirty &&
                old.cachedImagePath == new.cachedImagePath
        }

    private fun CanvasMetadataEntity.hasSnapshotFile(): Boolean =
        cachedImagePath?.let { File(it).exists() } == true

    private fun observeCanvasFontPack() {
        val assetPackManager = runCatching {
            AssetPackManagerFactory.getInstance(context.applicationContext)
        }.getOrNull() ?: return

        suspend fun rerenderSnapshotForFonts() {
            if (!wallpaperSyncSettingsRepository.enabled.first()) return
            renderMutex.withLock {
                val metadata = canvasMetadataDao.getMetadata() ?: CanvasMetadataEntity.default()
                if (metadata.hasSnapshotFile()) {
                    snapshotRenderer.renderCurrentSnapshot()
                }
            }
            notifyWallpaperUpdatedIfSelected()
        }

        if (assetPackManager.getPackLocation(CANVAS_FONTS_ASSET_PACK) != null) {
            applicationScope.launch { rerenderSnapshotForFonts() }
            return
        }

        val listener = AssetPackStateUpdateListener { state ->
            if (
                state.name() == CANVAS_FONTS_ASSET_PACK &&
                state.status() == AssetPackStatus.COMPLETED
            ) {
                applicationScope.launch { rerenderSnapshotForFonts() }
            }
        }
        assetPackManager.registerListener(listener)
    }
}
