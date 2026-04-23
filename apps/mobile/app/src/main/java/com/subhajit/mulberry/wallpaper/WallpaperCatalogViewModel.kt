package com.subhajit.mulberry.wallpaper

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WallpaperCatalogUiState(
    val wallpapers: List<RemoteWallpaper> = emptyList(),
    val isInitialLoading: Boolean = true,
    val isPageLoading: Boolean = false,
    val selectedWallpaperId: String? = null,
    val nextCursor: String? = null,
    val errorMessage: String? = null
) {
    val canLoadMore: Boolean
        get() = nextCursor != null && !isPageLoading && !isInitialLoading
}

sealed interface WallpaperCatalogEffect {
    data object WallpaperSelected : WallpaperCatalogEffect
}

@HiltViewModel
class WallpaperCatalogViewModel @Inject constructor(
    private val catalogRepository: WallpaperCatalogRepository,
    private val backgroundImageRepository: BackgroundImageRepository,
    private val wallpaperCoordinator: WallpaperCoordinator
) : ViewModel() {
    private val _uiState = MutableStateFlow(WallpaperCatalogUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<WallpaperCatalogEffect>()
    val effects = _effects.asSharedFlow()

    init {
        loadFirstPage()
    }

    fun loadFirstPage() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isInitialLoading = true,
                    errorMessage = null,
                    nextCursor = null
                )
            }
            runCatching {
                catalogRepository.fetchPage(cursor = null)
            }.onSuccess { page ->
                _uiState.update {
                    it.copy(
                        wallpapers = page.items,
                        nextCursor = page.nextCursor,
                        isInitialLoading = false
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isInitialLoading = false,
                        errorMessage = error.message ?: "Unable to load wallpapers"
                    )
                }
            }
        }
    }

    fun loadNextPage() {
        val current = _uiState.value
        val cursor = current.nextCursor ?: return
        if (current.isInitialLoading || current.isPageLoading) return

        viewModelScope.launch {
            _uiState.update { it.copy(isPageLoading = true, errorMessage = null) }
            runCatching {
                catalogRepository.fetchPage(cursor = cursor)
            }.onSuccess { page ->
                _uiState.update { state ->
                    val existingIds = state.wallpapers.mapTo(mutableSetOf()) { it.id }
                    val appended = page.items.filterNot { it.id in existingIds }
                    state.copy(
                        wallpapers = state.wallpapers + appended,
                        nextCursor = page.nextCursor,
                        isPageLoading = false
                    )
                }
            }.onFailure { error ->
                _uiState.update {
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
            _uiState.update {
                it.copy(
                    selectedWallpaperId = wallpaper.id,
                    errorMessage = null
                )
            }
            runCatching {
                backgroundImageRepository.importRemoteBackground(wallpaper)
                wallpaperCoordinator.ensureSnapshotCurrent()
                wallpaperCoordinator.notifyWallpaperUpdatedIfSelected()
            }.onSuccess {
                _effects.emit(WallpaperCatalogEffect.WallpaperSelected)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        selectedWallpaperId = null,
                        errorMessage = error.message ?: "Unable to update wallpaper"
                    )
                }
            }
        }
    }
}
