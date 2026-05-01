package com.subhajit.mulberry.home

import android.content.Intent
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Constraints
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
import com.subhajit.mulberry.app.shortcut.AppShortcutAction
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.core.ui.mulberryTapScale
import com.subhajit.mulberry.data.bootstrap.AuthStatus
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.drawing.model.DrawingDefaults
import com.subhajit.mulberry.drawing.model.DrawingTool
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.review.InAppReviewLauncher
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.VirgilFontFamily
import com.subhajit.mulberry.ui.theme.mulberryAppColors
import com.subhajit.mulberry.wallpaper.RemoteWallpaper
import com.subhajit.mulberry.wallpaper.WallpaperIntentFactory
import com.subhajit.mulberry.wallpaper.WallpaperPreset
import com.subhajit.mulberry.wallpaper.ui.WallpaperBackgroundSelectionSection
import com.subhajit.mulberry.wallpaper.ui.WallpaperLockScreenPreview
import com.subhajit.mulberry.wallpaper.ui.WallpaperPrimaryButton
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Angle
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.Spread
import nl.dionsegijn.konfetti.core.emitter.Emitter
import nl.dionsegijn.konfetti.core.models.Shape
import nl.dionsegijn.konfetti.core.models.Size as KonfettiSize
import java.util.concurrent.TimeUnit

