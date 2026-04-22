package com.subhajit.mulberry.home

import android.content.Intent
import android.graphics.Typeface as AndroidTypeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.drawing.model.DrawingDefaults
import com.subhajit.mulberry.drawing.model.DrawingTool
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.MulberrySecondaryFontFamily
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.wallpaper.WallpaperIntentFactory
import com.subhajit.mulberry.wallpaper.WallpaperPreset
import com.subhajit.mulberry.wallpaper.ui.WallpaperBackgroundSelectionSection
import com.subhajit.mulberry.wallpaper.ui.WallpaperLockScreenPreview
import com.subhajit.mulberry.wallpaper.ui.WallpaperPrimaryButton
import kotlinx.coroutines.launch

@Composable
fun CanvasHomeRoute(
    onNavigateToCanvas: () -> Unit,
    onNavigateToLockScreen: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: CanvasHomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val backgroundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.onBackgroundImageSelected(uri)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.refreshBootstrapState()
    }

    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CanvasHomeEffect.ShareInvite -> {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, effect.message)
                    }
                    context.startActivity(
                        Intent.createChooser(
                            sendIntent,
                            context.getString(R.string.home_invite_share_chooser_title)
                        )
                    )
                }

                CanvasHomeEffect.OpenWallpaperSetup ->
                    WallpaperIntentFactory.openWallpaperPicker(context)
            }
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshBootstrapState()
                viewModel.refreshWallpaperStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    CanvasHomeScreen(
        uiState = uiState,
        wallpaperPresets = viewModel.wallpaperPresets,
        onNavigateToLockScreen = onNavigateToLockScreen,
        onNavigateToSettings = onNavigateToSettings,
        onInviteRequested = viewModel::onInviteRequested,
        onInviteSheetDismissed = viewModel::onInviteSheetDismissed,
        onShareInviteClicked = viewModel::onShareInviteClicked,
        onSetUpLockScreen = viewModel::onSetUpLockScreenClicked,
        onUploadWallpaperBackground = {
            backgroundPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        onWallpaperPresetSelected = viewModel::onWallpaperPresetSelected,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CanvasHomeScreen(
    uiState: CanvasHomeUiState,
    wallpaperPresets: List<WallpaperPreset>,
    onNavigateToLockScreen: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onInviteRequested: () -> Unit,
    onInviteSheetDismissed: () -> Unit,
    onShareInviteClicked: () -> Unit,
    onSetUpLockScreen: () -> Unit,
    onUploadWallpaperBackground: () -> Unit,
    onWallpaperPresetSelected: (Int) -> Unit,
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
    val pagerState = rememberPagerState(pageCount = { MainAppTab.entries.size })
    val coroutineScope = rememberCoroutineScope()
    val selectedTab = MainAppTab.entries[pagerState.currentPage]
    val headerTitle = when (selectedTab) {
        MainAppTab.Canvas -> if (uiState.bootstrapState.pairingStatus == PairingStatus.UNPAIRED) {
            stringResource(R.string.home_unpaired_title)
        } else {
            stringResource(R.string.home_paired_title)
        }

        MainAppTab.LockScreen -> stringResource(R.string.home_lockscreen_title)
    }

    if (uiState.isInviteSheetVisible) {
        InviteCodeBottomSheet(
            inviteSheet = uiState.inviteSheet,
            onDismiss = onInviteSheetDismissed,
            onShareInviteClicked = onShareInviteClicked
        )
    }
    if (uiState.showClearConfirmation) {
        ClearCanvasConfirmationDialog(
            onDismiss = onClearDismissed,
            onConfirm = onClearConfirmed
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag(TestTags.HOME_SCREEN)
    ) {
        MainAppHeader(
            userName = uiState.bootstrapState.userDisplayName,
            userPhotoUrl = uiState.bootstrapState.userPhotoUrl,
            title = headerTitle,
            onProfileClick = onNavigateToSettings
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            when (MainAppTab.entries[page]) {
                MainAppTab.Canvas -> CanvasHomePane(
                    uiState = uiState,
                    onInviteRequested = onInviteRequested,
                    onCanvasPress = onCanvasPress,
                    onCanvasDrag = onCanvasDrag,
                    onCanvasRelease = onCanvasRelease,
                    onCanvasTap = onCanvasTap,
                    onCanvasViewportChanged = onCanvasViewportChanged,
                    onColorSelected = onColorSelected,
                    onBrushWidthChanged = onBrushWidthChanged,
                    onEraserToggle = onEraserToggle,
                    onClearRequested = onClearRequested
                )

                MainAppTab.LockScreen -> LockScreenHomePane(
                    uiState = uiState,
                    presets = wallpaperPresets,
                    onSetUpLockScreen = onSetUpLockScreen,
                    onUploadFromGallery = onUploadWallpaperBackground,
                    onPresetSelected = onWallpaperPresetSelected
                )
            }
        }

        MainAppBottomNavigation(
            selectedTab = selectedTab,
            onTabSelected = { tab ->
                coroutineScope.launch {
                    pagerState.animateScrollToPage(MainAppTab.entries.indexOf(tab))
                }
            },
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(top = 10.dp, bottom = 12.dp)
        )
    }
}

@Composable
private fun MainAppHeader(
    userName: String?,
    userPhotoUrl: String?,
    title: String,
    onProfileClick: () -> Unit
) {
    val displayName = userName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.home_default_user_name)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 34.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = buildAnnotatedString {
                    append(stringResource(R.string.home_header_welcome))
                    append(" ")
                    withStyle(SpanStyle(color = MulberryPrimary, fontWeight = FontWeight.SemiBold)) {
                        append(displayName)
                    }
                },
                color = Color(0xFF070B14),
                fontFamily = PoppinsFontFamily,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Normal
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                color = Color(0xFF070B14),
                fontFamily = PoppinsFontFamily,
                fontSize = 28.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp
            )
        }

        ProfileAvatar(
            photoUrl = userPhotoUrl,
            displayName = displayName,
            size = 38.dp,
            modifier = Modifier
                .padding(top = 26.dp)
                .testTag(TestTags.HOME_SETTINGS_BUTTON)
                .clickable(onClick = onProfileClick)
        )
    }
}

