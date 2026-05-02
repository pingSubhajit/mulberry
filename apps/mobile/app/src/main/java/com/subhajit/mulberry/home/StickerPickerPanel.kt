package com.subhajit.mulberry.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.mulberryAppColors
import com.subhajit.mulberry.stickers.StickerAssetStore
import com.subhajit.mulberry.stickers.StickerAssetVariant
import java.io.File

enum class StickerPickerChrome {
    Filled,
    Floating
}

@Composable
fun StickerPickerPanel(
    uiState: CanvasHomeUiState,
    stickerAssetStore: StickerAssetStore,
    onPackSelected: (String, Int) -> Unit,
    onStickerChosen: (String, Int, String) -> Unit,
    columns: Int = 3,
    stickerTileSize: Dp = 92.dp,
    gridHeight: Dp = 280.dp,
    chrome: StickerPickerChrome = StickerPickerChrome.Filled,
    modifier: Modifier = Modifier
) {
    val packs = uiState.stickerPacks
    val selected = uiState.selectedStickerPack

    val containerModifier = modifier
        .fillMaxWidth()
        .then(
            if (chrome == StickerPickerChrome.Filled) {
                Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.mulberryAppColors.softSurfaceStrong)
                    .padding(12.dp)
            } else {
                Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
            }
        )

    Column(
        modifier = containerModifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = selected?.title ?: "Stickers",
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (uiState.isStickerCatalogLoading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                        color = MulberryPrimary
                    )
                }
            }

            if (!uiState.stickerCatalogErrorMessage.isNullOrBlank()) {
                Text(
                    text = uiState.stickerCatalogErrorMessage ?: "",
                    fontFamily = PoppinsFontFamily,
                    color = MaterialTheme.mulberryAppColors.mutedText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (packs.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(packs, key = { it.packKey + ":" + it.packVersion }) { pack ->
                        val isSelected = selected?.packKey == pack.packKey && selected.packVersion == pack.packVersion
                        val coverFile by produceState<File?>(initialValue = null, key1 = pack.packKey, key2 = pack.packVersion) {
                            value = stickerAssetStore.getOrDownloadStickerAsset(
                                packKey = pack.packKey,
                                packVersion = pack.packVersion,
                                stickerId = StickerAssetStore.COVER_STICKER_ID,
                                variant = StickerAssetVariant.THUMBNAIL,
                                urlHint = pack.coverThumbnailUrl
                            )
                        }
                        StickerPackCoverThumbnail(
                            file = coverFile,
                            contentDescription = pack.title,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (chrome == StickerPickerChrome.Filled) {
                                        Color.White.copy(alpha = 0.08f)
                                    } else {
                                        Color.White.copy(alpha = 0.06f)
                                    },
                                    RoundedCornerShape(14.dp)
                                )
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) MulberryPrimary else Color.White.copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable { onPackSelected(pack.packKey, pack.packVersion) }
                                .padding(6.dp)
                        )
                    }
                }
            }

            val stickers = selected?.stickers.orEmpty()
            if (stickers.isNotEmpty()) {
                val selectedPack = selected
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns.coerceAtLeast(1)),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(gridHeight)
                ) {
                    items(stickers, key = { it.stickerId }) { sticker ->
                        val pack = selectedPack
                        val thumbnailFile by produceState<File?>(initialValue = null, key1 = pack?.packKey, key2 = pack?.packVersion, key3 = sticker.stickerId) {
                            val resolved = pack ?: return@produceState
                            value = stickerAssetStore.getOrDownloadStickerAsset(
                                packKey = resolved.packKey,
                                packVersion = resolved.packVersion,
                                stickerId = sticker.stickerId,
                                variant = StickerAssetVariant.THUMBNAIL,
                                urlHint = sticker.thumbnailUrl
                            )
                        }
                        StickerThumbnailTile(
                            file = thumbnailFile,
                            contentDescription = sticker.stickerId,
                            chrome = chrome,
                            size = stickerTileSize,
                            onClick = {
                                selectedPack?.let { pack ->
                                    onStickerChosen(pack.packKey, pack.packVersion, sticker.stickerId)
                                }
                            }
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(40.dp))
            }
    }
}

@Composable
private fun StickerPackCoverThumbnail(
    file: File?,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    if (file != null && file.exists() && file.length() > 0) {
        AsyncImage(
            model = file,
            contentDescription = contentDescription,
            modifier = modifier
        )
    } else {
        // Placeholder (quietly downloads in the background via StickerAssetStore).
        Box(modifier = modifier)
    }
}

@Composable
private fun StickerThumbnailTile(
    file: File?,
    contentDescription: String,
    chrome: StickerPickerChrome,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val baseModifier = modifier
        .size(size)
        .clip(RoundedCornerShape(14.dp))
        .background(
            if (chrome == StickerPickerChrome.Filled) {
                Color.White.copy(alpha = 0.06f)
            } else {
                Color.White.copy(alpha = 0.04f)
            },
            RoundedCornerShape(14.dp)
        )
        .clickable(onClick = onClick)
        .padding(6.dp)

    if (file != null && file.exists() && file.length() > 0) {
        AsyncImage(
            model = file,
            contentDescription = contentDescription,
            modifier = baseModifier
        )
    } else {
        Box(modifier = baseModifier)
    }
}