@Composable
fun CanvasHomeRoute(
    shortcutAction: AppShortcutAction? = null,
    onShortcutActionHandled: (AppShortcutAction) -> Unit = {},
    onNavigateToCanvas: () -> Unit,
    onNavigateToLockScreen: () -> Unit,
    onNavigateToWallpaperCatalog: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToPairingHub: () -> Unit,
    viewModel: CanvasHomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val activity = context as? ComponentActivity
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

                is CanvasHomeEffect.LaunchInAppReview -> {
                    if (activity != null) {
                        InAppReviewLauncher.launchWithDiagnostics(activity)
                    }
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshBootstrapState()
                viewModel.refreshWallpaperStatus()
                viewModel.onHomeResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    CanvasHomeScreen(
        uiState = uiState,
        shortcutAction = shortcutAction,
        wallpaperPresets = viewModel.wallpaperPresets,
        onNavigateToLockScreen = onNavigateToLockScreen,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToPairingHub = onNavigateToPairingHub,
        onInviteRequested = viewModel::onInviteRequested,
        onPairingSheetDismissed = viewModel::onPairingSheetDismissed,
        onShareInviteClicked = viewModel::onShareInviteClicked,
        onJoinCodeRequested = viewModel::onJoinCodeRequested,
        onPartnerDetailsRequested = viewModel::onPartnerDetailsRequested,
        onPartnerNameChanged = viewModel::onPartnerNameChanged,
        onPartnerAnniversaryChanged = viewModel::onPartnerAnniversaryChanged,
        onPartnerDetailsSubmitted = viewModel::onPartnerDetailsSubmitted,
        onPairingConfirmationRequested = viewModel::onPairingConfirmationRequested,
        onJoinCodeChanged = viewModel::onJoinCodeChanged,
        onJoinCodeSubmitted = viewModel::onJoinCodeSubmitted,
        onDisconnectFromConfirmation = viewModel::onDisconnectFromConfirmation,
        onKeepCurrentPairing = viewModel::onKeepCurrentPairingClicked,
        onDisconnectAndSwitchFromInboundInvite = viewModel::onDisconnectAndSwitchFromInboundInvite,
        onSetUpLockScreen = viewModel::onSetUpLockScreenClicked,
        onViewMoreWallpapers = onNavigateToWallpaperCatalog,
        onUploadWallpaperBackground = {
            backgroundPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        onWallpaperPresetSelected = viewModel::onWallpaperPresetSelected,
        onRemoteWallpaperSelected = viewModel::onRemoteWallpaperSelected,
        onCanvasPress = viewModel::onCanvasPress,
        onCanvasDrag = viewModel::onCanvasDrag,
        onCanvasRelease = viewModel::onCanvasRelease,
        onCanvasTap = viewModel::onCanvasTap,
        onCanvasViewportChanged = viewModel::onCanvasViewportChanged,
        onColorSelected = viewModel::onColorSelected,
        onBrushWidthChanged = viewModel::onBrushWidthChanged,
        onEraserToggle = viewModel::onEraserToggle,
        onTextToggle = viewModel::onTextToggle,
        onTextElementAdded = viewModel::onTextElementAdded,
        onTextElementUpdated = viewModel::onTextElementUpdated,
        onTextElementDeleted = viewModel::onTextElementDeleted,
        onClearRequested = viewModel::onClearRequested,
        onClearDismissed = viewModel::onClearDismissed,
        onClearConfirmed = viewModel::onClearConfirmed,
        onUndoRequested = viewModel::onUndoRequested,
        onRedoRequested = viewModel::onRedoRequested,
        onShortcutActionHandled = onShortcutActionHandled
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CanvasHomeScreen(
    uiState: CanvasHomeUiState,
    shortcutAction: AppShortcutAction?,
    wallpaperPresets: List<WallpaperPreset>,
    onNavigateToLockScreen: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToPairingHub: () -> Unit,
    onInviteRequested: () -> Unit,
    onPairingSheetDismissed: () -> Unit,
    onShareInviteClicked: () -> Unit,
    onJoinCodeRequested: () -> Unit,
    onPartnerDetailsRequested: () -> Unit,
    onPartnerNameChanged: (String) -> Unit,
    onPartnerAnniversaryChanged: (String) -> Unit,
    onPartnerDetailsSubmitted: () -> Unit,
    onPairingConfirmationRequested: () -> Unit,
    onJoinCodeChanged: (String) -> Unit,
    onJoinCodeSubmitted: () -> Unit,
    onDisconnectFromConfirmation: () -> Unit,
    onKeepCurrentPairing: () -> Unit,
    onDisconnectAndSwitchFromInboundInvite: () -> Unit,
    onSetUpLockScreen: () -> Unit,
    onViewMoreWallpapers: () -> Unit,
    onUploadWallpaperBackground: () -> Unit,
    onWallpaperPresetSelected: (Int) -> Unit,
    onRemoteWallpaperSelected: (RemoteWallpaper) -> Unit,
    onCanvasPress: (StrokePoint) -> Unit,
    onCanvasDrag: (StrokePoint) -> Unit,
    onCanvasRelease: () -> Unit,
    onCanvasTap: (StrokePoint) -> Unit,
    onCanvasViewportChanged: (Int, Int) -> Unit,
    onColorSelected: (Long) -> Unit,
    onBrushWidthChanged: (Float) -> Unit,
    onEraserToggle: () -> Unit,
    onTextToggle: () -> Unit,
    onTextElementAdded: (com.subhajit.mulberry.drawing.model.CanvasTextElement) -> Unit,
    onTextElementUpdated: (com.subhajit.mulberry.drawing.model.CanvasTextElement) -> Unit,
    onTextElementDeleted: (String) -> Unit,
    onClearRequested: () -> Unit,
    onClearDismissed: () -> Unit,
    onClearConfirmed: () -> Unit,
    onUndoRequested: () -> Unit,
    onRedoRequested: () -> Unit,
    onShortcutActionHandled: (AppShortcutAction) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { MainAppTab.entries.size })
    val coroutineScope = rememberCoroutineScope()
    val selectedTab = MainAppTab.entries[pagerState.currentPage]
    var textEditorSession by remember { mutableStateOf<CanvasTextEditorSession?>(null) }
    val headerTitle = when (selectedTab) {
        MainAppTab.Canvas -> if (uiState.bootstrapState.pairingStatus == PairingStatus.UNPAIRED) {
            stringResource(R.string.home_unpaired_title)
        } else {
            stringResource(R.string.home_paired_title)
        }

        MainAppTab.LockScreen -> stringResource(R.string.home_lockscreen_title)
    }
    val isHomeReady =
        uiState.bootstrapState.authStatus == AuthStatus.SIGNED_IN &&
            uiState.bootstrapState.hasCompletedOnboarding

    LaunchedEffect(shortcutAction, isHomeReady, uiState.bootstrapState.pairingStatus) {
        val action = shortcutAction ?: return@LaunchedEffect
        if (!isHomeReady) return@LaunchedEffect

        when (action) {
            AppShortcutAction.ClearDoodles -> {
                if (uiState.bootstrapState.pairingStatus == PairingStatus.PAIRED) {
                    onClearRequested()
                }
                onShortcutActionHandled(action)
            }

            AppShortcutAction.ChangeWallpaper -> {
                pagerState.animateScrollToPage(MainAppTab.entries.indexOf(MainAppTab.LockScreen))
                onShortcutActionHandled(action)
            }

            AppShortcutAction.ShowPairingConfirmation -> {
                onPairingConfirmationRequested()
                onShortcutActionHandled(action)
            }

            AppShortcutAction.ShowPairingHub -> {
                if (uiState.bootstrapState.pairingStatus == PairingStatus.UNPAIRED) {
                    onNavigateToPairingHub()
                }
                onShortcutActionHandled(action)
            }

            AppShortcutAction.ShowSettings -> {
                onShortcutActionHandled(action)
            }
        }
    }

    when (uiState.pairingSheetMode) {
        HomePairingSheetMode.Hidden -> Unit
        HomePairingSheetMode.ShareInvite -> InviteCodeBottomSheet(
            inviteSheet = uiState.inviteSheet,
            onDismiss = onPairingSheetDismissed,
            onShareInviteClicked = onShareInviteClicked,
            onPartnerDetailsRequested = onPartnerDetailsRequested
        )

        HomePairingSheetMode.JoinCodeEntry -> JoinCodeBottomSheet(
            joinCode = uiState.joinCode,
            onCodeChanged = onJoinCodeChanged,
            onSubmit = onJoinCodeSubmitted,
            onDismiss = onPairingSheetDismissed
        )

        HomePairingSheetMode.PartnerDetails -> PartnerDetailsBottomSheet(
            form = uiState.partnerDetailsForm,
            onPartnerNameChanged = onPartnerNameChanged,
            onAnniversaryChanged = onPartnerAnniversaryChanged,
            onSubmit = onPartnerDetailsSubmitted,
            onDismiss = onPairingSheetDismissed
        )

        HomePairingSheetMode.PairingConfirmed -> PairingConfirmedBottomSheet(
            userName = uiState.bootstrapState.userDisplayName,
            partnerName = uiState.bootstrapState.partnerDisplayName,
            confirmation = uiState.pairingConfirmation,
            onDismiss = onPairingSheetDismissed,
            onDisconnect = onDisconnectFromConfirmation
        )

        HomePairingSheetMode.InboundInvitePaired -> InboundInvitePairedBottomSheet(
            partnerName = uiState.bootstrapState.partnerDisplayName,
            confirmation = uiState.pairingConfirmation,
            onKeepPairing = onKeepCurrentPairing,
            onDisconnectAndSwitch = onDisconnectAndSwitchFromInboundInvite,
            onDismiss = onKeepCurrentPairing
        )
    }
    if (uiState.showClearConfirmation) {
        ClearCanvasConfirmationDialog(
            onDismiss = onClearDismissed,
            onConfirm = onClearConfirmed
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val editorOpen = textEditorSession != null
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .graphicsLayer {
                    if (editorOpen && Build.VERSION.SDK_INT >= 31) {
                        renderEffect = RenderEffect.createBlurEffect(
                            18f,
                            18f,
                            Shader.TileMode.CLAMP
                        ).asComposeRenderEffect()
                    } else {
                        renderEffect = null
                    }
                    alpha = if (editorOpen) 0.92f else 1f
                }
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
                        onJoinCodeRequested = onJoinCodeRequested,
                        onCanvasPress = onCanvasPress,
                        onCanvasDrag = onCanvasDrag,
                        onCanvasRelease = onCanvasRelease,
                        onCanvasTap = onCanvasTap,
                        onCanvasViewportChanged = onCanvasViewportChanged,
                        onColorSelected = onColorSelected,
                        onBrushWidthChanged = onBrushWidthChanged,
                        onEraserToggle = onEraserToggle,
                        onTextToggle = onTextToggle,
                        onTextElementAdded = onTextElementAdded,
                        onTextElementUpdated = onTextElementUpdated,
                        onTextElementDeleted = onTextElementDeleted,
                        onTextEditorRequested = { session -> textEditorSession = session },
                        isTextEditorOpen = textEditorSession != null,
                        onClearRequested = onClearRequested,
                        onUndoRequested = onUndoRequested,
                        onRedoRequested = onRedoRequested
                    )

                    MainAppTab.LockScreen -> LockScreenHomePane(
                        uiState = uiState,
                        presets = wallpaperPresets,
                        onSetUpLockScreen = onSetUpLockScreen,
                        onViewMoreWallpapers = onViewMoreWallpapers,
                        onUploadFromGallery = onUploadWallpaperBackground,
                        onPresetSelected = onWallpaperPresetSelected,
                        onRemoteWallpaperSelected = onRemoteWallpaperSelected
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

        val session = textEditorSession
        if (session != null) {
            TextEditorOverlay(
                element = session.element,
                palette = uiState.palette,
                autoFocus = true,
                onDismiss = {
                    textEditorSession = null
                },
                onDone = { updated ->
                    if (updated.text.isBlank()) onTextElementDeleted(updated.id) else onTextElementUpdated(updated)
                    textEditorSession = null
                },
                onDelete = { elementId ->
                    onTextElementDeleted(elementId)
                    textEditorSession = null
                }
            )
        }
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
                color = MaterialTheme.colorScheme.onBackground,
                fontFamily = PoppinsFontFamily,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Normal
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onBackground,
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
    onJoinCodeRequested: () -> Unit,
    onCanvasPress: (StrokePoint) -> Unit,
    onCanvasDrag: (StrokePoint) -> Unit,
    onCanvasRelease: () -> Unit,
    onCanvasTap: (StrokePoint) -> Unit,
    onCanvasViewportChanged: (Int, Int) -> Unit,
    onColorSelected: (Long) -> Unit,
    onBrushWidthChanged: (Float) -> Unit,
    onEraserToggle: () -> Unit,
    onTextToggle: () -> Unit,
    onTextElementAdded: (com.subhajit.mulberry.drawing.model.CanvasTextElement) -> Unit,
    onTextElementUpdated: (com.subhajit.mulberry.drawing.model.CanvasTextElement) -> Unit,
    onTextElementDeleted: (String) -> Unit,
    onTextEditorRequested: (CanvasTextEditorSession) -> Unit,
    isTextEditorOpen: Boolean,
    onClearRequested: () -> Unit,
    onUndoRequested: () -> Unit,
    onRedoRequested: () -> Unit
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
                color = MaterialTheme.colorScheme.onBackground,
                fontFamily = PoppinsFontFamily,
                fontSize = 17.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.home_unpaired_body),
                color = MaterialTheme.mulberryAppColors.mutedText,
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
                    .testTag(TestTags.HOME_SHARE_INVITE_BUTTON)
                    .mulberryTapScale(),
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
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = buildAnnotatedString {
                    append(stringResource(R.string.home_enter_code_prompt))
                    append(" ")
                    withStyle(SpanStyle(color = MulberryPrimary, fontWeight = FontWeight.SemiBold)) {
                        append(stringResource(R.string.home_enter_code_action))
                    }
                },
                color = MaterialTheme.mulberryAppColors.mutedText,
                fontFamily = PoppinsFontFamily,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onJoinCodeRequested
                    )
                    .testTag(TestTags.HOME_ENTER_CODE_BUTTON)
            )
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
            onTextToggle = onTextToggle,
            onTextElementAdded = onTextElementAdded,
            onTextElementUpdated = onTextElementUpdated,
            onTextElementDeleted = onTextElementDeleted,
            onTextEditorRequested = onTextEditorRequested,
            isTextEditorOpen = isTextEditorOpen,
            onClearRequested = onClearRequested,
            onUndoRequested = onUndoRequested,
            onRedoRequested = onRedoRequested,
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
    onTextToggle: () -> Unit,
    onTextElementAdded: (com.subhajit.mulberry.drawing.model.CanvasTextElement) -> Unit,
    onTextElementUpdated: (com.subhajit.mulberry.drawing.model.CanvasTextElement) -> Unit,
    onTextElementDeleted: (String) -> Unit,
    onTextEditorRequested: (CanvasTextEditorSession) -> Unit,
    isTextEditorOpen: Boolean,
    onClearRequested: () -> Unit,
    onUndoRequested: () -> Unit,
    onRedoRequested: () -> Unit,
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
                .background(MaterialTheme.mulberryAppColors.softSurface)
                .border(
                    width = 2.dp,
                    color = MulberryPrimary.copy(alpha = 0.30f),
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

            CanvasTextOverlay(
                elements = uiState.canvasState.textElements,
                activeTool = uiState.toolState.activeTool,
                palette = uiState.palette,
                selectedColorArgb = uiState.toolState.selectedColorArgb,
                onAddElement = onTextElementAdded,
                onUpdateElement = onTextElementUpdated,
                onDeleteElement = onTextElementDeleted,
                onRequestEdit = onTextEditorRequested,
                isEditorOpen = isTextEditorOpen,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            )

            if (uiState.canvasState.isEmpty) {
                CanvasBlankStateGuidance(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        CanvasControlTray(
            uiState = uiState,
            onBrushWidthChanged = onBrushWidthChanged,
            onColorSelected = onColorSelected,
            onUndoRequested = onUndoRequested,
            onRedoRequested = onRedoRequested,
            onEraserToggle = onEraserToggle,
            onTextToggle = onTextToggle,
            onClearRequested = onClearRequested
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
            color = MaterialTheme.mulberryAppColors.mutedText,
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
    return VirgilFontFamily
}

@Composable
private fun CanvasActionButton(
    drawableRes: Int,
    contentDescription: String,
    selected: Boolean,
    enabled: Boolean = true,
    showSelectedRing: Boolean = selected,
    dimWhenNotSelected: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 50.dp
) {
    val ringWidth = 2.dp
    val ringColor = MulberryPrimary.copy(alpha = 0.95f)
    val iconAlpha = when {
        !enabled -> 0.35f
        selected -> 1f
        dimWhenNotSelected -> 0.60f
        else -> 1f
    }
    Box(
        modifier = modifier
            .size(size)
            .shadow(
                elevation = 14.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color(0x1A3D3D3D),
                spotColor = Color(0x1A3D3D3D)
            )
            .clip(CircleShape)
            .background(MaterialTheme.mulberryAppColors.softSurfaceStrong)
            .border(
                width = if (showSelectedRing) ringWidth else 0.dp,
                color = if (showSelectedRing) ringColor else Color.Transparent,
                shape = CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(7.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(drawableRes),
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .alpha(iconAlpha),
            contentScale = ContentScale.Fit,
            colorFilter = if (drawableRes == R.drawable.canvas_action_text) {
                ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
            } else {
                null
            }
        )
    }
}

@Composable
private fun ColorDotButton(
    colorArgb: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .size(47.dp)
            .shadow(
                elevation = 12.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color(0x1A3D3D3D),
                spotColor = Color(0x1A3D3D3D)
            )
            .clip(CircleShape)
            .clickable(onClick = onClick),
        color = Color(colorArgb),
        shape = CircleShape
    ) {}
}

@Composable
private fun BrushWidthButton(
    width: Float,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .size(47.dp)
            .shadow(
                elevation = 12.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color(0x1A3D3D3D),
                spotColor = Color(0x1A3D3D3D)
            )
            .clip(CircleShape)
            .clickable(onClick = onClick),
        color = MaterialTheme.mulberryAppColors.softSurfaceStrong,
        shape = CircleShape
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = ((width - DrawingDefaults.MIN_WIDTH) / (DrawingDefaults.MAX_WIDTH - DrawingDefaults.MIN_WIDTH))
                .coerceIn(0f, 1f)

            val insetPx = 7.dp.toPx()
            val ringWidthPx = 4.dp.toPx()
            val ringRadiusPx = (size.minDimension / 2f) - insetPx - (ringWidthPx / 2f)

            drawCircle(
                color = accentColor,
                radius = ringRadiusPx,
                style = Stroke(width = ringWidthPx)
            )

            val dotRadiusPx = 4.dp.toPx() + (11.dp.toPx() - 4.dp.toPx()) * t
            drawCircle(color = accentColor, radius = dotRadiusPx)
        }
    }
}

@Composable
private fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    colors: androidx.compose.material3.SliderColors,
    modifier: Modifier = Modifier
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        colors = colors,
        modifier = modifier.vertical()
    )
}

private fun Modifier.vertical(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(
            Constraints(
                minWidth = constraints.minHeight,
                maxWidth = constraints.maxHeight,
                minHeight = constraints.minWidth,
                maxHeight = constraints.maxWidth
            )
        )

        val width = placeable.height.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = placeable.width.coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(width, height) {
            placeable.placeWithLayer(0, 0) {
                rotationZ = -90f
                transformOrigin = TransformOrigin(0f, 0f)
                translationY = height.toFloat()
            }
        }
    }

@Composable
private fun CanvasControlTray(
    uiState: CanvasHomeUiState,
    onBrushWidthChanged: (Float) -> Unit,
    onColorSelected: (Long) -> Unit,
    onUndoRequested: () -> Unit,
    onRedoRequested: () -> Unit,
    onEraserToggle: () -> Unit,
    onTextToggle: () -> Unit,
    onClearRequested: () -> Unit
) {
    var showWidthPicker by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }

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
            .background(MaterialTheme.mulberryAppColors.softSurface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box {
            BrushWidthButton(
                width = uiState.toolState.selectedWidth,
                accentColor = Color(uiState.toolState.selectedColorArgb),
                onClick = {
                    if (uiState.toolState.activeTool == DrawingTool.DRAW) {
                        showWidthPicker = true
                        showColorPicker = false
                    }
                },
                modifier = Modifier.testTag(TestTags.BRUSH_WIDTH_BUTTON)
            )

            DropdownMenu(
                expanded = showWidthPicker,
                onDismissRequest = { showWidthPicker = false },
                containerColor = MaterialTheme.mulberryAppColors.softSurface,
                modifier = Modifier.width(72.dp),
                properties = PopupProperties(focusable = true)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .height(220.dp)
                            .width(52.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        VerticalSlider(
                            value = uiState.toolState.selectedWidth,
                            onValueChange = onBrushWidthChanged,
                            valueRange = DrawingDefaults.MIN_WIDTH..DrawingDefaults.MAX_WIDTH,
                            colors = SliderDefaults.colors(
                                thumbColor = MulberryPrimary,
                                activeTrackColor = MulberryPrimary,
                                inactiveTrackColor = MaterialTheme.mulberryAppColors.softSurfaceAlt
                            ),
                            modifier = Modifier
                                .testTag(TestTags.BRUSH_WIDTH_SLIDER)
                                .fillMaxSize()
                        )
                    }
                }
            }
        }

        Box {
            ColorDotButton(
                colorArgb = uiState.toolState.selectedColorArgb,
                onClick = {
                    showColorPicker = true
                    showWidthPicker = false
                }
            )

            DropdownMenu(
                expanded = showColorPicker,
                onDismissRequest = { showColorPicker = false },
                containerColor = MaterialTheme.mulberryAppColors.softSurface,
                properties = PopupProperties(focusable = true)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    uiState.palette
                        .take(16)
                        .chunked(4)
                        .forEach { rowColors ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                rowColors.forEach { color ->
                                    ColorSwatch(
                                        colorArgb = color,
                                        isSelected = color == uiState.toolState.selectedColorArgb &&
                                            (uiState.toolState.activeTool == DrawingTool.DRAW ||
                                                uiState.toolState.activeTool == DrawingTool.TEXT),
                                        onClick = {
                                            onColorSelected(color)
                                            showColorPicker = false
                                        },
                                        size = 44.dp
                                    )
                                }
                            }
                        }
                }
            }
        }

        CanvasActionButton(
            drawableRes = R.drawable.canvas_action_undo,
            contentDescription = stringResource(R.string.home_canvas_undo_content_description),
            selected = true,
            showSelectedRing = false,
            dimWhenNotSelected = false,
            enabled = uiState.canUndo,
            onClick = onUndoRequested,
            modifier = Modifier.testTag(TestTags.UNDO_BUTTON)
        )
        CanvasActionButton(
            drawableRes = R.drawable.canvas_action_redo,
            contentDescription = stringResource(R.string.home_canvas_redo_content_description),
            selected = true,
            showSelectedRing = false,
            dimWhenNotSelected = false,
            enabled = uiState.canRedo,
            onClick = onRedoRequested,
            modifier = Modifier.testTag(TestTags.REDO_BUTTON)
        )
        CanvasActionButton(
            drawableRes = R.drawable.canvas_action_erase,
            contentDescription = stringResource(R.string.home_canvas_erase_content_description),
            selected = uiState.toolState.activeTool == DrawingTool.ERASE,
            onClick = onEraserToggle,
            modifier = Modifier.testTag(TestTags.ERASER_BUTTON)
        )
        CanvasActionButton(
            drawableRes = R.drawable.canvas_action_text,
            contentDescription = stringResource(R.string.home_canvas_text_content_description),
            selected = uiState.toolState.activeTool == DrawingTool.TEXT,
            onClick = onTextToggle,
            modifier = Modifier.testTag(TestTags.TEXT_BUTTON)
        )
        CanvasActionButton(
            drawableRes = R.drawable.canvas_action_clear,
            contentDescription = stringResource(R.string.home_canvas_clear_content_description),
            selected = true,
            showSelectedRing = false,
            dimWhenNotSelected = false,
            onClick = onClearRequested,
            modifier = Modifier.testTag(TestTags.CLEAR_BUTTON)
        )
    }
}

