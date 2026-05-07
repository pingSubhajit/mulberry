package com.subhajit.mulberry.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewBottomSheet(
    entry: WhatsNewEntry,
    apiBaseUrl: String,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val primaryTextColor = if (isDark) {
        MaterialTheme.colorScheme.onSurface
    } else {
        Color.Black
    }
    val secondaryTextColor = if (isDark) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    } else {
        Color.Black.copy(alpha = 0.6f)
    }
    val sheetHandleColor = if (isDark) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    } else {
        Color(0xFFDEDEDE)
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 19.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(sheetHandleColor)
                )
            }
        }
    ) {
        val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp * WHATS_NEW_SHEET_MAX_HEIGHT_FRACTION
        val maxContentHeight = (maxSheetHeight - WHATS_NEW_DRAG_HANDLE_TOTAL_HEIGHT).coerceAtLeast(0.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxContentHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.5.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val subtitle = remember(entry.versionName, entry.releasedAt) {
                val date = entry.releasedAt?.let {
                    it.format(DateTimeFormatter.ofPattern("d MMMM, yyyy", Locale.US))
                }
                val version = entry.versionName
                when {
                    date != null && !version.isNullOrBlank() -> "Released on $date \u2022 Version $version"
                    date != null -> "Released on $date"
                    !version.isNullOrBlank() -> "Version $version"
                    else -> ""
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(
                    text = "What’s new",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 24.sp,
                        lineHeight = 36.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = primaryTextColor
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 10.sp,
                            lineHeight = 15.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = secondaryTextColor
                    )
                }
            }

            val heroUrl = remember(entry.heroImagePathOrUrl, apiBaseUrl) {
                resolveHeroUrl(apiBaseUrl = apiBaseUrl, heroPathOrUrl = entry.heroImagePathOrUrl)
            }
            if (!heroUrl.isNullOrBlank()) {
                SubcomposeAsyncImage(
                    model = heroUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(15.38.dp))
                ) {
                    val aspect = painter.intrinsicSize.safeAspectRatio() ?: DEFAULT_HERO_ASPECT_RATIO
                    SubcomposeAsyncImageContent(
                        modifier = Modifier.fillMaxWidth().aspectRatio(aspect)
                    )
                }
            }

            if (entry.markdownBody.isNotBlank()) {
                WhatsNewMarkdown(markdown = entry.markdownBody)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(15.38.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB31329),
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = entry.ctaLabel,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 16.sp,
                            lineHeight = 23.857.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }
        }
    }
}

private fun resolveHeroUrl(apiBaseUrl: String, heroPathOrUrl: String?): String? {
    val raw = heroPathOrUrl?.trim().orEmpty()
    if (raw.isBlank()) return null
    if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
    val base = apiBaseUrl.trimEnd('/')
    return if (raw.startsWith("/")) {
        base + raw
    } else {
        "$base/$raw"
    }
}

private fun Size.safeAspectRatio(): Float? {
    if (this == Size.Unspecified) return null
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null
    return width / height
}

private const val DEFAULT_HERO_ASPECT_RATIO = 365f / 243f
private const val WHATS_NEW_SHEET_MAX_HEIGHT_FRACTION = 0.85f
private val WHATS_NEW_DRAG_HANDLE_TOTAL_HEIGHT = 31.dp
