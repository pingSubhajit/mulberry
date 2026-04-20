package com.subhajit.elaris.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subhajit.elaris.core.ui.TestTags
import com.subhajit.elaris.drawing.model.DrawingTool

@Composable
fun CanvasHomeRoute(
    onNavigateToSettings: () -> Unit,
    viewModel: CanvasHomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    CanvasHomeScreen(
        uiState = uiState,
        onNavigateToSettings = onNavigateToSettings,
        onTabSelected = viewModel::onTabSelected,
        onCanvasPress = viewModel::onCanvasPress,
        onCanvasDrag = viewModel::onCanvasDrag,
        onCanvasRelease = viewModel::onCanvasRelease,
        onCanvasTap = viewModel::onCanvasTap,
        onColorSelected = viewModel::onColorSelected,
        onBrushWidthChanged = viewModel::onBrushWidthChanged,
        onEraserToggle = viewModel::onEraserToggle,
        onClearRequested = viewModel::onClearRequested,
        onClearDismissed = viewModel::onClearDismissed,
        onClearConfirmed = viewModel::onClearConfirmed,
        onWallpaperConfiguredChanged = viewModel::onWallpaperConfiguredChanged
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CanvasHomeScreen(
    uiState: CanvasHomeUiState,
    onNavigateToSettings: () -> Unit,
    onTabSelected: (CanvasHomeTab) -> Unit,
    onCanvasPress: (com.subhajit.elaris.drawing.model.StrokePoint) -> Unit,
    onCanvasDrag: (com.subhajit.elaris.drawing.model.StrokePoint) -> Unit,
    onCanvasRelease: () -> Unit,
    onCanvasTap: (com.subhajit.elaris.drawing.model.StrokePoint) -> Unit,
    onColorSelected: (Long) -> Unit,
    onBrushWidthChanged: (Float) -> Unit,
    onEraserToggle: () -> Unit,
    onClearRequested: () -> Unit,
    onClearDismissed: () -> Unit,
    onClearConfirmed: () -> Unit,
    onWallpaperConfiguredChanged: (Boolean) -> Unit
) {
    if (uiState.showClearConfirmation) {
        AlertDialog(
            onDismissRequest = onClearDismissed,
            title = { Text("Clear canvas?") },
            text = { Text("This removes all local strokes from the canvas.") },
            confirmButton = {
                TextButton(
                    onClick = onClearConfirmed,
                    modifier = Modifier.testTag(TestTags.CLEAR_CONFIRM_BUTTON)
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = onClearDismissed) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.testTag(TestTags.HOME_SCREEN),
        topBar = {
            TopAppBar(
                title = { Text("Shared Canvas") },
                actions = {
                    TextButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag(TestTags.HOME_SETTINGS_BUTTON)
                    ) {
                        Text("Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FloatingActionButton(
                    onClick = onEraserToggle,
                    modifier = Modifier.testTag(TestTags.ERASER_BUTTON),
                    containerColor = if (uiState.toolState.activeTool == DrawingTool.ERASE) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                ) {
                    Text("Erase")
                }
                FloatingActionButton(
                    onClick = onClearRequested,
                    modifier = Modifier.testTag(TestTags.CLEAR_BUTTON)
                ) {
                    Text("Clear")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = uiState.selectedTab.ordinal) {
                Tab(
                    selected = uiState.selectedTab == CanvasHomeTab.CANVAS,
                    onClick = { onTabSelected(CanvasHomeTab.CANVAS) },
                    modifier = Modifier.testTag(TestTags.HOME_CANVAS_TAB),
                    text = { Text("Canvas") }
                )
                Tab(
                    selected = uiState.selectedTab == CanvasHomeTab.LOCK_SCREEN,
                    onClick = { onTabSelected(CanvasHomeTab.LOCK_SCREEN) },
                    modifier = Modifier.testTag(TestTags.HOME_LOCKSCREEN_TAB),
                    text = { Text("Lock screen") }
                )
            }

            when (uiState.selectedTab) {
                CanvasHomeTab.CANVAS -> CanvasTabContent(
                    uiState = uiState,
                    onCanvasPress = onCanvasPress,
                    onCanvasDrag = onCanvasDrag,
                    onCanvasRelease = onCanvasRelease,
                    onCanvasTap = onCanvasTap,
                    onColorSelected = onColorSelected,
                    onBrushWidthChanged = onBrushWidthChanged
                )

                CanvasHomeTab.LOCK_SCREEN -> LockScreenPlaceholderContent(
                    uiState = uiState,
                    onWallpaperConfiguredChanged = onWallpaperConfiguredChanged
                )
            }
        }
    }
}

@Composable
private fun CanvasTabContent(
    uiState: CanvasHomeUiState,
    onCanvasPress: (com.subhajit.elaris.drawing.model.StrokePoint) -> Unit,
    onCanvasDrag: (com.subhajit.elaris.drawing.model.StrokePoint) -> Unit,
    onCanvasRelease: () -> Unit,
    onCanvasTap: (com.subhajit.elaris.drawing.model.StrokePoint) -> Unit,
    onColorSelected: (Long) -> Unit,
    onBrushWidthChanged: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "What will you send?",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (uiState.toolState.activeTool == DrawingTool.ERASE) {
                    "Tap a stroke to delete it."
                } else {
                    "Draw freely on the local canvas. Sync and wallpaper rendering come later."
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(28.dp)
                )
        ) {
            DrawingCanvas(
                canvasState = uiState.canvasState,
                activeTool = uiState.toolState.activeTool,
                onDrawStart = onCanvasPress,
                onDrawPoint = onCanvasDrag,
                onDrawEnd = onCanvasRelease,
                onEraseTap = onCanvasTap,
                modifier = Modifier.padding(8.dp)
            )

            if (uiState.canvasState.strokes.isEmpty() && uiState.canvasState.activeStroke == null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag(TestTags.DRAWING_BLANK_STATE),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Draw them something cute",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Your drawing will later appear on their lock screen.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Brush size", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = uiState.toolState.selectedWidth,
                        onValueChange = onBrushWidthChanged,
                        valueRange = com.subhajit.elaris.drawing.model.DrawingDefaults.MIN_WIDTH..
                            com.subhajit.elaris.drawing.model.DrawingDefaults.MAX_WIDTH,
                        modifier = Modifier.testTag(TestTags.BRUSH_WIDTH_SLIDER)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    uiState.palette.forEach { color ->
                        ColorSwatch(
                            colorArgb = color,
                            isSelected = color == uiState.toolState.selectedColorArgb &&
                                uiState.toolState.activeTool == DrawingTool.DRAW,
                            onClick = { onColorSelected(color) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LockScreenPlaceholderContent(
    uiState: CanvasHomeUiState,
    onWallpaperConfiguredChanged: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag(TestTags.LOCKSCREEN_PLACEHOLDER),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Lock screen preview is coming later.",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = if (uiState.canvasState.isEmpty) {
                "Your local canvas is empty. Once drawing exists, this screen will reflect that state and eventually feed the live wallpaper workstream."
            } else {
                "You currently have ${uiState.canvasState.strokes.size} committed stroke(s) ready for future wallpaper rendering."
            },
            style = MaterialTheme.typography.bodyLarge
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Wallpaper setup placeholder", fontWeight = FontWeight.SemiBold)
                Text("Environment: ${uiState.environmentLabel}")
                Text("Snapshot dirty: ${uiState.canvasState.snapshotState.isDirty}")
                Text("Wallpaper marked configured: ${uiState.bootstrapState.hasWallpaperConfigured}")
                TextButton(
                    onClick = {
                        onWallpaperConfiguredChanged(!uiState.bootstrapState.hasWallpaperConfigured)
                    },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        if (uiState.bootstrapState.hasWallpaperConfigured) {
                            "Mark as not configured"
                        } else {
                            "Mark as configured"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorSwatch(
    colorArgb: Long,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val outlineColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = outlineColor,
                shape = CircleShape
            ),
        color = Color(colorArgb),
        shape = CircleShape
    ) {}
}
