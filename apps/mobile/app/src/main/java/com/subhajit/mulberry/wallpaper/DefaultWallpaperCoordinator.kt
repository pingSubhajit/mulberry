package com.subhajit.mulberry.wallpaper

import android.app.WallpaperManager
import android.content.Context
import com.subhajit.mulberry.app.di.ApplicationScope
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.drawing.data.local.CanvasMetadataDao
import com.subhajit.mulberry.drawing.data.local.CanvasMetadataEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class DefaultWallpaperCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val canvasMetadataDao: CanvasMetadataDao,
    private val snapshotRenderer: CanvasSnapshotRenderer,
    private val backgroundImageRepository: BackgroundImageRepository,
    private val wallpaperStatusCalculator: WallpaperStatusCalculator,
    private val sessionBootstrapRepository: SessionBootstrapRepository,
    @ApplicationScope private val applicationScope: CoroutineScope
) : WallpaperCoordinator {

    private val selectedState = MutableStateFlow(false)
    private val renderMutex = Mutex()

    init {
        applicationScope.launch {
            refreshWallpaperSelection()
        }

        applicationScope.launch {
            canvasMetadataDao.observeMetadata()
                .distinctUntilChangedByRelevantFields()
                .collect { metadata ->
                    if (metadata.isSnapshotDirty || !metadata.hasSnapshotFile()) {
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

    override fun wallpaperStatus(): Flow<WallpaperStatusState> = combine(
        selectedState,
        backgroundImageRepository.backgroundState,
        canvasMetadataDao.observeMetadata().mapDefault()
    ) { isSelected, backgroundState, metadata ->
        wallpaperStatusCalculator.calculate(
            isWallpaperSelected = isSelected,
            backgroundState = backgroundState,
            metadata = metadata,
            hasSnapshotFile = metadata.hasSnapshotFile(),
            hasBackgroundAsset = backgroundState.assetPath?.let { File(it).exists() } == true
        )
    }.distinctUntilChanged()

    private suspend fun refreshWallpaperSelection(): Boolean {
        val isSelected = WallpaperManager.getInstance(context)
            .wallpaperInfo
            ?.serviceName == MulberryWallpaperService::class.java.name
        selectedState.value = isSelected
        sessionBootstrapRepository.setWallpaperConfigured(isSelected)
        return isSelected
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
}
