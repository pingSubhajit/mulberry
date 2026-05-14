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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewHistoryBottomSheet(
    entries: List<WhatsNewEntry>,
    apiBaseUrl: String,
    hasMore: Boolean,
    loadingMore: Boolean,
    error: String?,
    onLoadMore: () -> Unit,
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
    val listState = rememberLazyListState()
    val shouldLoadMore by remember(hasMore, loadingMore, listState) {
        derivedStateOf {
            if (!hasMore || loadingMore) return@derivedStateOf false
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisibleIndex >= layoutInfo.totalItemsCount - 3
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }

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
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxContentHeight)
                .padding(horizontal = 18.5.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            item(key = "history-header") {
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
                    Text(
                        text = "All recent updates",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 10.sp,
                            lineHeight = 15.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = secondaryTextColor
                    )
                }
            }

            if (entries.isEmpty()) {
                item(key = "history-empty") {
                    Text(
                        text = "No what’s new entries are configured yet.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = secondaryTextColor
                    )
                }
            }

            itemsIndexed(
                items = entries,
                key = { index, entry -> entry.versionName ?: "entry-$index" }
            ) { index, entry ->
                WhatsNewHistoryEntry(
                    entry = entry,
                    apiBaseUrl = apiBaseUrl,
                    primaryTextColor = primaryTextColor,
                    secondaryTextColor = secondaryTextColor,
                    showDivider = index < entries.lastIndex
                )
            }

            if (loadingMore) {
                item(key = "history-loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color(0xFFB31329),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            if (error != null && !loadingMore) {
                item(key = "history-error") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = secondaryTextColor
                        )
                        TextButton(onClick = onLoadMore) {
                            Text(text = "Try again")
                        }
                    }
                }
            }

            item(key = "history-bottom-space") {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun WhatsNewHistoryEntry(
    entry: WhatsNewEntry,
    apiBaseUrl: String,
    primaryTextColor: Color,
    secondaryTextColor: Color,
    showDivider: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(
                text = entry.title ?: "What’s new",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = primaryTextColor
            )
            val subtitle = rememberWhatsNewSubtitle(entry)
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

        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(secondaryTextColor.copy(alpha = 0.16f))
            )
        }
    }
}

@Composable
private fun rememberWhatsNewSubtitle(entry: WhatsNewEntry): String {
    return remember(entry.versionName, entry.releasedAt) {
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