@Composable
private fun CanvasHomePane(
    uiState: CanvasHomeUiState,
    onInviteRequested: () -> Unit,
    onCanvasPress: (StrokePoint) -> Unit,
    onCanvasDrag: (StrokePoint) -> Unit,
    onCanvasRelease: () -> Unit,
    onCanvasTap: (StrokePoint) -> Unit,
    onCanvasViewportChanged: (Int, Int) -> Unit,
    onColorSelected: (Long) -> Unit,
    onBrushWidthChanged: (Float) -> Unit,
    onEraserToggle: () -> Unit,
    onClearRequested: () -> Unit
) {
    val userName = uiState.bootstrapState.userDisplayName
        ?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.home_default_user_name)

    if (uiState.bootstrapState.pairingStatus == PairingStatus.UNPAIRED) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(top = 150.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OverlappingInviteAvatars(
                userName = userName,
                userPhotoUrl = uiState.bootstrapState.userPhotoUrl,
                onQuestionClick = onInviteRequested
            )
            Spacer(modifier = Modifier.height(34.dp))
            Text(
                text = stringResource(R.string.home_unpaired_pair_title, userName),
                color = Color(0xFF0A0C14),
                fontFamily = PoppinsFontFamily,
                fontSize = 17.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.home_unpaired_body),
                color = Color(0xFF737373),
                fontFamily = PoppinsFontFamily,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(306.dp)
            )
            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick = onInviteRequested,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag(TestTags.HOME_SHARE_INVITE_BUTTON),
                shape = RoundedCornerShape(15.38.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MulberryPrimary)
            ) {
                Text(
                    text = stringResource(R.string.home_invite_button),
                    color = Color.White,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    } else {
        PairedCanvasPane(
            uiState = uiState,
            onCanvasPress = onCanvasPress,
            onCanvasDrag = onCanvasDrag,
            onCanvasRelease = onCanvasRelease,
            onCanvasTap = onCanvasTap,
            onCanvasViewportChanged = onCanvasViewportChanged,
            onColorSelected = onColorSelected,
            onBrushWidthChanged = onBrushWidthChanged,
            onEraserToggle = onEraserToggle,
            onClearRequested = onClearRequested,
            modifier = Modifier.testTag(TestTags.HOME_OPEN_CANVAS_BUTTON)
        )
    }
}

