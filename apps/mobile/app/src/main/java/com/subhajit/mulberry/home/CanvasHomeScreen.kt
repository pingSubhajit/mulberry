package com.subhajit.mulberry.home

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.drawing.model.DrawingDefaults
import com.subhajit.mulberry.drawing.model.DrawingTool
import com.subhajit.mulberry.drawing.model.StrokePoint

@Composable
fun CanvasHomeRoute(
    onNavigateToCanvas: () -> Unit,
    onNavigateToLockScreen: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: CanvasHomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(viewModel) {
        viewModel.refreshBootstrapState()
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshBootstrapState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    CanvasHomeScreen(
        uiState = uiState,
        onNavigateToCanvas = onNavigateToCanvas,
        onNavigateToLockScreen = onNavigateToLockScreen,
        onNavigateToSettings = onNavigateToSettings,
        onInviteRequested = viewModel::onInviteRequested,
        onInviteSheetDismissed = viewModel::onInviteSheetDismissed
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CanvasHomeScreen(
    uiState: CanvasHomeUiState,
    onNavigateToCanvas: () -> Unit,
    onNavigateToLockScreen: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onInviteRequested: () -> Unit,
    onInviteSheetDismissed: () -> Unit
) {
    if (uiState.isInviteSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = onInviteSheetDismissed,
            modifier = Modifier.testTag(TestTags.HOME_INVITE_SHEET)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Invite your partner",
                    style = MaterialTheme.typography.headlineMedium
                )

                when {
                    uiState.isInviteLoading -> {
                        Text(
                            text = "Creating invite code...",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    uiState.currentInvite != null -> {
                        Text(
                            text = uiState.currentInvite.code.chunked(1).joinToString(" "),
                            style = MaterialTheme.typography.displaySmall
                        )
                        Text(
                            text = "Expires at ${uiState.currentInvite.expiresAt}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                uiState.inviteErrorMessage?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Text(
                    text = "Share this code with your partner and ask them to enter it after they sign up.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Button(
                    onClick = onInviteRequested,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                ) {
                    Text("Share invite code")
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.testTag(TestTags.HOME_SCREEN),
        topBar = {
            TopAppBar(
                title = { Text("Home") },
                actions = {
                    TextButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag(TestTags.HOME_SETTINGS_BUTTON)
                    ) {
                        Text("Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (uiState.bootstrapState.pairingStatus == com.subhajit.mulberry.data.bootstrap.PairingStatus.UNPAIRED) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = buildString {
                                append("Welcome ")
                                append(uiState.bootstrapState.userDisplayName ?: "there")
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Ask your partner out",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Text(
                            text = "${uiState.bootstrapState.userDisplayName ?: "You"} & who?",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Invite your partner to unlock the full experience.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = onInviteRequested,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(TestTags.HOME_SHARE_INVITE_BUTTON)
                        ) {
                            Text("Share invite code")
                        }
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.HOME_OPEN_CANVAS_BUTTON)
                    .clickable(onClick = onNavigateToCanvas)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Canvas", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "Local strokes: ${uiState.canvasState.strokes.size}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.HOME_OPEN_LOCKSCREEN_BUTTON)
                    .clickable(onClick = onNavigateToLockScreen)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Lock screen", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = if (uiState.canvasState.isEmpty) {
                            "No local canvas content yet"
                        } else {
                            "Preview placeholder available"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun CanvasSurfaceRoute(
    viewModel: CanvasHomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    CanvasSurfaceScreen(
        uiState = uiState,
        onCanvasPress = viewModel::onCanvasPress,
        onCanvasDrag = viewModel::onCanvasDrag,
        onCanvasRelease = viewModel::onCanvasRelease,
        onCanvasTap = viewModel::onCanvasTap,
        onCanvasViewportChanged = viewModel::onCanvasViewportChanged,
        onColorSelected = viewModel::onColorSelected,
        onBrushWidthChanged = viewModel::onBrushWidthChanged,
        onEraserToggle = viewModel::onEraserToggle,
        onClearRequested = viewModel::onClearRequested,
        onClearDismissed = viewModel::onClearDismissed,
        onClearConfirmed = viewModel::onClearConfirmed
    )
}

@Composable
private fun CanvasSurfaceScreen(
    uiState: CanvasHomeUiState,
    onCanvasPress: (StrokePoint) -> Unit,
    onCanvasDrag: (StrokePoint) -> Unit,
    onCanvasRelease: () -> Unit,
    onCanvasTap: (StrokePoint) -> Unit,
    onCanvasViewportChanged: (Int, Int) -> Unit,
    onColorSelected: (Long) -> Unit,
    onBrushWidthChanged: (Float) -> Unit,
    onEraserToggle: () -> Unit,
    onClearRequested: () -> Unit,
    onClearDismissed: () -> Unit,
    onClearConfirmed: () -> Unit
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
        modifier = Modifier.testTag(TestTags.CANVAS_SCREEN),
        floatingActionButton = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
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
                    onCanvasSizeChanged = onCanvasViewportChanged,
                    modifier = Modifier.padding(8.dp)
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Slider(
                        value = uiState.toolState.selectedWidth,
                        onValueChange = onBrushWidthChanged,
                        valueRange = DrawingDefaults.MIN_WIDTH..DrawingDefaults.MAX_WIDTH,
                        modifier = Modifier.testTag(TestTags.BRUSH_WIDTH_SLIDER)
                    )

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
