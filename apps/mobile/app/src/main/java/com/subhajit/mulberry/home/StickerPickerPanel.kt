package com.subhajit.mulberry.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.mulberryAppColors

@Composable
fun StickerPickerPanel(
    uiState: CanvasHomeUiState,
    onPackSelected: (String, Int) -> Unit,
    onStickerChosen: (String, Int, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val packs = uiState.stickerPacks
    val selected = uiState.selectedStickerPack

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.mulberryAppColors.softSurfaceStrong,
        shadowElevation = 10.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
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
                        AsyncImage(
                            model = pack.coverThumbnailUrl,
                            contentDescription = pack.title,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = 0.08f))
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
                    columns = GridCells.Fixed(5),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(180.dp)
                ) {
                    items(stickers, key = { it.stickerId }) { sticker ->
                        AsyncImage(
                            model = sticker.thumbnailUrl,
                            contentDescription = sticker.stickerId,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .clickable {
                                    selectedPack?.let { pack ->
                                        onStickerChosen(pack.packKey, pack.packVersion, sticker.stickerId)
                                    }
                                }
                                .padding(6.dp)
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}