@Composable
private fun LockScreenHomePane(
    uiState: CanvasHomeUiState,
    presets: List<WallpaperPreset>,
    onSetUpLockScreen: () -> Unit,
    onViewMoreWallpapers: () -> Unit,
    onUploadFromGallery: () -> Unit,
    onPresetSelected: (Int) -> Unit,
    onRemoteWallpaperSelected: (RemoteWallpaper) -> Unit
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

            val isSelectedOnHome = uiState.wallpaperStatus.isWallpaperSelectedOnHome
            val isSelectedOnLock = uiState.wallpaperStatus.isWallpaperSelectedOnLock
            val isSelectedOnBoth = isSelectedOnHome && isSelectedOnLock

            if (!isSelectedOnBoth) {
                val setupCtaResId =
                    if (!isSelectedOnLock) {
                        R.string.onboarding_wallpaper_setup_button
                    } else {
                        R.string.wallpaper_setup_set_both_lock_home
                    }

                val statusResId = when {
                    isSelectedOnHome -> R.string.wallpaper_setup_status_home
                    isSelectedOnLock -> R.string.wallpaper_setup_status_lock
                    else -> R.string.wallpaper_setup_status_none
                }
                val statusColor = if (isSelectedOnHome || isSelectedOnLock) {
                    com.subhajit.mulberry.ui.theme.MulberrySuccess
                } else {
                    com.subhajit.mulberry.ui.theme.MulberryError
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    WallpaperPrimaryButton(
                        text = stringResource(setupCtaResId),
                        onClick = onSetUpLockScreen,
                        enabled = !uiState.isWallpaperBusy,
                        modifier = Modifier.testTag(TestTags.HOME_OPEN_LOCKSCREEN_BUTTON)
                    )

                    Text(
                        text = stringResource(statusResId),
                        color = statusColor,
                        fontFamily = PoppinsFontFamily,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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
                remoteWallpapers = uiState.recentRemoteWallpapers,
                presets = presets,
                selectedPresetResId = uiState.selectedWallpaperPresetResId,
                selectedRemoteWallpaperId = uiState.selectedRemoteWallpaperId,
                applyingRemoteWallpaperId = uiState.applyingRemoteWallpaperId,
                onUploadFromGallery = onUploadFromGallery,
                onPresetSelected = onPresetSelected,
                onRemoteWallpaperSelected = onRemoteWallpaperSelected,
                onViewMoreWallpapers = onViewMoreWallpapers
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
                modifier = Modifier
                    .testTag(TestTags.CLEAR_CONFIRM_BUTTON)
                    .mulberryTapScale()
            ) {
                Text(stringResource(R.string.home_clear_canvas_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.mulberryTapScale()) {
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
            .border(borderWidth, MaterialTheme.mulberryAppColors.softBorder, CircleShape)
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
            .border(8.dp, MaterialTheme.mulberryAppColors.softBorder, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.mulberryAppColors.softSurface),
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
            .background(MaterialTheme.colorScheme.surface),
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
    val inactiveColor = MaterialTheme.mulberryAppColors.iconMuted
    val itemColor = if (selected) activeColor else inactiveColor
    val label = when (tab) {
        MainAppTab.Canvas -> stringResource(R.string.home_tab_canvas)
        MainAppTab.LockScreen -> stringResource(R.string.home_tab_lockscreen)
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(7.dp))
            .background(
                if (selected) MaterialTheme.mulberryAppColors.softSurfaceSelected else Color.Transparent
            )
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
    onShareInviteClicked: () -> Unit,
    onPartnerDetailsRequested: () -> Unit
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
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 19.dp)
                    .size(width = 44.dp, height = 4.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .background(MaterialTheme.mulberryAppColors.dragHandle)
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
                color = MaterialTheme.colorScheme.onSurface,
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
                color = MaterialTheme.colorScheme.onSurface,
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
                    .height(50.dp)
                    .mulberryTapScale(enabled = !inviteSheet.isLoading),
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
            Spacer(modifier = Modifier.height(10.dp))
            TextButton(onClick = onPartnerDetailsRequested, modifier = Modifier.mulberryTapScale()) {
                Text(
                    text = "Change partner info",
                    color = MulberryPrimary,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PartnerDetailsBottomSheet(
    form: PartnerDetailsFormUiState,
    onPartnerNameChanged: (String) -> Unit,
    onAnniversaryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(sheetState) {
        sheetState.expand()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Partner details",
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = PoppinsFontFamily,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            BareTextInput(
                label = "Partner name",
                placeholder = "Enter partner name",
                value = form.partnerDisplayName,
                onValueChange = onPartnerNameChanged,
                keyboardType = KeyboardType.Text
            )
            BareTextInput(
                label = "Relationship anniversary (DD-MM-YYYY)",
                value = form.anniversaryDate,
                onValueChange = onAnniversaryChanged,
                keyboardType = KeyboardType.Number,
                useDatePlaceholderStyle = true
            )
            form.errorMessage?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 12.sp
                )
            }
            form.nextUpdateAt?.let { nextUpdateAt ->
                Text(
                    text = "Editable again at $nextUpdateAt",
                    color = MaterialTheme.mulberryAppColors.mutedText,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
            Button(
                onClick = onSubmit,
                enabled = form.canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .mulberryTapScale(enabled = form.canSubmit),
                shape = RoundedCornerShape(15.38.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MulberryPrimary,
                    disabledContainerColor = MulberryPrimary.copy(alpha = 0.45f)
                )
            ) {
                Text(
                    text = if (form.isSaving) "Saving..." else "Save and create invite",
                    color = Color.White,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun BareTextInput(
    label: String,
    placeholder: String = "",
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    useDatePlaceholderStyle: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = PoppinsFontFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        val placeholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.34f)
        val inputColor = MaterialTheme.colorScheme.onSurface
        val inputModifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.mulberryAppColors.inputSurface)
            .padding(horizontal = 14.dp, vertical = 14.dp)
        val inputTextStyle = TextStyle(
            color = inputColor,
            fontFamily = PoppinsFontFamily,
            fontSize = 16.sp
        )
        val inputKeyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Next
        )
        if (useDatePlaceholderStyle) {
            BasicTextField(
                value = TextFieldValue(
                    text = value,
                    selection = TextRange(value.anniversaryDateCursorOffset())
                ),
                onValueChange = { onValueChange(it.text) },
                singleLine = true,
                textStyle = inputTextStyle,
                keyboardOptions = inputKeyboardOptions,
                visualTransformation = DatePlaceholderVisualTransformation(
                    inputColor = inputColor,
                    placeholderColor = placeholderColor
                ),
                cursorBrush = SolidColor(inputColor),
                modifier = inputModifier
            )
        } else {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = inputTextStyle,
                keyboardOptions = inputKeyboardOptions,
                visualTransformation = VisualTransformation.None,
                cursorBrush = SolidColor(inputColor),
                modifier = inputModifier,
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty() && placeholder.isNotBlank()) {
                            Text(
                                text = placeholder,
                                color = placeholderColor,
                                fontFamily = PoppinsFontFamily,
                                fontSize = 16.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
    }
}

private fun String.anniversaryDateCursorOffset(): Int {
    val digitCount = count(Char::isDigit)
    if (digitCount == 0) return 0
    val editableIndexes = listOf(0, 1, 3, 4, 6, 7, 8, 9)
    return (editableIndexes[(digitCount - 1).coerceAtMost(editableIndexes.lastIndex)] + 1)
        .coerceAtMost(length)
}

private class DatePlaceholderVisualTransformation(
    private val inputColor: Color,
    private val placeholderColor: Color
) : VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): TransformedText {
        val transformed = buildAnnotatedString {
            text.text.forEachIndexed { index, character ->
                val color = if (
                    character.isAnniversaryPlaceholderCharacter() ||
                    character.isPendingAnniversaryHyphen(text.text, index)
                ) {
                    placeholderColor
                } else {
                    inputColor
                }
                withStyle(SpanStyle(color = color)) {
                    append(character)
                }
            }
        }
        return TransformedText(transformed, OffsetMapping.Identity)
    }
}

private fun Char.isAnniversaryPlaceholderCharacter(): Boolean =
    this == 'D' || this == 'M' || this == 'Y'

private fun Char.isPendingAnniversaryHyphen(text: String, index: Int): Boolean =
    this == '-' && text.getOrNull(index + 1)?.isDigit() != true

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JoinCodeBottomSheet(
    joinCode: JoinCodeUiState,
    onCodeChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(sheetState) {
        sheetState.expand()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(TestTags.HOME_JOIN_CODE_SHEET),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { HomeSheetDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.home_join_sheet_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = PoppinsFontFamily,
                fontSize = 20.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "(•••) •••",
                color = MulberryPrimary,
                fontFamily = PoppinsFontFamily,
                fontSize = 20.sp,
                lineHeight = 27.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(18.dp))
            HomeInviteCodeInput(
                code = joinCode.code,
                onCodeChanged = onCodeChanged,
                onSubmit = {
                    if (joinCode.code.length == 6 && !joinCode.isSubmitting) {
                        onSubmit()
                    }
                }
            )
            joinCode.errorMessage?.let { error ->
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
            Spacer(modifier = Modifier.height(18.dp))
            Button(
                onClick = onSubmit,
                enabled = joinCode.code.length == 6 && !joinCode.isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag(TestTags.HOME_JOIN_CODE_SUBMIT_BUTTON)
                    .mulberryTapScale(enabled = joinCode.code.length == 6 && !joinCode.isSubmitting),
                shape = RoundedCornerShape(15.38.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MulberryPrimary,
                    disabledContainerColor = MulberryPrimary.copy(alpha = 0.45f),
                    disabledContentColor = Color.White.copy(alpha = 0.80f)
                )
            ) {
                Text(
                    text = if (joinCode.isSubmitting) {
                        stringResource(R.string.invite_acceptance_connecting)
                    } else {
                        stringResource(R.string.home_join_sheet_cta)
                    },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingConfirmedBottomSheet(
    userName: String?,
    partnerName: String?,
    confirmation: PairingConfirmationUiState,
    onDismiss: () -> Unit,
    onDisconnect: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val displayName = userName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.home_default_user_name)
    val resolvedPartnerName = partnerName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.app_name)

    LaunchedEffect(sheetState) {
        sheetState.expand()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(TestTags.HOME_PAIRING_CONFIRMED_SHEET),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { HomeSheetDragHandle() }
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 22.dp)
                    .zIndex(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.invite_acceptance_welcome, displayName),
                    color = MulberryPrimary,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.home_pairing_confirmed_title, resolvedPartnerName),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(18.dp))
                Image(
                    painter = painterResource(R.drawable.invite_acceptance_couple),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(236.dp)
                )
                Spacer(modifier = Modifier.height(18.dp))
                Button(
                    onClick = onDismiss,
                    enabled = !confirmation.isDisconnecting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .mulberryTapScale(enabled = !confirmation.isDisconnecting),
                    shape = RoundedCornerShape(15.38.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MulberryPrimary,
                        disabledContainerColor = MulberryPrimary.copy(alpha = 0.45f)
                    )
                ) {
                    Text(
                        text = stringResource(R.string.invite_acceptance_continue),
                        color = Color.White,
                        fontFamily = PoppinsFontFamily,
                        fontSize = 18.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = buildAnnotatedString {
                        append(stringResource(R.string.invite_acceptance_wrong_partner))
                        append(" ")
                        withStyle(SpanStyle(color = MulberryPrimary, fontWeight = FontWeight.SemiBold)) {
                            append(
                                if (confirmation.isDisconnecting) {
                                    stringResource(R.string.home_pairing_disconnect_progress)
                                } else {
                                    stringResource(R.string.invite_acceptance_disconnect)
                                }
                            )
                        }
                    },
                    color = MaterialTheme.mulberryAppColors.subtleText,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = !confirmation.isDisconnecting,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDisconnect
                        )
                        .testTag(TestTags.HOME_PAIRING_CONFIRMED_DISCONNECT_BUTTON)
                )
                confirmation.errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = PoppinsFontFamily,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            PairingConfetti(
                modifier = Modifier
                    .matchParentSize()
                    .zIndex(2f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboundInvitePairedBottomSheet(
    partnerName: String?,
    confirmation: PairingConfirmationUiState,
    onKeepPairing: () -> Unit,
    onDisconnectAndSwitch: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(sheetState) {
        sheetState.expand()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { HomeSheetDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.home_inbound_invite_paired_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = PoppinsFontFamily,
                fontSize = 20.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (partnerName.isNullOrBlank()) {
                    stringResource(R.string.home_inbound_invite_paired_body_no_partner)
                } else {
                    stringResource(R.string.home_inbound_invite_paired_body_with_partner, partnerName)
                },
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f),
                fontFamily = PoppinsFontFamily,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )

            confirmation.errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = onDisconnectAndSwitch,
                enabled = !confirmation.isDisconnecting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .mulberryTapScale(enabled = !confirmation.isDisconnecting),
                shape = RoundedCornerShape(15.38.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MulberryPrimary,
                    disabledContainerColor = MulberryPrimary.copy(alpha = 0.45f),
                    disabledContentColor = Color.White.copy(alpha = 0.80f)
                )
            ) {
                Text(
                    text = if (confirmation.isDisconnecting) {
                        stringResource(R.string.home_pairing_disconnect_progress)
                    } else {
                        stringResource(R.string.home_inbound_invite_disconnect_switch)
                    },
                    color = Color.White,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onKeepPairing,
                enabled = !confirmation.isDisconnecting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .mulberryTapScale(enabled = !confirmation.isDisconnecting),
                shape = RoundedCornerShape(15.38.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(
                    text = stringResource(R.string.home_inbound_invite_keep_pairing),
                    fontFamily = PoppinsFontFamily,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun PairingConfetti(modifier: Modifier = Modifier) {
    val parties = remember {
        val colors = listOf(
            0xFFB31329.toInt(),
            0xFFFF4D6D.toInt(),
            0xFFFFB000.toInt(),
            0xFFFFE066.toInt(),
            0xFFFF7A3D.toInt(),
            0xFF00A878.toInt(),
            0xFF6C5CE7.toInt(),
            0xFFFFF4F5.toInt()
        )
        val sizes = listOf(KonfettiSize(6), KonfettiSize(9), KonfettiSize(12))
        val shapes = listOf(Shape.Square, Shape.Circle, Shape.Rectangle(0.35f))
        listOf(
            Party(
                angle = Angle.RIGHT - 12,
                spread = Spread.WIDE,
                speed = 22f,
                maxSpeed = 44f,
                damping = 0.90f,
                size = sizes,
                shapes = shapes,
                colors = colors,
                timeToLive = 3_200L,
                position = Position.Relative(0.0, 0.42),
                emitter = Emitter(duration = 360, TimeUnit.MILLISECONDS).max(70)
            ),
            Party(
                angle = Angle.LEFT + 12,
                spread = Spread.WIDE,
                speed = 22f,
                maxSpeed = 44f,
                damping = 0.90f,
                size = sizes,
                shapes = shapes,
                colors = colors,
                timeToLive = 3_200L,
                position = Position.Relative(1.0, 0.42),
                emitter = Emitter(duration = 360, TimeUnit.MILLISECONDS).max(70)
            )
        )
    }

    KonfettiView(
        modifier = modifier,
        parties = parties
    )
}

@Composable
private fun HomeSheetDragHandle() {
    Box(
        modifier = Modifier
            .padding(top = 8.dp, bottom = 19.dp)
            .size(width = 44.dp, height = 4.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(MaterialTheme.mulberryAppColors.dragHandle)
    )
}

@Composable
private fun HomeInviteCodeInput(
    code: String,
    onCodeChanged: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var showCursor by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(530)
            showCursor = !showCursor
        }
    }

    BasicTextField(
        value = code,
        onValueChange = { value -> onCodeChanged(value.filter(Char::isDigit).take(6)) },
        singleLine = true,
        textStyle = TextStyle(color = Color.Transparent),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        decorationBox = { innerTextField ->
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(6) { index ->
                        HomeInviteCodeCell(
                            digit = code.getOrNull(index),
                            isActive = index == code.length.coerceAtMost(5) && code.length < 6,
                            showCursor = showCursor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(1.dp)
                        .alpha(0f)
                ) {
                    innerTextField()
                }
            }
        }
    )
}

@Composable
private fun HomeInviteCodeCell(
    digit: Char?,
    isActive: Boolean,
    showCursor: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .aspectRatio(0.84f)
            .clip(RoundedCornerShape(15.38.dp))
            .background(MaterialTheme.mulberryAppColors.inputSurface),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isActive) {
            Box(
                modifier = Modifier
                    .size(width = 2.dp, height = 32.dp)
                    .alpha(if (showCursor) 1f else 0f)
                    .background(MulberryPrimary)
            )
        }
        Text(
            text = digit?.toString() ?: "0",
            color = if (digit == null) {
                MaterialTheme.mulberryAppColors.subtleText
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontFamily = PoppinsFontFamily,
            fontSize = 30.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Medium
        )
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
                    .background(MaterialTheme.mulberryAppColors.inputSurface),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = digit.toString(),
                    color = MaterialTheme.colorScheme.onSurface,
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
        onClearConfirmed = viewModel::onClearConfirmed,
        onUndoRequested = viewModel::onUndoRequested,
        onRedoRequested = viewModel::onRedoRequested
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
    onClearConfirmed: () -> Unit,
    onUndoRequested: () -> Unit,
    onRedoRequested: () -> Unit
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
            val undoEnabled = uiState.canUndo
            val redoEnabled = uiState.canRedo
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FloatingActionButton(
                    onClick = { if (undoEnabled) onUndoRequested() },
                    modifier = Modifier
                        .testTag(TestTags.UNDO_BUTTON)
                        .alpha(if (undoEnabled) 1f else 0.45f),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Text("Undo")
                }
                FloatingActionButton(
                    onClick = { if (redoEnabled) onRedoRequested() },
                    modifier = Modifier
                        .testTag(TestTags.REDO_BUTTON)
                        .alpha(if (redoEnabled) 1f else 0.45f),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Text("Redo")
                }
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
                                    (uiState.toolState.activeTool == DrawingTool.DRAW ||
                                        uiState.toolState.activeTool == DrawingTool.TEXT),
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