@Composable
private fun PairedCanvasPane(
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 34.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFFFCF4F5))
                .border(
                    width = 2.dp,
                    color = Color(0x4DB31329),
                    shape = RoundedCornerShape(24.dp)
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                strokeRenderMode = uiState.canvasStrokeRenderMode
            )

            if (uiState.canvasState.isEmpty) {
                CanvasBlankStateGuidance(
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 22.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CanvasActionButton(
                    drawableRes = R.drawable.canvas_action_erase,
                    contentDescription = stringResource(R.string.home_canvas_erase_content_description),
                    selected = uiState.toolState.activeTool == DrawingTool.ERASE,
                    onClick = onEraserToggle
                )
                CanvasActionButton(
                    drawableRes = R.drawable.canvas_action_clear,
                    contentDescription = stringResource(R.string.home_canvas_clear_content_description),
                    selected = false,
                    onClick = onClearRequested
                )
            }
        }

        CanvasControlTray(
            uiState = uiState,
            onBrushWidthChanged = onBrushWidthChanged,
            onColorSelected = onColorSelected
        )
    }
}

@Composable
private fun CanvasBlankStateGuidance(modifier: Modifier = Modifier) {
    val guidanceFontFamily = rememberMulberryGuidanceFontFamily()

    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.home_paired_canvas_guidance_title),
            color = MulberryPrimary,
            fontFamily = guidanceFontFamily,
            fontSize = 20.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.25.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.home_paired_canvas_guidance_body),
            color = Color(0x99A39E9B),
            fontFamily = guidanceFontFamily,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.25.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(252.dp)
        )
    }
}

@Composable
private fun rememberMulberryGuidanceFontFamily(): FontFamily {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            FontFamily(AndroidTypeface.createFromAsset(context.assets, "fonts/virgil.woff2"))
        }.getOrDefault(MulberrySecondaryFontFamily)
    }
}

@Composable
private fun CanvasActionButton(
    drawableRes: Int,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(50.dp)
            .shadow(
                elevation = 14.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color(0x1A3D3D3D),
                spotColor = Color(0x1A3D3D3D)
            )
            .clip(CircleShape)
            .background(if (selected) Color(0xFFFFC6CE) else Color(0xFFFFD6DA))
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) MulberryPrimary else Color.Transparent,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
            .padding(7.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(drawableRes),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun CanvasControlTray(
    uiState: CanvasHomeUiState,
    onBrushWidthChanged: (Float) -> Unit,
    onColorSelected: (Long) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(69.dp)
            .shadow(
                elevation = 15.dp,
                shape = RoundedCornerShape(500.dp),
                clip = false,
                ambientColor = Color(0x26000000),
                spotColor = Color(0x26000000)
            )
            .clip(RoundedCornerShape(500.dp))
            .background(Color(0xFFFFF4F5))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .width(168.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(500.dp))
                .background(Color.White)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Slider(
                value = uiState.toolState.selectedWidth,
                onValueChange = onBrushWidthChanged,
                valueRange = DrawingDefaults.MIN_WIDTH..DrawingDefaults.MAX_WIDTH,
                colors = SliderDefaults.colors(
                    thumbColor = MulberryPrimary,
                    activeTrackColor = MulberryPrimary,
                    inactiveTrackColor = Color(0xFFFFEBED)
                ),
                modifier = Modifier.testTag(TestTags.BRUSH_WIDTH_SLIDER)
            )
        }

        uiState.palette.forEach { color ->
            ColorSwatch(
                colorArgb = color,
                isSelected = color == uiState.toolState.selectedColorArgb &&
                    uiState.toolState.activeTool == DrawingTool.DRAW,
                onClick = { onColorSelected(color) },
                size = 47.dp
            )
        }
    }
}

