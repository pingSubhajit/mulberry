package com.subhajit.mulberry.wallpaper

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.ui.ApplySystemBarStyle
import com.subhajit.mulberry.core.ui.OnboardingLightSystemBars
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
fun WallpaperCatalogRoute(
    onNavigateBack: () -> Unit,
    viewModel: WallpaperCatalogViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ApplySystemBarStyle(OnboardingLightSystemBars)

    WallpaperCatalogScreen(
        uiState = uiState,
        onBack = onNavigateBack,
        onRetry = viewModel::loadFirstPage,
        onLoadMore = viewModel::loadNextPage,
        onWallpaperSelected = viewModel::onWallpaperSelected
    )
}

@Composable
private fun WallpaperCatalogScreen(
    uiState: WallpaperCatalogUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onWallpaperSelected: (RemoteWallpaper) -> Unit
) {
    val gridState = rememberLazyGridState()

    LaunchedEffect(gridState, uiState.canLoadMore, uiState.wallpapers.size) {
        snapshotFlow {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= uiState.wallpapers.lastIndex - 4
        }
            .distinctUntilChanged()
            .filter { it && uiState.canLoadMore }
            .collect { onLoadMore() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 11.dp, bottom = 26.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CatalogBackButton(onClick = onBack)
            Text(
                text = stringResource(R.string.wallpaper_catalog_title),
                color = Color(0xFF030A14),
                style = TextStyle(
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    lineHeight = 38.sp
                ),
                modifier = Modifier.weight(1f)
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                uiState.isInitialLoading -> CircularProgressIndicator(
                    color = MulberryPrimary,
                    modifier = Modifier.align(Alignment.Center)
                )

                uiState.wallpapers.isEmpty() && uiState.errorMessage == null -> EmptyCatalogState()

                uiState.wallpapers.isEmpty() -> ErrorCatalogState(
                    message = uiState.errorMessage ?: stringResource(R.string.wallpaper_catalog_error),
                    onRetry = onRetry,
                    modifier = Modifier.align(Alignment.Center)
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.wallpapers, key = { it.id }) { wallpaper ->
                        RemoteWallpaperCard(
                            wallpaper = wallpaper,
                            isSelected = uiState.selectedWallpaperId == wallpaper.id,
                            isApplying = uiState.applyingWallpaperId == wallpaper.id,
                            onClick = { onWallpaperSelected(wallpaper) }
                        )
                    }
                    if (uiState.isPageLoading) {
                        item {
                            CircularProgressIndicator(
                                color = MulberryPrimary,
                                modifier = Modifier
                                    .padding(24.dp)
                                    .size(28.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteWallpaperCard(
    wallpaper: RemoteWallpaper,
    isSelected: Boolean,
    isApplying: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(171f / 133f)
            .clip(RoundedCornerShape(15.38.dp))
            .clickable(enabled = !isApplying, onClick = onClick)
    ) {
        AsyncImage(
            model = wallpaper.thumbnailUrl,
            contentDescription = wallpaper.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (isSelected || isApplying) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.46f)),
                contentAlignment = Alignment.Center
            ) {
                if (isApplying) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(30.dp)
                    )
                } else {
                    CheckmarkIcon()
                }
            }
        }
    }
}

@Composable
private fun CatalogBackButton(onClick: () -> Unit) {
    val interactionSource = MutableInteractionSource()
    Box(
        modifier = Modifier
            .padding(end = 8.dp)
            .size(48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val strokeWidth = 2.dp.toPx()
            drawLine(
                color = Color(0xFF46514D),
                start = center.copy(x = size.width * 0.22f),
                end = center.copy(x = size.width * 0.78f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Square
            )
            drawLine(
                color = Color(0xFF46514D),
                start = center.copy(x = size.width * 0.22f),
                end = center.copy(x = size.width * 0.44f, y = size.height * 0.28f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Square
            )
            drawLine(
                color = Color(0xFF46514D),
                start = center.copy(x = size.width * 0.22f),
                end = center.copy(x = size.width * 0.44f, y = size.height * 0.72f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Square
            )
        }
    }
}

@Composable
private fun CheckmarkIcon() {
    Canvas(modifier = Modifier.size(34.dp)) {
        val strokeWidth = 4.dp.toPx()
        drawLine(
            color = Color.White,
            start = Offset(size.width * 0.22f, size.height * 0.54f),
            end = Offset(size.width * 0.43f, size.height * 0.74f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White,
            start = Offset(size.width * 0.43f, size.height * 0.74f),
            end = Offset(size.width * 0.80f, size.height * 0.30f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun EmptyCatalogState() {
    Text(
        text = stringResource(R.string.wallpaper_catalog_empty),
        color = Color(0xFF676A70),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ErrorCatalogState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = message,
            color = Color(0xFF676A70),
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.wallpaper_catalog_retry),
            color = MulberryPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onRetry)
        )
    }
}
