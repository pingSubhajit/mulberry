package com.subhajit.mulberry.wallpaper.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.subhajit.mulberry.R
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.mulberryAppColors
import com.subhajit.mulberry.wallpaper.RemoteWallpaper
import com.subhajit.mulberry.wallpaper.WallpaperPreset
import kotlinx.coroutines.launch

sealed interface WallpaperPreviewItem {
    val title: String
    val description: String?
    val previewImage: WallpaperPreviewImage

    data class Remote(val wallpaper: RemoteWallpaper) : WallpaperPreviewItem {
        override val title: String = wallpaper.title
        override val description: String = wallpaper.description
        override val previewImage: WallpaperPreviewImage = WallpaperPreviewImage.Url(wallpaper.previewUrl)
    }

    data class Preset(val preset: WallpaperPreset) : WallpaperPreviewItem {
        override val title: String = preset.label
        override val description: String? = null
        override val previewImage: WallpaperPreviewImage =
            WallpaperPreviewImage.Drawable(preset.previewDrawableResId)
    }
}

sealed interface WallpaperPreviewImage {
    data class Url(val url: String) : WallpaperPreviewImage
    data class Drawable(@DrawableRes val drawableResId: Int) : WallpaperPreviewImage
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperPreviewBottomSheet(
    item: WallpaperPreviewItem,
    onDismiss: () -> Unit,
    onSetAsWallpaper: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(sheetState) {
        sheetState.expand()
    }

    ModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                sheetState.hide()
                onDismiss()
            }
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { WallpaperPreviewDragHandle() },
        modifier = modifier
    ) {
        WallpaperPreviewSheetContent(
            item = item,
            onSetAsWallpaper = {
                coroutineScope.launch {
                    onSetAsWallpaper()
                    sheetState.hide()
                    onDismiss()
                }
            }
        )
    }
}

@Composable
private fun WallpaperPreviewDragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(top = 8.dp, bottom = 19.dp)
            .size(width = 44.dp, height = 4.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(MaterialTheme.mulberryAppColors.dragHandle)
    )
}

@Composable
private fun WallpaperPreviewSheetContent(
    item: WallpaperPreviewItem,
    onSetAsWallpaper: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = item.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = TextStyle(
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    lineHeight = 28.sp
                )
            )
            item.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    color = MaterialTheme.mulberryAppColors.mutedText,
                    style = TextStyle(
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                )
            }
        }

        WallpaperPreviewImage(
            image = item.previewImage,
            contentDescription = item.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
        )

        WallpaperPrimaryButton(
            text = stringResource(R.string.wallpaper_preview_set_action),
            onClick = onSetAsWallpaper,
            enabled = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun WallpaperPreviewImage(
    image: WallpaperPreviewImage,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    when (image) {
        is WallpaperPreviewImage.Drawable -> Image(
            painter = painterResource(image.drawableResId),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )

        is WallpaperPreviewImage.Url -> AsyncImage(
            model = image.url,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    }
}