@Composable
private fun LockScreenHomePane(
    uiState: CanvasHomeUiState,
    presets: List<WallpaperPreset>,
    onSetUpLockScreen: () -> Unit,
    onUploadFromGallery: () -> Unit,
    onPresetSelected: (Int) -> Unit
) {
    val selectedPreviewResId = presets
        .firstOrNull { it.drawableResId == uiState.selectedWallpaperPresetResId }
        ?.previewDrawableResId
        ?: R.drawable.wallpaper_default_bg_preview
    val previewAssetPath = if (uiState.selectedWallpaperPresetResId == null) {
        uiState.backgroundImageState.assetPath
    } else {
        null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 34.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(34.dp)
        ) {
            WallpaperLockScreenPreview(
                assetPath = previewAssetPath,
                assetUpdatedAt = uiState.backgroundImageState.lastUpdatedAt,
                fallbackBackgroundRes = selectedPreviewResId
            )

            if (!uiState.wallpaperStatus.isWallpaperSelected) {
                WallpaperPrimaryButton(
                    text = stringResource(R.string.home_lockscreen_setup_button),
                    onClick = onSetUpLockScreen,
                    enabled = !uiState.isWallpaperBusy,
                    modifier = Modifier.testTag(TestTags.HOME_OPEN_LOCKSCREEN_BUTTON)
                )
            }

            uiState.wallpaperErrorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            WallpaperBackgroundSelectionSection(
                presets = presets,
                selectedPresetResId = uiState.selectedWallpaperPresetResId,
                onUploadFromGallery = onUploadFromGallery,
                onPresetSelected = onPresetSelected
            )
        }

        if (uiState.isWallpaperBusy) {
            CircularProgressIndicator(
                color = MulberryPrimary,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun ClearCanvasConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_clear_canvas_title)) },
        text = { Text(stringResource(R.string.home_clear_canvas_body)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(TestTags.CLEAR_CONFIRM_BUTTON)
            ) {
                Text(stringResource(R.string.home_clear_canvas_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_clear_canvas_cancel))
            }
        }
    )
}

@Composable
private fun OverlappingInviteAvatars(
    userName: String,
    userPhotoUrl: String?,
    onQuestionClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(267.dp)
            .height(155.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        QuestionAvatar(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 128.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onQuestionClick
                )
        )
        ProfileAvatar(
            photoUrl = userPhotoUrl,
            displayName = userName,
            size = 139.dp,
            borderWidth = 8.dp,
            shadowElevation = 14.dp,
            modifier = Modifier.align(Alignment.CenterStart)
        )
    }
}

