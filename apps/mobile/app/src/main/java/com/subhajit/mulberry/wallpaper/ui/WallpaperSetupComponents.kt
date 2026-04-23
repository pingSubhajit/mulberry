package com.subhajit.mulberry.wallpaper.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.mulberryAppColors
import com.subhajit.mulberry.wallpaper.RemoteWallpaper
import com.subhajit.mulberry.wallpaper.WallpaperPreset
import kotlin.math.max

@Composable
fun WallpaperLockScreenPreview(
    assetPath: String?,
    assetUpdatedAt: Long,
    @DrawableRes fallbackBackgroundRes: Int,
    modifier: Modifier = Modifier,
    width: Dp = 233.dp,
    height: Dp = 428.dp
) {
    val appColors = MaterialTheme.mulberryAppColors
    val imageBitmap = remember(assetPath, assetUpdatedAt) {
        assetPath?.let { path ->
            decodeSampledBitmap(path, targetWidth = 492, targetHeight = 856)?.asImageBitmap()
        }
    }
    val previewShape = RoundedCornerShape(15.38.dp)

    Box(
        modifier = modifier
            .size(width = width, height = height)
            .clip(previewShape)
            .background(appColors.previewFrame)
            .padding(6.dp)
            .clip(previewShape)
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Image(
                painter = painterResource(fallbackBackgroundRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        PreviewClock(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 54.dp)
        )
    }
}

@Composable
fun WallpaperBackgroundSelectionSection(
    remoteWallpapers: List<RemoteWallpaper>,
    presets: List<WallpaperPreset>,
    @DrawableRes selectedPresetResId: Int?,
    selectedRemoteWallpaperId: String?,
    applyingRemoteWallpaperId: String?,
    onUploadFromGallery: () -> Unit,
    onPresetSelected: (Int) -> Unit,
    onRemoteWallpaperSelected: (RemoteWallpaper) -> Unit,
    onViewMoreWallpapers: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val wallpaperItems = buildList<WallpaperSelectionItem> {
        remoteWallpapers.forEach { wallpaper ->
            add(WallpaperSelectionItem.Remote(wallpaper))
        }
        presets.forEach { preset ->
            add(WallpaperSelectionItem.Preset(preset))
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(28.dp),
        modifier = modifier.testTag(TestTags.ONBOARDING_WALLPAPER_BACKGROUND_SECTION)
    ) {
        UploadBackgroundButton(onClick = onUploadFromGallery)
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            wallpaperItems.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    row.forEach { item ->
                        when (item) {
                            is WallpaperSelectionItem.Preset -> PresetCard(
                                preset = item.preset,
                                isSelected = selectedPresetResId == item.preset.drawableResId,
                                onClick = { onPresetSelected(item.preset.drawableResId) },
                                modifier = Modifier.weight(1f)
                            )

                            is WallpaperSelectionItem.Remote -> RemoteWallpaperCard(
                                wallpaper = item.wallpaper,
                                isSelected = selectedRemoteWallpaperId == item.wallpaper.id,
                                isApplying = applyingRemoteWallpaperId == item.wallpaper.id,
                                onClick = { onRemoteWallpaperSelected(item.wallpaper) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (row.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
            onViewMoreWallpapers?.let { onClick ->
                MoreWallpapersButton(onClick = onClick)
            }
        }
    }
}

private sealed interface WallpaperSelectionItem {
    data class Preset(val preset: WallpaperPreset) : WallpaperSelectionItem
    data class Remote(val wallpaper: RemoteWallpaper) : WallpaperSelectionItem
}

@Composable
fun WallpaperPrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(15.38.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MulberryPrimary,
            contentColor = Color.White,
            disabledContainerColor = MulberryPrimary.copy(alpha = 0.36f),
            disabledContentColor = Color.White.copy(alpha = 0.82f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 24.sp
            )
        )
    }
}

@Composable
private fun PreviewClock(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "9:41",
            color = Color.White.copy(alpha = 0.62f),
            style = TextStyle(
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 52.sp,
                lineHeight = 58.sp
            )
        )
        Text(
            text = stringResource(R.string.onboarding_wallpaper_preview_date),
            color = Color.White.copy(alpha = 0.70f),
            style = TextStyle(
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        )
    }
}

@Composable
private fun UploadBackgroundButton(onClick: () -> Unit) {
    val appColors = MaterialTheme.mulberryAppColors
    val radius = 15.38.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(115.dp)
            .clip(RoundedCornerShape(radius))
            .background(appColors.softSurfaceAlt)
            .dashedBorder(MulberryPrimary, radius)
            .clickable(onClick = onClick)
            .testTag(TestTags.ONBOARDING_WALLPAPER_UPLOAD_BUTTON),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GalleryIcon()
            Text(
                text = stringResource(R.string.onboarding_wallpaper_upload),
                color = MulberryPrimary,
                style = TextStyle(
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    lineHeight = 22.sp
                )
            )
        }
    }
}

@Composable
private fun MoreWallpapersButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(15.38.dp))
            .background(MulberryPrimary.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.wallpaper_more_action),
            color = MulberryPrimary,
            style = TextStyle(
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 22.sp
            )
        )
    }
}

@Composable
private fun GalleryIcon() {
    Canvas(modifier = Modifier.size(18.dp)) {
        val strokeWidth = 1.8.dp.toPx()
        drawRoundRect(
            color = MulberryPrimary,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        )
        drawCircle(
            color = MulberryPrimary,
            radius = 2.dp.toPx(),
            center = center.copy(x = size.width * 0.66f, y = size.height * 0.34f),
            style = Stroke(width = strokeWidth)
        )
        drawLine(
            color = MulberryPrimary,
            start = center.copy(x = size.width * 0.20f, y = size.height * 0.72f),
            end = center.copy(x = size.width * 0.42f, y = size.height * 0.50f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = MulberryPrimary,
            start = center.copy(x = size.width * 0.42f, y = size.height * 0.50f),
            end = center.copy(x = size.width * 0.72f, y = size.height * 0.76f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun PresetCard(
    preset: WallpaperPreset,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(15.38.dp)
    Box(
        modifier = modifier
            .aspectRatio(171f / 133f)
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        Image(
            painter = painterResource(preset.thumbnailDrawableResId),
            contentDescription = preset.label,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.46f)),
                contentAlignment = Alignment.Center
            ) {
                CheckmarkIcon()
            }
        }
    }
}

@Composable
private fun RemoteWallpaperCard(
    wallpaper: RemoteWallpaper,
    isSelected: Boolean,
    isApplying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(15.38.dp)
    Box(
        modifier = modifier
            .aspectRatio(171f / 133f)
            .clip(shape)
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

private fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier =
    drawBehind {
        val strokeWidth = 1.dp.toPx()
        drawRoundRect(
            color = color,
            style = Stroke(
                width = strokeWidth,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))
            ),
            cornerRadius = CornerRadius(radius.toPx(), radius.toPx())
        )
    }

private fun decodeSampledBitmap(
    path: String,
    targetWidth: Int,
    targetHeight: Int
): Bitmap? {
    val boundsOptions = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(path, boundsOptions)

    if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
        return null
    }

    val widthRatio = boundsOptions.outWidth / targetWidth
    val heightRatio = boundsOptions.outHeight / targetHeight
    val sampleSize = max(1, minOf(widthRatio, heightRatio))

    return BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
    )
}
