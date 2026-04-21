package com.subhajit.mulberry.home

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
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
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
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
            }
        }
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
        onInviteSheetDismissed = viewModel::onInviteSheetDismissed,
        onShareInviteClicked = viewModel::onShareInviteClicked
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
    onInviteSheetDismissed: () -> Unit,
    onShareInviteClicked: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { MainAppTab.entries.size })
    val coroutineScope = rememberCoroutineScope()
    val selectedTab = MainAppTab.entries[pagerState.currentPage]

    if (uiState.isInviteSheetVisible) {
        InviteCodeBottomSheet(
            inviteSheet = uiState.inviteSheet,
            onDismiss = onInviteSheetDismissed,
            onShareInviteClicked = onShareInviteClicked
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
                    onNavigateToCanvas = onNavigateToCanvas
                )

                MainAppTab.LockScreen -> LockScreenHomePane(
                    uiState = uiState,
                    onNavigateToLockScreen = onNavigateToLockScreen
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
                .padding(bottom = 12.dp)
        )
    }
}

@Composable
private fun MainAppHeader(
    userName: String?,
    userPhotoUrl: String?,
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
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.home_unpaired_title),
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
                .padding(top = 32.dp)
                .testTag(TestTags.HOME_SETTINGS_BUTTON)
                .clickable(onClick = onProfileClick)
        )
    }
}

@Composable
private fun CanvasHomePane(
    uiState: CanvasHomeUiState,
    onInviteRequested: () -> Unit,
    onNavigateToCanvas: () -> Unit
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
                lineHeight = 24.sp,
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
                    fontWeight = FontWeight.Normal
                )
            }
        }
    } else {
        PairedPaneCard(
            title = stringResource(R.string.home_tab_canvas),
            body = stringResource(R.string.home_canvas_paired_body, uiState.canvasState.strokes.size),
            button = stringResource(R.string.home_open_canvas),
            testTag = TestTags.HOME_OPEN_CANVAS_BUTTON,
            onClick = onNavigateToCanvas
        )
    }
}

@Composable
private fun LockScreenHomePane(
    uiState: CanvasHomeUiState,
    onNavigateToLockScreen: () -> Unit
) {
    PairedPaneCard(
        title = stringResource(R.string.home_tab_lockscreen),
        body = if (uiState.wallpaperStatus.isWallpaperSelected) {
            stringResource(R.string.home_lockscreen_selected_body)
        } else {
            stringResource(R.string.home_lockscreen_unselected_body)
        },
        button = stringResource(R.string.home_open_lockscreen),
        testTag = TestTags.HOME_OPEN_LOCKSCREEN_BUTTON,
        onClick = onNavigateToLockScreen
    )
}

@Composable
private fun PairedPaneCard(
    title: String,
    body: String,
    button: String,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
                .clickable(onClick = onClick)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = button,
                    color = MulberryPrimary,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
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
        ProfileAvatar(
            photoUrl = userPhotoUrl,
            displayName = userName,
            size = 139.dp,
            borderWidth = 8.dp,
            modifier = Modifier.align(Alignment.CenterStart)
        )
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
    }
}

@Composable
private fun ProfileAvatar(
    photoUrl: String?,
    displayName: String,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    borderWidth: androidx.compose.ui.unit.Dp = 0.dp
) {
    val initial = displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "M"
    Box(
        modifier = modifier
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(TestTags.HOME_INVITE_SHEET),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = Color.White,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 16.dp, bottom = 22.dp)
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
            Spacer(modifier = Modifier.height(40.dp))
            Image(
                painter = painterResource(R.drawable.invite_envelope_heart),
                contentDescription = null,
                modifier = Modifier.size(150.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(44.dp))
            InviteCodeCells(code = inviteSheet.code.orEmpty())
            Spacer(modifier = Modifier.height(18.dp))
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
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.home_invite_sheet_body),
                color = Color.Black,
                fontFamily = PoppinsFontFamily,
                fontSize = 12.sp,
                lineHeight = 22.sp,
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
            Spacer(modifier = Modifier.height(26.dp))
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
                    fontWeight = FontWeight.Normal
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
                    fontSize = 27.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }
}

private fun Long.formatInviteCountdown(): String {
    val minutes = this / 60
    val seconds = this % 60
    return "%02d:%02d".format(minutes, seconds)
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