@Composable
private fun ProfileAvatar(
    photoUrl: String?,
    displayName: String,
    size: Dp,
    modifier: Modifier = Modifier,
    borderWidth: Dp = 0.dp,
    shadowElevation: Dp = 0.dp
) {
    val initial = displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "M"
    val avatarModifier = if (shadowElevation > 0.dp) {
        modifier.shadow(
            elevation = shadowElevation,
            shape = CircleShape,
            clip = false,
            ambientColor = Color(0x33000000),
            spotColor = Color(0x26000000)
        )
    } else {
        modifier
    }

    Box(
        modifier = avatarModifier
            .size(size)
            .border(borderWidth, Color(0xFFFFEDF0), CircleShape)
            .clip(CircleShape)
            .background(MulberryPrimary),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = Color.White,
            fontFamily = PoppinsFontFamily,
            fontSize = (size.value * 0.34f).sp,
            fontWeight = FontWeight.SemiBold
        )
        if (!photoUrl.isNullOrBlank()) {
            AsyncImage(
                model = photoUrl,
                contentDescription = stringResource(R.string.home_profile_photo_content_description),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun QuestionAvatar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(139.dp)
            .border(8.dp, Color(0xFFFFEDF0), CircleShape)
            .clip(CircleShape)
            .background(Color(0xFFFFF8F9)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "?",
            color = MulberryPrimary,
            fontFamily = PoppinsFontFamily,
            fontSize = 50.sp,
            lineHeight = 56.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun MainAppBottomNavigation(
    selectedTab: MainAppTab,
    onTabSelected: (MainAppTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color.White),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        MainAppBottomNavItem(
            tab = MainAppTab.Canvas,
            selected = selectedTab == MainAppTab.Canvas,
            onClick = { onTabSelected(MainAppTab.Canvas) },
            modifier = Modifier.weight(1f)
        )
        MainAppBottomNavItem(
            tab = MainAppTab.LockScreen,
            selected = selectedTab == MainAppTab.LockScreen,
            onClick = { onTabSelected(MainAppTab.LockScreen) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MainAppBottomNavItem(
    tab: MainAppTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeColor = MulberryPrimary
    val inactiveColor = Color(0xFFC5C5C5)
    val itemColor = if (selected) activeColor else inactiveColor
    val label = when (tab) {
        MainAppTab.Canvas -> stringResource(R.string.home_tab_canvas)
        MainAppTab.LockScreen -> stringResource(R.string.home_tab_lockscreen)
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Color(0xFFFFEEF1) else Color.Transparent)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (tab) {
            MainAppTab.Canvas -> BrushNavIcon(color = itemColor)
            MainAppTab.LockScreen -> LockNavIcon(color = itemColor)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = itemColor,
            fontFamily = PoppinsFontFamily,
            fontSize = 15.sp,
            lineHeight = 18.sp,
            fontWeight = if (selected) FontWeight.Normal else FontWeight.Medium
        )
    }
}

@Composable
private fun BrushNavIcon(color: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        drawLine(
            color = color,
            start = Offset(size.width * 0.28f, size.height * 0.72f),
            end = Offset(size.width * 0.72f, size.height * 0.28f),
            strokeWidth = size.width * 0.15f,
            cap = StrokeCap.Round
        )
        drawCircle(
            color = color,
            radius = size.width * 0.18f,
            center = Offset(size.width * 0.31f, size.height * 0.73f)
        )
        drawCircle(
            color = color,
            radius = size.width * 0.09f,
            center = Offset(size.width * 0.18f, size.height * 0.84f)
        )
    }
}

@Composable
private fun LockNavIcon(color: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.2f, size.height * 0.46f),
            size = Size(size.width * 0.6f, size.height * 0.42f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.08f)
        )
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.32f, size.height * 0.14f),
            size = Size(size.width * 0.36f, size.height * 0.52f),
            style = Stroke(width = size.width * 0.12f, cap = StrokeCap.Round)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InviteCodeBottomSheet(
    inviteSheet: InviteSheetUiState,
    onDismiss: () -> Unit,
    onShareInviteClicked: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(sheetState) {
        sheetState.expand()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(TestTags.HOME_INVITE_SHEET),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = Color.White,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 19.dp)
                    .size(width = 44.dp, height = 4.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .background(Color(0xFFDEDEDE))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.home_invite_sheet_title),
                color = Color.Black,
                fontFamily = PoppinsFontFamily,
                fontSize = 20.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Image(
                painter = painterResource(R.drawable.invite_envelope_heart),
                contentDescription = null,
                modifier = Modifier.size(190.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(16.dp))
            InviteCodeCells(code = inviteSheet.code.orEmpty())
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = when {
                    inviteSheet.isLoading -> stringResource(R.string.home_invite_loading)
                    inviteSheet.isExpired -> stringResource(R.string.home_invite_countdown_expired)
                    inviteSheet.hasCode -> inviteSheet.remainingSeconds.formatInviteCountdown()
                    else -> ""
                },
                color = MulberryPrimary,
                fontFamily = PoppinsFontFamily,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.home_invite_sheet_body),
                color = Color.Black,
                fontFamily = PoppinsFontFamily,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(302.dp)
            )
            inviteSheet.errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onShareInviteClicked,
                enabled = !inviteSheet.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(15.38.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MulberryPrimary,
                    disabledContainerColor = MulberryPrimary.copy(alpha = 0.45f)
                )
            ) {
                Text(
                    text = stringResource(R.string.home_invite_button),
                    color = Color.White,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun InviteCodeCells(code: String) {
    val digits = code.padEnd(6, ' ').take(6)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        digits.forEach { digit ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(0.84f)
                    .clip(RoundedCornerShape(15.38.dp))
                    .background(Color(0xFFF3F3F3)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = digit.toString(),
                    color = Color.Black,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 30.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private fun Long.formatInviteCountdown(): String {
    val hours = this / 3_600
    val minutes = (this % 3_600) / 60
    val seconds = this % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
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
        ClearCanvasConfirmationDialog(
            onDismiss = onClearDismissed,
            onConfirm = onClearConfirmed
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
                    modifier = Modifier.padding(8.dp),
                    strokeRenderMode = uiState.canvasStrokeRenderMode
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
    onClick: () -> Unit,
    size: Dp = 40.dp
) {
    val outlineColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        modifier = Modifier
            .size(size)
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
