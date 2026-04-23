package com.subhajit.mulberry.wallpaper

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

data class WallpaperCatalogUiState(
    val wallpapers: List<RemoteWallpaper> = emptyList(),
    val isInitialLoading: Boolean = true,
    val isPageLoading: Boolean = false,
    val selectedWallpaperId: String? = null,
    val applyingWallpaperId: String? = null,
    val nextCursor: String? = null,
    val errorMessage: String? = null
) {
    val canLoadMore: Boolean
        get() = nextCursor != null && !isPageLoading && !isInitialLoading
}

@HiltViewModel
class WallpaperCatalogViewModel @Inject constructor(
    private val catalogRepository: WallpaperCatalogRepository,
    private val backgroundImageRepository: BackgroundImageRepository,
    private val wallpaperCoordinator: WallpaperCoordinator
) : ViewModel() {
    private val catalogState = MutableStateFlow(WallpaperCatalogUiState())
    private val selectedWallpaperIdState = MutableStateFlow<String?>(null)
    val uiState = combine(
        catalogState,
        selectedWallpaperIdState
    ) { catalog, selectedWallpaperId ->
        catalog.copy(selectedWallpaperId = selectedWallpaperId)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = WallpaperCatalogUiState()
    )

    init {
        viewModelScope.launch {
            backgroundImageRepository.backgroundState.collect { backgroundState ->
                selectedWallpaperIdState.value = backgroundState.selectedRemoteWallpaperId
            }
        }
        loadFirstPage()
    }

    fun loadFirstPage() {
        viewModelScope.launch {
            catalogState.update {
                it.copy(
                    isInitialLoading = true,
                    errorMessage = null,
                    nextCursor = null
                )
            }
            runCatching {
                catalogRepository.fetchPage(cursor = null)
            }.onSuccess { page ->
                catalogState.update {
                    it.copy(
                        wallpapers = page.items,
                        nextCursor = page.nextCursor,
                        isInitialLoading = false
                    )
                }
            }.onFailure { error ->
                catalogState.update {
                    it.copy(
                        isInitialLoading = false,
                        errorMessage = error.message ?: "Unable to load wallpapers"
                    )
                }
            }
        }
    }

    fun loadNextPage() {
        val current = catalogState.value
        val cursor = current.nextCursor ?: return
        if (current.isInitialLoading || current.isPageLoading) return

        viewModelScope.launch {
            catalogState.update { it.copy(isPageLoading = true, errorMessage = null) }
            runCatching {
                catalogRepository.fetchPage(cursor = cursor)
            }.onSuccess { page ->
                catalogState.update { state ->
                    val existingIds = state.wallpapers.mapTo(mutableSetOf()) { it.id }
                    val appended = page.items.filterNot { it.id in existingIds }
                    state.copy(
                        wallpapers = state.wallpapers + appended,
                        nextCursor = page.nextCursor,
                        isPageLoading = false
                    )
                }
            }.onFailure { error ->
                catalogState.update {
                    it.copy(
                        isPageLoading = false,
                        errorMessage = error.message ?: "Unable to load more wallpapers"
                    )
                }
            }
        }
    }

    fun onWallpaperSelected(wallpaper: RemoteWallpaper) {
        viewModelScope.launch {
            catalogState.update {
                it.copy(
                    applyingWallpaperId = wallpaper.id,
                    errorMessage = null
                )
            }
            yield()
            runCatching {
                backgroundImageRepository.importRemoteBackground(wallpaper)
                wallpaperCoordinator.ensureSnapshotCurrent()
                wallpaperCoordinator.notifyWallpaperUpdatedIfSelected()
            }.onSuccess {
                catalogState.update {
                    it.copy(applyingWallpaperId = null)
                }
            }.onFailure { error ->
                selectedWallpaperIdState.value =
                    backgroundImageRepository.getCurrentBackgroundState().selectedRemoteWallpaperId
                catalogState.update {
                    it.copy(
                        applyingWallpaperId = null,
                        errorMessage = error.message ?: "Unable to update wallpaper"
                    )
                }
            }
        }
    }
}
