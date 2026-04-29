package com.subhajit.mulberry.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.core.ui.mulberryTapScale
import com.subhajit.mulberry.wallpaper.WallpaperIntentFactory

@Composable
fun LockScreenPlaceholderRoute(
    onNavigateBack: () -> Unit,
    viewModel: CanvasHomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val backgroundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.onBackgroundImageSelected(uri)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.refreshWallpaperStatus()
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshWallpaperStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LockScreenStatusContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onOpenWallpaperPicker = {
            WallpaperIntentFactory.openWallpaperPicker(context)
        },
        onSelectBackground = {
            backgroundPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        onClearBackground = viewModel::onBackgroundImageCleared,
        onRefreshSnapshot = viewModel::onSnapshotRefreshRequested
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LockScreenStatusContent(
    uiState: CanvasHomeUiState,
    onNavigateBack: () -> Unit,
    onOpenWallpaperPicker: () -> Unit,
    onSelectBackground: () -> Unit,
    onClearBackground: () -> Unit,
    onRefreshSnapshot: () -> Unit
) {
    val backgroundAssetPath = uiState.backgroundImageState.assetPath ?: "None"

    Scaffold(
        modifier = Modifier.testTag(TestTags.LOCKSCREEN_SCREEN),
        topBar = {
            TopAppBar(
                title = { Text("Lock screen") },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack, modifier = Modifier.mulberryTapScale()) {
                        Text("Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusCard(
                title = "Wallpaper",
                lines = listOf(
                    "Selected: ${uiState.wallpaperStatus.isWallpaperSelected.yesNo()}",
                    "Home screen: ${uiState.wallpaperStatus.isWallpaperSelectedOnHome.yesNo()}",
                    "Lock screen: ${uiState.wallpaperStatus.isWallpaperSelectedOnLock.yesNo()}",
                    "Snapshot ready: ${uiState.wallpaperStatus.hasSnapshot.yesNo()}",
                    "Snapshot current: ${uiState.wallpaperStatus.isSnapshotCurrent.yesNo()}"
                )
            )

            StatusCard(
                title = "Canvas state",
                lines = listOf(
                    "Current revision: ${uiState.wallpaperStatus.currentCanvasRevision}",
                    "Last snapshot revision: ${uiState.wallpaperStatus.lastSnapshotRevision}",
                    "Local strokes: ${uiState.canvasState.strokes.size}"
                )
            )

            StatusCard(
                title = "Background",
                lines = listOf(
                    "Configured: ${uiState.wallpaperStatus.hasBackgroundImage.yesNo()}",
                    "Asset path: $backgroundAssetPath"
                )
            )

            val isSelectedOnHome = uiState.wallpaperStatus.isWallpaperSelectedOnHome
            val isSelectedOnLock = uiState.wallpaperStatus.isWallpaperSelectedOnLock
            val isSelectedOnBoth = isSelectedOnHome && isSelectedOnLock

            if (!isSelectedOnBoth) {
                Button(
                    onClick = onOpenWallpaperPicker,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.LOCKSCREEN_OPEN_WALLPAPER_BUTTON)
                        .mulberryTapScale()
                ) {
                    val setupLabelResId = if (!isSelectedOnHome && !isSelectedOnLock) {
                        R.string.onboarding_wallpaper_setup_button
                    } else {
                        R.string.wallpaper_setup_set_both_lock_home
                    }
                    Text(stringResource(setupLabelResId))
                }
            }

            OutlinedButton(
                onClick = onSelectBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.LOCKSCREEN_SELECT_BACKGROUND_BUTTON)
                    .mulberryTapScale()
            ) {
                Text(
                    if (uiState.backgroundImageState.isConfigured) {
                        "Change Background Image"
                    } else {
                        "Select Background Image"
                    }
                )
            }

            OutlinedButton(
                onClick = onClearBackground,
                enabled = uiState.backgroundImageState.isConfigured,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.LOCKSCREEN_CLEAR_BACKGROUND_BUTTON)
                    .mulberryTapScale(enabled = uiState.backgroundImageState.isConfigured)
            ) {
                Text("Remove Background Image")
            }

            if (uiState.wallpaperStatus.requiresRecovery) {
                OutlinedButton(
                    onClick = onRefreshSnapshot,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.LOCKSCREEN_REFRESH_SNAPSHOT_BUTTON)
                        .mulberryTapScale()
                ) {
                    Text("Refresh Snapshot")
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    lines: List<String>
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            lines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun Boolean.yesNo(): String = if (this) "Yes" else "No"
