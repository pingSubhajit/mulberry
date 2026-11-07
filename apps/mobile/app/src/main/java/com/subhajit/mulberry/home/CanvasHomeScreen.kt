package com.subhajit.mulberry.home

import androidx.compose.ui.unit.lerp
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.animation.core.animateFloatAsState
import kotlin.math.roundToInt
import com.subhajit.mulberry.analytics.OnboardingStep
import com.subhajit.mulberry.analytics.TrackOnboardingStep
import android.content.Intent
import android.os.SystemClock
import com.subhajit.mulberry.BuildConfig
import com.subhajit.mulberry.drawing.geometry.normalizeToSurface
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.activity.compose.BackHandler
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import com.subhajit.mulberry.share.ShareSheet
import com.subhajit.mulberry.app.shortcut.AppShortcutAction
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.core.ui.mulberryTapScale
import com.subhajit.mulberry.data.bootstrap.AuthStatus
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.drawing.model.DrawingDefaults
import com.subhajit.mulberry.drawing.model.DrawingTool
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.review.InAppReviewLauncher
import com.subhajit.mulberry.reactions.ReactionType
import com.subhajit.mulberry.reactions.ReactionGifUiCache
import com.subhajit.mulberry.reactions.ReactionGif
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.VirgilFontFamily
import com.subhajit.mulberry.ui.animation.bobbing
import com.subhajit.mulberry.ui.animation.rememberBobbingPhase
import com.subhajit.mulberry.ui.animation.mulberrySelectionPop
import com.subhajit.mulberry.ui.animation.TwoSidedConfettiBurst
import com.subhajit.mulberry.ui.animation.rememberSystemAnimationsEnabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.subhajit.mulberry.streak.boltHop
import com.subhajit.mulberry.streak.RollingNumber
import com.subhajit.mulberry.ui.theme.mulberryAppColors
import com.subhajit.mulberry.wallpaper.RemoteWallpaper
import com.subhajit.mulberry.wallpaper.WallpaperIntentFactory
import com.subhajit.mulberry.wallpaper.WallpaperPreset
import com.subhajit.mulberry.wallpaper.ui.WallpaperBackgroundSelectionSection
import com.subhajit.mulberry.wallpaper.ui.WallpaperLockScreenPreview
import com.subhajit.mulberry.wallpaper.ui.WallpaperPrimaryButtonWithStatusBadge
import com.subhajit.mulberry.wallpaper.ui.WallpaperSetupStatusBadgeStyle
import com.subhajit.mulberry.wallpaper.ui.WallpaperWhyDoIDoThisLink
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import com.subhajit.mulberry.core.ui.ColourNames
import com.subhajit.mulberry.core.ui.MulberryHaptic
import com.subhajit.mulberry.core.ui.isLargeText
import com.subhajit.mulberry.core.ui.rememberMulberryHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.sin

private val LocalStreakLevelUpBannerClick = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
fun CanvasHomeRoute(
    shortcutAction: AppShortcutAction? = null,
    onShortcutActionHandled: (AppShortcutAction) -> Unit = {},
    onNavigateToCanvas: () -> Unit,
    onNavigateToWallpaperStatus: () -> Unit,
    onNavigateToWallpaperCatalog: () -> Unit,
    onNavigateToWallpaperHelp: () -> Unit,
    onNavigateToPairingHelp: () -> Unit,
    onNavigateToStreak: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToPairingHub: () -> Unit,
    /** The streak screen just closed: the toolbar bolt takes the streak back, once. */
    streakJustClosed: Boolean = false,
    onStreakClosingSeen: () -> Unit = {},
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
	        streakJustClosed = streakJustClosed,
	        onStreakClosingSeen = onStreakClosingSeen,
	        wallpaperPresets = viewModel.wallpaperPresets,
            onCanvasTabVisible = viewModel::onCanvasTabVisible,
            onBrushToolGuideDismissed = viewModel::onBrushToolGuideDismissed,
            onStreakLevelUpBannerClicked = viewModel::onStreakLevelUpBannerClicked,
		        onNavigateToWallpaperStatus = onNavigateToWallpaperStatus,
            onNavigateToWallpaperHelp = onNavigateToWallpaperHelp,
            onNavigateToPairingHelp = onNavigateToPairingHelp,
	        onNavigateToStreak = onNavigateToStreak,
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
	        onSetUpWallpaper = viewModel::onSetUpWallpaperClicked,
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
        onCanvasEraseDrag = viewModel::onCanvasEraseDrag,
        onCanvasViewportChanged = viewModel::onCanvasViewportChanged,
        onColorSelected = viewModel::onColorSelected,
        onEyedropperColorCommitted = viewModel::onEyedropperColorCommitted,
        onBrushWidthChanged = viewModel::onBrushWidthChanged,
        onBrushToggle = viewModel::onBrushToggle,
        onEraserToggle = viewModel::onEraserToggle,
        onTextToggle = viewModel::onTextToggle,
        onStickerToggle = viewModel::onStickerToggle,
        onStickerPackSelected = viewModel::onStickerPackSelected,
        stickerAssetStore = viewModel.stickerAssetStore,
        photoStore = viewModel.photoStore,
        onCameraToolSelected = viewModel::onCameraToolSelected,
        onPhotoElementAdded = viewModel::onPhotoElementAdded,
        onPhotoElementUpdated = viewModel::onPhotoElementUpdated,
        onPhotoElementDeleted = viewModel::onPhotoElementDeleted,
        onPhotoCancelled = viewModel::onPhotoCancelled,
        onTextElementAdded = viewModel::onTextElementAdded,
        onTextElementUpdated = viewModel::onTextElementUpdated,
        onTextElementDeleted = viewModel::onTextElementDeleted,
	        onStickerElementAdded = viewModel::onStickerElementAdded,
	        onStickerElementUpdated = viewModel::onStickerElementUpdated,
	        onStickerElementDeleted = viewModel::onStickerElementDeleted,
	        onStickerUsed = viewModel::onStickerUsed,
	        onClearRequested = viewModel::onClearRequested,
        onClearDismissed = viewModel::onClearDismissed,
        onClearConfirmed = viewModel::onClearConfirmed,
        onUndoRequested = viewModel::onUndoRequested,
        onRedoRequested = viewModel::onRedoRequested,
        onSendReaction = viewModel::trySendReaction,
        onShortcutActionHandled = onShortcutActionHandled
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
	private fun CanvasHomeScreen(
		    uiState: CanvasHomeUiState,
		    shortcutAction: AppShortcutAction?,
		    streakJustClosed: Boolean,
		    onStreakClosingSeen: () -> Unit,
		    wallpaperPresets: List<WallpaperPreset>,
            onCanvasTabVisible: () -> Unit,
            onBrushToolGuideDismissed: () -> Unit,
            onStreakLevelUpBannerClicked: () -> Unit,
			    onNavigateToWallpaperStatus: () -> Unit,
        onNavigateToWallpaperHelp: () -> Unit,
        onNavigateToPairingHelp: () -> Unit,
	    onNavigateToStreak: () -> Unit,
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
	    onSetUpWallpaper: () -> Unit,
    onViewMoreWallpapers: () -> Unit,
    onUploadWallpaperBackground: () -> Unit,
    onWallpaperPresetSelected: (WallpaperPreset) -> Unit,
    onRemoteWallpaperSelected: (RemoteWallpaper) -> Unit,
    onCanvasPress: (StrokePoint) -> Unit,
    onCanvasDrag: (StrokePoint) -> Unit,
    onCanvasRelease: () -> Unit,
	    onCanvasTap: (StrokePoint) -> Unit,
	    onCanvasEraseDrag: (StrokePoint, StrokePoint) -> Unit,
	    onCanvasViewportChanged: (Int, Int) -> Unit,
	    onColorSelected: (Long) -> Unit,
        onEyedropperColorCommitted: (Long) -> Unit,
	    onBrushWidthChanged: (Float) -> Unit,
	    onBrushToggle: () -> Unit,
	    onEraserToggle: () -> Unit,
	    onTextToggle: () -> Unit,
    onStickerToggle: () -> Unit,
    onStickerPackSelected: (String, Int) -> Unit,
    stickerAssetStore: com.subhajit.mulberry.stickers.StickerAssetStore,
    photoStore: com.subhajit.mulberry.photos.CanvasPhotoStore,
    onCameraToolSelected: () -> Unit,
    onPhotoElementAdded: (com.subhajit.mulberry.drawing.model.CanvasPhotoElement) -> Unit,
    onPhotoElementUpdated: (com.subhajit.mulberry.drawing.model.CanvasPhotoElement) -> Unit,
    onPhotoElementDeleted: (String) -> Unit,
    onPhotoCancelled: () -> Unit,
    onTextElementAdded: (com.subhajit.mulberry.drawing.model.CanvasTextElement) -> Unit,
    onTextElementUpdated: (com.subhajit.mulberry.drawing.model.CanvasTextElement) -> Unit,
    onTextElementDeleted: (String) -> Unit,
	    onStickerElementAdded: (com.subhajit.mulberry.drawing.model.CanvasStickerElement) -> Unit,
	    onStickerElementUpdated: (com.subhajit.mulberry.drawing.model.CanvasStickerElement) -> Unit,
	    onStickerElementDeleted: (String) -> Unit,
	    onStickerUsed: (StickerSelection) -> Unit,
	    onClearRequested: () -> Unit,
    onClearDismissed: () -> Unit,
	    onClearConfirmed: () -> Unit,
	    onUndoRequested: () -> Unit,
	    onRedoRequested: () -> Unit,
	    onSendReaction: suspend (ReactionType) -> Boolean,
	    onShortcutActionHandled: (AppShortcutAction) -> Unit
		) {
    // The wallpapers open over the canvas from the top bar, as iOS pushes them.
    var wallpaperOpen by rememberSaveable { mutableStateOf(false) }
    // The share screen, from the share button on the canvas.
    var shareOpen by rememberSaveable { mutableStateOf(false) }
	    var textEditorSession by remember { mutableStateOf<CanvasTextEditorSession?>(null) }
	    var stickerEditorSession by remember { mutableStateOf<CanvasStickerEditorSession?>(null) }
	    var photoSession by remember { mutableStateOf<CanvasPhotoSession?>(null) }
	    var canvasBoundsInRoot by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
	    var pendingNewStickerCenter by remember { mutableStateOf<StrokePoint?>(null) }
        var allowToolTapPlacement by remember { mutableStateOf(false) }
        var autoOpenOnNextToolSelect by remember { mutableStateOf<DrawingTool?>(null) }
        var awaitingInitialDismissForTool by remember { mutableStateOf<DrawingTool?>(null) }
        var openedInitialEditorForTool by remember { mutableStateOf<DrawingTool?>(null) }
    val isHomeReady =
        uiState.bootstrapState.authStatus == AuthStatus.SIGNED_IN &&
            uiState.bootstrapState.hasCompletedOnboarding

    LaunchedEffect(uiState.bootstrapState.pairingStatus) {
        if (uiState.bootstrapState.pairingStatus != PairingStatus.PAIRED) shareOpen = false
    }

    LaunchedEffect(wallpaperOpen, uiState.bootstrapState.pairingStatus) {
        if (!wallpaperOpen && uiState.bootstrapState.pairingStatus == PairingStatus.PAIRED) {
            onCanvasTabVisible()
        }
    }

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
                wallpaperOpen = true
                onShortcutActionHandled(action)
            }

	            AppShortcutAction.OpenCanvas -> {
	                wallpaperOpen = false
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

            AppShortcutAction.ShowPartnerVisibilitySheet -> {
                onShortcutActionHandled(action)
            }
        }
    }

    uiState.pairingSheetMode.onboardingStep()?.let { step ->
        TrackOnboardingStep(step, keyPrefix = "home_sheet")
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
            onHelpRequested = onNavigateToPairingHelp,
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
	        val editorOpen = textEditorSession != null || stickerEditorSession != null || photoSession != null
            // The Camera tool takes up the camera at once, as on iPhone; chosen again, it puts it down.
            val photoHooks = CanvasPhotoHooks(
                photoStore = photoStore,
                onCameraToggle = {
                    if (!editorOpen && uiState.toolState.activeTool == DrawingTool.PHOTO) {
                        onBrushToggle()
                    } else if (!editorOpen) {
                        // The camera opens with the tool, so nothing waits for a first editor.
                        autoOpenOnNextToolSelect = null
                        awaitingInitialDismissForTool = null
                        openedInitialEditorForTool = null
                        onCameraToolSelected()
                        photoSession = CanvasPhotoSession.new()
                    }
                },
                onPhotoElementUpdated = onPhotoElementUpdated,
                onPhotoElementDeleted = onPhotoElementDeleted,
                onPhotoEditRequested = { element ->
                    textEditorSession = null
                    stickerEditorSession = null
                    photoSession = CanvasPhotoSession.edit(element)
                },
                onNewPhotoRequested = {
                    textEditorSession = null
                    stickerEditorSession = null
                    photoSession = CanvasPhotoSession.new()
                },
                onCanvasBoundsChanged = { bounds -> canvasBoundsInRoot = bounds },
                hiddenElementId = photoSession?.element?.id
            )
            val toolSelectText: () -> Unit = {
                if (!editorOpen && uiState.toolState.activeTool == DrawingTool.TEXT) {
                    onTextToggle()
                } else if (!editorOpen) {
                    allowToolTapPlacement = false
                    autoOpenOnNextToolSelect = DrawingTool.TEXT
                    awaitingInitialDismissForTool = DrawingTool.TEXT
                    onTextToggle()
                }
            }
            val toolSelectSticker: () -> Unit = {
                if (!editorOpen && uiState.toolState.activeTool == DrawingTool.STICKER) {
                    onStickerToggle()
                } else if (!editorOpen) {
                    allowToolTapPlacement = false
                    autoOpenOnNextToolSelect = DrawingTool.STICKER
                    awaitingInitialDismissForTool = DrawingTool.STICKER
                    onStickerToggle()
                }
            }

	        LaunchedEffect(
	            pendingNewStickerCenter,
	            uiState.lastUsedSticker,
	            uiState.selectedStickerPack,
	            uiState.stickerPacks,
	            editorOpen,
	            uiState.toolState.activeTool
	        ) {
	            val center = pendingNewStickerCenter ?: return@LaunchedEffect
	            if (editorOpen) return@LaunchedEffect
	            if (uiState.toolState.activeTool != DrawingTool.STICKER) {
	                pendingNewStickerCenter = null
	                return@LaunchedEffect
	            }

	            val lastUsed = uiState.lastUsedSticker
	            val selectedPack = uiState.selectedStickerPack
	            val selectedFirstSticker = selectedPack?.stickers?.firstOrNull()?.let { first ->
	                StickerSelection(selectedPack.packKey, selectedPack.packVersion, first.stickerId)
	            }

	            val selection = lastUsed ?: selectedFirstSticker
	            if (selection != null) {
	                onStickerPackSelected(selection.packKey, selection.packVersion)
	                textEditorSession = null
	                stickerEditorSession = CanvasStickerEditorSession(
	                    element = com.subhajit.mulberry.drawing.model.CanvasStickerElement(
	                        id = java.util.UUID.randomUUID().toString(),
	                        createdAt = System.currentTimeMillis(),
	                        center = center,
	                        rotationRad = 0f,
	                        scale = 0.22f,
	                        packKey = selection.packKey,
	                        packVersion = selection.packVersion,
	                        stickerId = selection.stickerId
	                    ),
	                    isNew = true
	                )
                    openedInitialEditorForTool = DrawingTool.STICKER
	                pendingNewStickerCenter = null
	                return@LaunchedEffect
	            }

	            // No last used sticker and no selected pack; pick the first pack (if present) and
	            // wait for its detail to load, then open with its first sticker.
	            val firstPack = uiState.stickerPacks.firstOrNull()
	            if (firstPack != null) {
	                onStickerPackSelected(firstPack.packKey, firstPack.packVersion)
	            }
	        }

            LaunchedEffect(
                uiState.toolState.activeTool,
                autoOpenOnNextToolSelect,
                editorOpen,
                uiState.toolState.selectedColorArgb
            ) {
                if (editorOpen) return@LaunchedEffect
                val pending = autoOpenOnNextToolSelect ?: return@LaunchedEffect
                if (pending != uiState.toolState.activeTool) return@LaunchedEffect

                autoOpenOnNextToolSelect = null

                val center = StrokePoint(x = 0.5f, y = 0.5f)
                when (pending) {
                    DrawingTool.TEXT -> {
                        val id = java.util.UUID.randomUUID().toString()
                        textEditorSession = CanvasTextEditorSession(
                            element = com.subhajit.mulberry.drawing.model.CanvasTextElement(
                                id = id,
                                text = "",
                                createdAt = System.currentTimeMillis(),
                                center = center,
                                rotationRad = 0f,
                                scale = 1f,
                                boxWidth = 0.7f,
                                colorArgb = uiState.toolState.selectedColorArgb,
                                backgroundPillEnabled = false,
                                font = com.subhajit.mulberry.drawing.model.CanvasTextFont.POPPINS,
                                alignment = com.subhajit.mulberry.drawing.model.CanvasTextAlign.CENTER
                            ),
                            isNew = true
                        )
                        openedInitialEditorForTool = DrawingTool.TEXT
                    }
                    DrawingTool.STICKER -> {
                        pendingNewStickerCenter = center
                    }
                    else -> Unit
                }
            }

            LaunchedEffect(uiState.toolState.activeTool, editorOpen, awaitingInitialDismissForTool) {
                if (editorOpen) return@LaunchedEffect
                val current = ToolTapPlacement(
                    allowsEmptyTap = allowToolTapPlacement,
                    awaitingInitialDismissForTool = awaitingInitialDismissForTool,
                    openedInitialEditorForTool = openedInitialEditorForTool
                )
                val settled = current.settled(uiState.toolState.activeTool)
                allowToolTapPlacement = settled.allowsEmptyTap
                awaitingInitialDismissForTool = settled.awaitingInitialDismissForTool
                openedInitialEditorForTool = settled.openedInitialEditorForTool
            }

        val headerUserName = uiState.bootstrapState.userDisplayName
            ?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.home_default_user_name)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .graphicsLayer {
                    // The editors sit over a blurred, dimmed canvas; the share sheet brings its own scrim.
                    val backdropBlurred = editorOpen
                    if (backdropBlurred && Build.VERSION.SDK_INT >= 31) {
                        renderEffect = RenderEffect.createBlurEffect(
                            18f,
                            18f,
                            Shader.TileMode.CLAMP
                        ).asComposeRenderEffect()
                    } else {
                        renderEffect = null
                    }
                    alpha = if (backdropBlurred) 0.92f else 1f
                }
                .testTag(TestTags.HOME_SCREEN)
        ) {
            CompositionLocalProvider(
                LocalStreakLevelUpBannerClick provides {
                    onStreakLevelUpBannerClicked()
                    onNavigateToStreak()
                }
            ) {
                CanvasHomePane(
                    uiState = uiState,
                    onBrushToolGuideDismissed = onBrushToolGuideDismissed,
                    onInviteRequested = onInviteRequested,
                    onJoinCodeRequested = onJoinCodeRequested,
                    onCanvasPress = onCanvasPress,
                    onCanvasDrag = onCanvasDrag,
                    onCanvasRelease = onCanvasRelease,
                    onCanvasTap = onCanvasTap,
                    onCanvasEraseDrag = onCanvasEraseDrag,
                    onCanvasViewportChanged = onCanvasViewportChanged,
                    onColorSelected = onColorSelected,
                    onEyedropperColorCommitted = onEyedropperColorCommitted,
                    onBrushWidthChanged = onBrushWidthChanged,
                    onBrushToggle = onBrushToggle,
                    onEraserToggle = onEraserToggle,
                    onTextToggle = toolSelectText,
                    onStickerToggle = toolSelectSticker,
                    onStickerPackSelected = onStickerPackSelected,
                    stickerAssetStore = stickerAssetStore,
                    photo = photoHooks,
                    onTextElementAdded = onTextElementAdded,
                    onTextElementUpdated = onTextElementUpdated,
                    onTextElementDeleted = onTextElementDeleted,
                    onStickerElementAdded = onStickerElementAdded,
                    onStickerElementUpdated = onStickerElementUpdated,
                    onStickerElementDeleted = onStickerElementDeleted,
                    onTextEditorRequested = { session ->
                        stickerEditorSession = null
                        textEditorSession = session
                    },
                    onStickerEditorRequested = { session ->
                        textEditorSession = null
                        onStickerPackSelected(session.element.packKey, session.element.packVersion)
                        stickerEditorSession = session
                    },
                    onNewStickerRequestedAt = { center ->
                        pendingNewStickerCenter = center
                    },
                    allowToolTapPlacement = allowToolTapPlacement,
                    onEmptyTapCreationHandled = { allowToolTapPlacement = true },
                    isEditorOpen = editorOpen,
                    onClearRequested = onClearRequested,
                    onUndoRequested = onUndoRequested,
                    onRedoRequested = onRedoRequested,
                    onSendReaction = onSendReaction,
                    onShareRequested = { shareOpen = true }
                )
            }

            CanvasHomeTopBar(
                showStreak = uiState.bootstrapState.pairingStatus == PairingStatus.PAIRED,
                currentStreakDays = uiState.bootstrapState.currentStreakDays,
                streakJustClosed = streakJustClosed,
                onStreakClosingSeen = onStreakClosingSeen,
                userName = headerUserName,
                userPhotoUrl = uiState.bootstrapState.userPhotoUrl,
                onStreakClick = onNavigateToStreak,
                onWallpaperClick = { wallpaperOpen = true },
                onProfileClick = onNavigateToSettings,
                modifier = Modifier.align(Alignment.TopCenter),
                history = if (uiState.bootstrapState.pairingStatus == PairingStatus.PAIRED) {
                    CanvasHistoryControls(
                        canUndo = uiState.canUndo,
                        canRedo = uiState.canRedo,
                        onUndo = onUndoRequested,
                        onRedo = onRedoRequested
                    )
                } else {
                    null
                }
            )
        }

        WallpapersPage(
            visible = wallpaperOpen,
            onBack = { wallpaperOpen = false }
        ) {
            WallpaperHomePane(
                uiState = uiState,
                presets = wallpaperPresets,
                onSetUpWallpaper = onSetUpWallpaper,
                onNavigateToWallpaperHelp = onNavigateToWallpaperHelp,
                onViewMoreWallpapers = onViewMoreWallpapers,
                onUploadFromGallery = onUploadWallpaperBackground,
                onPresetSelected = onWallpaperPresetSelected,
                onRemoteWallpaperSelected = onRemoteWallpaperSelected
            )
        }

        if (shareOpen && uiState.bootstrapState.pairingStatus == PairingStatus.PAIRED) {
            ShareSheet(onDismiss = { shareOpen = false })
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
                    if (session.isNew) {
                        if (updated.text.isNotBlank()) onTextElementAdded(updated)
                    } else {
                        if (updated.text.isBlank()) onTextElementDeleted(updated.id) else onTextElementUpdated(updated)
                    }
                    textEditorSession = null
                },
                onDelete = { elementId ->
                    if (!session.isNew) onTextElementDeleted(elementId)
                    textEditorSession = null
                }
	            )
	        }

	        val stickerSession = stickerEditorSession
	        if (stickerSession != null) {
	            StickerEditorOverlay(
	                session = stickerSession,
	                uiState = uiState,
	                stickerAssetStore = stickerAssetStore,
	                onPackSelected = onStickerPackSelected,
	                onStickerPicked = onStickerUsed,
	                onDone = { updated ->
	                    if (stickerSession.isNew) {
	                        onStickerElementAdded(updated)
	                    } else {
	                        onStickerElementUpdated(updated)
	                    }
	                    onStickerUsed(StickerSelection(updated.packKey, updated.packVersion, updated.stickerId))
	                    stickerEditorSession = null
	                },
	                onDelete = { elementId ->
	                    if (!stickerSession.isNew) {
	                        onStickerElementDeleted(elementId)
	                    }
	                    stickerEditorSession = null
	                }
	            )
	        }

	        val currentPhotoSession = photoSession
	        if (currentPhotoSession != null) {
	            CanvasPhotoFlow(
	                session = currentPhotoSession,
	                canvasBounds = canvasBoundsInRoot,
	                photoStore = photoStore,
	                onDone = { element ->
	                    if (currentPhotoSession.isNew) onPhotoElementAdded(element) else onPhotoElementUpdated(element)
	                    photoSession = null
	                },
	                onDelete = {
	                    currentPhotoSession.element?.let { onPhotoElementDeleted(it.id) }
	                    photoSession = null
	                },
	                onCancel = {
	                    photoSession = null
	                    onPhotoCancelled()
	                }
	            )
	        }
	    }
}

@Composable
private fun StreakLevelUpBanner(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(15.38.dp)

    Row(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFFB31329))
            .mulberryTapScale()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 19.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.ic_streak_level_up_bolt),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(21.dp)
            )
            Text(
                text = text,
                color = Color.White,
                fontFamily = PoppinsFontFamily,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Image(
            painter = painterResource(R.drawable.ic_streak_level_up_chevron),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun CanvasHomePane(
    uiState: CanvasHomeUiState,
    onBrushToolGuideDismissed: () -> Unit,
    onInviteRequested: () -> Unit,
    onJoinCodeRequested: () -> Unit,
    onCanvasPress: (StrokePoint) -> Unit,
    onCanvasDrag: (StrokePoint) -> Unit,
    onCanvasRelease: () -> Unit,
    onCanvasTap: (StrokePoint) -> Unit,
    onCanvasEraseDrag: (StrokePoint, StrokePoint) -> Unit,
    onCanvasViewportChanged: (Int, Int) -> Unit,
    onColorSelected: (Long) -> Unit,
    onEyedropperColorCommitted: (Long) -> Unit,
    onBrushWidthChanged: (Float) -> Unit,
    onBrushToggle: () -> Unit,
    onEraserToggle: () -> Unit,
    onTextToggle: () -> Unit,
    onStickerToggle: () -> Unit,
    onStickerPackSelected: (String, Int) -> Unit,
    stickerAssetStore: com.subhajit.mulberry.stickers.StickerAssetStore,
    photo: CanvasPhotoHooks,
    onTextElementAdded: (com.subhajit.mulberry.drawing.model.CanvasTextElement) -> Unit,
    onTextElementUpdated: (com.subhajit.mulberry.drawing.model.CanvasTextElement) -> Unit,
    onTextElementDeleted: (String) -> Unit,
    onStickerElementAdded: (com.subhajit.mulberry.drawing.model.CanvasStickerElement) -> Unit,
    onStickerElementUpdated: (com.subhajit.mulberry.drawing.model.CanvasStickerElement) -> Unit,
    onStickerElementDeleted: (String) -> Unit,
    onTextEditorRequested: (CanvasTextEditorSession) -> Unit,
    onStickerEditorRequested: (CanvasStickerEditorSession) -> Unit,
    onNewStickerRequestedAt: (StrokePoint) -> Unit,
    allowToolTapPlacement: Boolean,
    onEmptyTapCreationHandled: () -> Unit,
    isEditorOpen: Boolean,
    onClearRequested: () -> Unit,
    onUndoRequested: () -> Unit,
    onRedoRequested: () -> Unit,
    onSendReaction: suspend (ReactionType) -> Boolean,
    onShareRequested: () -> Unit
) {
    val userName = uiState.bootstrapState.userDisplayName
        ?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.home_default_user_name)

    if (uiState.bootstrapState.pairingStatus == PairingStatus.UNPAIRED) {
        TrackOnboardingStep(OnboardingStep.PAIRING_CHOICE, keyPrefix = "home")
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                // Below the top bar, where it sat under the old header.
                .padding(top = CanvasChromeMetrics.HeaderHeight + 132.dp),
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
                text = stringResource(
                    R.string.home_unpaired_body,
                    uiState.bootstrapState.partnerDisplayName?.trim()?.takeIf { it.isNotEmpty() } ?: "your partner"
                ),
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
                    .heightIn(min = 50.dp)
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
                    onBrushToolGuideDismissed = onBrushToolGuideDismissed,
	                onCanvasPress = onCanvasPress,
		            onCanvasDrag = onCanvasDrag,
	            onCanvasRelease = onCanvasRelease,
	            onCanvasTap = onCanvasTap,
	            onCanvasEraseDrag = onCanvasEraseDrag,
	            onCanvasViewportChanged = onCanvasViewportChanged,
	            onColorSelected = onColorSelected,
                onEyedropperColorCommitted = onEyedropperColorCommitted,
	            onBrushWidthChanged = onBrushWidthChanged,
	            onBrushToggle = onBrushToggle,
	            onEraserToggle = onEraserToggle,
	            onTextToggle = onTextToggle,
	            onStickerToggle = onStickerToggle,
	            onStickerPackSelected = onStickerPackSelected,
	            stickerAssetStore = stickerAssetStore,
	            photo = photo,
            onTextElementAdded = onTextElementAdded,
            onTextElementUpdated = onTextElementUpdated,
            onTextElementDeleted = onTextElementDeleted,
	            onStickerElementAdded = onStickerElementAdded,
	            onStickerElementUpdated = onStickerElementUpdated,
	            onStickerElementDeleted = onStickerElementDeleted,
	            onTextEditorRequested = onTextEditorRequested,
	            onStickerEditorRequested = onStickerEditorRequested,
	            onNewStickerRequestedAt = onNewStickerRequestedAt,
                allowToolTapPlacement = allowToolTapPlacement,
                onEmptyTapCreationHandled = onEmptyTapCreationHandled,
	            isEditorOpen = isEditorOpen,
	            onClearRequested = onClearRequested,
	            onUndoRequested = onUndoRequested,
	            onRedoRequested = onRedoRequested,
	            onSendReaction = onSendReaction,
	            onShareRequested = onShareRequested,
	            modifier = Modifier.testTag(TestTags.HOME_OPEN_CANVAS_BUTTON)
	        )
	    }
	}

@Composable
	private fun PairedCanvasPane(
	    uiState: CanvasHomeUiState,
        onBrushToolGuideDismissed: () -> Unit,
	    onCanvasPress: (StrokePoint) -> Unit,
	    onCanvasDrag: (StrokePoint) -> Unit,
	    onCanvasRelease: () -> Unit,
	    onCanvasTap: (StrokePoint) -> Unit,
	    onCanvasEraseDrag: (StrokePoint, StrokePoint) -> Unit,
	    onCanvasViewportChanged: (Int, Int) -> Unit,
	    onColorSelected: (Long) -> Unit,
        onEyedropperColorCommitted: (Long) -> Unit,
	    onBrushWidthChanged: (Float) -> Unit,
	    onBrushToggle: () -> Unit,
	    onEraserToggle: () -> Unit,
	    onTextToggle: () -> Unit,
	    onStickerToggle: () -> Unit,
	    onStickerPackSelected: (String, Int) -> Unit,
	    stickerAssetStore: com.subhajit.mulberry.stickers.StickerAssetStore,
	    photo: CanvasPhotoHooks,
    onTextElementAdded: (com.subhajit.mulberry.drawing.model.CanvasTextElement) -> Unit,
    onTextElementUpdated: (com.subhajit.mulberry.drawing.model.CanvasTextElement) -> Unit,
    onTextElementDeleted: (String) -> Unit,
	    onStickerElementAdded: (com.subhajit.mulberry.drawing.model.CanvasStickerElement) -> Unit,
	    onStickerElementUpdated: (com.subhajit.mulberry.drawing.model.CanvasStickerElement) -> Unit,
	    onStickerElementDeleted: (String) -> Unit,
	    onTextEditorRequested: (CanvasTextEditorSession) -> Unit,
	    onStickerEditorRequested: (CanvasStickerEditorSession) -> Unit,
	    onNewStickerRequestedAt: (StrokePoint) -> Unit,
        allowToolTapPlacement: Boolean,
        onEmptyTapCreationHandled: () -> Unit,
	    isEditorOpen: Boolean,
    onClearRequested: () -> Unit,
    onUndoRequested: () -> Unit,
    onRedoRequested: () -> Unit,
    onSendReaction: suspend (ReactionType) -> Boolean,
    onShareRequested: () -> Unit,
    modifier: Modifier = Modifier
) {
        val viewConfig = LocalViewConfiguration.current
        val coroutineScope = rememberCoroutineScope()
	    var reactionRailOpen by remember { mutableStateOf(false) }
	    var sentReactionOverlay by remember { mutableStateOf<SentReactionOverlayState?>(null) }
	    var sentReactionToken by remember { mutableStateOf(0L) }
        // The canvas never zooms or pans, as on iPhone.
        val viewportTransform = CanvasViewportTransform()
        var eyedropperArmed by remember { mutableStateOf(false) }
        var eyedropperLiveColorArgb by remember { mutableStateOf<Long?>(null) }
        var eyedropperCommittedColorArgb by remember { mutableStateOf<Long?>(null) }

	    LaunchedEffect(sentReactionOverlay) {
	        if (sentReactionOverlay == null) return@LaunchedEffect
	        delay(1_050)
	        sentReactionOverlay = null
	    }

        LaunchedEffect(isEditorOpen) {
            if (isEditorOpen) {
                eyedropperArmed = false
                eyedropperLiveColorArgb = null
                eyedropperCommittedColorArgb = null
            }
        }

        LaunchedEffect(
            uiState.toolState.strokeColorArgb,
            uiState.toolState.textColorArgb,
            eyedropperCommittedColorArgb,
            eyedropperArmed
        ) {
            val committed = eyedropperCommittedColorArgb ?: return@LaunchedEffect
            if (eyedropperArmed) return@LaunchedEffect
            if (
                uiState.toolState.strokeColorArgb == committed &&
                uiState.toolState.textColorArgb == committed
            ) {
                eyedropperLiveColorArgb = null
                eyedropperCommittedColorArgb = null
            }
        }

        // The eyedropper is one of the tools, as on iPhone: choosing it puts the others down,
        // choosing another tool puts it down, and tapping it again keeps it.
        // The tool just chosen, until the saved tool catches up: the toolbar shows the choice at
        // once rather than waiting on the database, so nothing flashes on the way.
        var pendingTool by remember { mutableStateOf<DrawingTool?>(null) }
        LaunchedEffect(uiState.toolState.activeTool, pendingTool) {
            if (pendingTool == uiState.toolState.activeTool) pendingTool = null
        }
        val shownTool = pendingTool ?: uiState.toolState.activeTool
        val onEyedropperSelected: () -> Unit = select@{
            if (isEditorOpen || eyedropperArmed) return@select
            // It replaces the tool in hand, so text or stickers aren't left waiting underneath.
            pendingTool = DrawingTool.DRAW
            onBrushToggle()
            eyedropperArmed = true
            eyedropperLiveColorArgb = null
            eyedropperCommittedColorArgb = null
        }
        val putEyedropperDown: () -> Unit = {
            eyedropperArmed = false
            eyedropperLiveColorArgb = null
        }
        /** Puts the eyedropper down and takes up [tool], showing it chosen straight away. */
        val chooseTool: (DrawingTool, () -> Unit) -> Unit = { tool, select ->
            pendingTool = tool
            putEyedropperDown()
            select()
        }
        /** A colour from the palette ends the eyedropper and, unless writing text, returns to the brush. */
        val chooseColour: (Long) -> Unit = { colorArgb ->
            onColorSelected(colorArgb)
            if (shownTool == DrawingTool.TEXT) putEyedropperDown() else chooseTool(DrawingTool.DRAW, onBrushToggle)
        }

        val haptics = rememberMulberryHaptics()
        // The reactions' GIFs decode in the background while the canvas opens, so the rail opens at once.
        val reactionContext = LocalContext.current
        LaunchedEffect(Unit) { ReactionGifUiCache.prewarm(reactionContext) }
	    val sendAndAnimate: (ReactionType) -> Unit = { type ->
            coroutineScope.launch {
                val didSend = onSendReaction(type)
                if (!didSend) return@launch
                haptics.perform(MulberryHaptic.LightImpact)
                sentReactionToken += 1
                sentReactionOverlay = SentReactionOverlayState(type = type, token = sentReactionToken)
                reactionRailOpen = false
            }
	    }

        var paneOriginInRoot by remember { mutableStateOf(Offset.Zero) }
        var paneSizePx by remember { mutableStateOf(IntSize.Zero) }
        var brushButtonBoundsInRoot by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        var guideTooltipSizePx by remember { mutableStateOf(IntSize.Zero) }
        val density = LocalDensity.current
        val streakLevelUpBannerClick = LocalStreakLevelUpBannerClick.current ?: {}

        val celebrationAnimationsEnabled = rememberSystemAnimationsEnabled()

        val reduceMotion = !rememberSystemAnimationsEnabled()
        var showWidthPicker by remember { mutableStateOf(false) }
        // The reactions bar, from the button (0) to fully out (1). It stays drawn until it has
        // folded back into the button.
        var reactionButtonBoundsInRoot by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        val reactionRailProgress = remember { Animatable(0f) }
        LaunchedEffect(reactionRailOpen) {
            val target = if (reactionRailOpen) 1f else 0f
            if (reduceMotion) {
                reactionRailProgress.animateTo(target, tween(durationMillis = 150))
            } else {
                reactionRailProgress.animateTo(
                    target,
                    spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
                )
            }
        }
        val reactionRailShown = reactionRailOpen || reactionRailProgress.value > 0.001f
        val setReactionRail: (Boolean) -> Unit = { open ->
            if (open != reactionRailOpen) {
                reactionRailOpen = open
                haptics.perform(MulberryHaptic.Selection)
            }
        }
        // A finger held on the canvas opens the reactions over it, whatever the tool, as on iPhone.
        var reactionArc by remember { mutableStateOf<ReactionArcRequest?>(null) }
        val reactionArcPointer = remember { ReactionArcPointer() }
        var showColorPicker by remember { mutableStateOf(false) }
        var showCustomColorPicker by remember { mutableStateOf(false) }
        val brushColorArgb = eyedropperLiveColorArgb ?: uiState.toolState.selectedColorArgb
        val canvasBackgroundArgb = MaterialTheme.colorScheme.background.toArgb().toLong() and 0xFFFFFFFFL

        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    paneOriginInRoot = coordinates.positionInRoot()
                    paneSizePx = coordinates.size
                }
        ) {
            val paneWidth = maxWidth
            val toolsTop = maxHeight - CanvasChromeMetrics.BottomMargin - CanvasChromeMetrics.ToolRowHeight
            val visibleHeight = (toolsTop - CanvasChromeMetrics.HeaderHeight).coerceAtLeast(0.dp)
            val canvasHeight = canvasHeightFor(paneWidth, visibleHeight)
            // Centred between the middles of the top bar and the tools, so it runs under both, as on iPhone.
            val canvasMiddle = (CanvasChromeMetrics.HeaderHeight / 2 + toolsTop + CanvasChromeMetrics.ToolRowHeight / 2) / 2
            val canvasTop = canvasMiddle - canvasHeight / 2
            val controlsWidth = minOf(paneWidth - 16.dp, CanvasChromeMetrics.ControlsMaxWidth)
            // Squee stands a button's width in from the controls' left edge, as on iPhone.
            val squeeStart = (paneWidth - controlsWidth) / 2 + 8.dp + CanvasChromeMetrics.ButtonSize + 8.dp

            val canvasTopPx = with(density) { canvasTop.toPx() }
            // Transparent and unframed: drawings sit straight on the screen.
            Box(
                modifier = Modifier
                    .offset(y = canvasTop)
                    .size(width = paneWidth, height = canvasHeight)
                    .clipToBounds()
                    .onGloballyPositioned { coordinates -> photo.onCanvasBoundsChanged(coordinates.boundsInRoot()) }
                    .holdForReactions(
                        enabled = { !isEditorOpen && !reactionRailOpen && reactionArc == null },
                        onHold = { position ->
                            haptics.perform(MulberryHaptic.LongPress)
                            reactionArc = ReactionArcRequest(tip = Offset(position.x, position.y + canvasTopPx))
                        },
                        pointer = reactionArcPointer
                    )
            ) {
                DrawingCanvas(
                    canvasState = uiState.canvasState,
                    activeTool = uiState.toolState.activeTool,
                    viewportTransform = viewportTransform,
                    onDrawStart = onCanvasPress,
                    onDrawPoint = onCanvasDrag,
                    onDrawEnd = onCanvasRelease,
                    onEraseTap = onCanvasTap,
                    onEraseDrag = onCanvasEraseDrag,
                    onCanvasSizeChanged = onCanvasViewportChanged,
                    modifier = Modifier.fillMaxSize(),
                    strokeRenderMode = uiState.canvasStrokeRenderMode,
                    drawsStrokes = false
                )

                // Stickers, then text: the element layers, under the strokes.
                CanvasTextOverlay(
                    elements = uiState.canvasState.elements,
                    strokes = uiState.canvasState.strokes,
                    activeTool = uiState.toolState.activeTool,
                    palette = uiState.palette,
                    selectedColorArgb = uiState.toolState.selectedColorArgb,
                    showElementBounds = uiState.canvasShowElementBounds,
                    stickerAssetStore = stickerAssetStore,
                    viewportTransform = viewportTransform,
                    onEraseTap = onCanvasTap,
                    onEraseDrag = onCanvasEraseDrag,
                    onAddTextElement = onTextElementAdded,
                    onUpdateTextElement = onTextElementUpdated,
                    onDeleteTextElement = onTextElementDeleted,
                    onAddStickerElement = onStickerElementAdded,
                    onUpdateStickerElement = onStickerElementUpdated,
                    onDeleteStickerElement = onStickerElementDeleted,
                    onUpdatePhotoElement = photo.onPhotoElementUpdated,
                    onDeletePhotoElement = photo.onPhotoElementDeleted,
                    photoStore = photo.photoStore,
                    onRequestPhotoEdit = photo.onPhotoEditRequested,
                    onRequestNewPhoto = photo.onNewPhotoRequested,
                    hiddenElementId = photo.hiddenElementId,
                    onRequestTextEdit = onTextEditorRequested,
                    onRequestStickerEdit = onStickerEditorRequested,
                    onRequestNewStickerAt = onNewStickerRequestedAt,
                    allowCreateOnEmptyTap = allowToolTapPlacement,
                    onEmptyTapCreationHandled = onEmptyTapCreationHandled,
                    isEditorOpen = isEditorOpen,
                    modifier = Modifier.fillMaxSize()
                )

                // Strokes cover every element. It takes no touches, so the gestures below still get them.
                CanvasStrokesLayer(
                    canvasState = uiState.canvasState,
                    viewportTransform = viewportTransform,
                    strokeRenderMode = uiState.canvasStrokeRenderMode,
                    modifier = Modifier.fillMaxSize()
                )

                EyedropperOverlay(
                    armed = eyedropperArmed,
                    enabled = !isEditorOpen,
                    canvasState = uiState.canvasState,
                    viewportTransform = viewportTransform,
                    stickerAssetStore = stickerAssetStore,
                    photoStore = photo.photoStore,
                    backgroundColorArgb = canvasBackgroundArgb,
                    onLiveColorArgbChanged = { colorArgb ->
                        eyedropperLiveColorArgb = colorArgb
                    },
                    // The picked colour becomes the brush's and the text's, and the brush is back in hand.
                    onCommitColorArgb = { colorArgb ->
                        eyedropperLiveColorArgb = colorArgb
                        eyedropperCommittedColorArgb = colorArgb
                        onEyedropperColorCommitted(colorArgb)
                        eyedropperArmed = false
                        onBrushToggle()
                    },
                    // Let go without choosing, as when a second finger lands: still the eyedropper.
                    onCanceled = {
                        eyedropperLiveColorArgb = null
                        eyedropperCommittedColorArgb = null
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // The visible part of the canvas, between the top bar and the tools: the prompt, the
            // streak banner and the celebrations belong here.
            Box(
                modifier = Modifier
                    .padding(top = CanvasChromeMetrics.HeaderHeight)
                    .fillMaxWidth()
                    .height(visibleHeight)
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.canvasState.isEmpty,
                    enter = fadeIn(animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing)),
                    exit = fadeOut(animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing)),
                    modifier = Modifier.matchParentSize()
                ) {
                    CanvasBlankStateGuidance(squeeStart = squeeStart)
                }

                if (uiState.showStreakLevelUpBanner && !isEditorOpen) {
                    // Bobs only while it's shown, and moves without rebuilding the canvas.
                    val bannerBob = rememberBobbingPhase(label = "streak_level_up_banner_bob")
                    StreakLevelUpBanner(
                        text = stringResource(R.string.home_canvas_streak_level_up_banner),
                        onClick = streakLevelUpBannerClick,
                        modifier = Modifier
                            .zIndex(40f)
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(top = 12.dp)
                            .bobbing(bannerBob)
                    )
                }

                // A streak level-up, or the account's first drawing. Like iOS, it never plays
                // with animations turned off.
                val celebrationConfettiToken = uiState.celebrationConfettiToken
                if (celebrationConfettiToken != null && celebrationAnimationsEnabled) {
                    key(celebrationConfettiToken) {
                        TwoSidedConfettiBurst(
                            modifier = Modifier
                                .matchParentSize()
                                .zIndex(45f)
                        )
                    }
                }

                sentReactionOverlay?.let { overlay ->
                    ReactionSentOverlay(
                        reactionType = overlay.type,
                        token = overlay.token,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            // Actions stand apart from the tools, in one column on the right, as on iPhone: reactions
            // and share on top, then width, colour and clear. Undo and redo are in the top bar.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = CanvasChromeMetrics.BottomMargin)
                    .width(controlsWidth),
                verticalArrangement = Arrangement.spacedBy(CanvasChromeMetrics.ColumnsToTools)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    // Width for the brush, colour for the brush and the eyedropper, and clear always.
                    // What the tool in hand can't use slides away but keeps its place, so reactions
                    // and share above never move.
                    val brushInHand = !eyedropperArmed && shownTool == DrawingTool.DRAW
                    LaunchedEffect(brushInHand) { if (!brushInHand) showWidthPicker = false }
                    Column {
                        // The bar grows out of this button; while it's out, the bar stands in for it.
                        CanvasChromeButton(
                            contentDescription = stringResource(R.string.home_canvas_reactions_content_description),
                            onClick = { setReactionRail(!reactionRailOpen) },
                            floating = true,
                            modifier = Modifier
                                .padding(bottom = CanvasChromeMetrics.ColumnSpacing)
                                .alpha(if (reactionRailShown) 0f else 1f)
                                .onGloballyPositioned { coordinates ->
                                    reactionButtonBoundsInRoot = coordinates.boundsInRoot()
                                }
                        ) { ChromeSymbol(R.drawable.ic_material_favorite_border) }
                        CanvasChromeButton(
                            contentDescription = stringResource(R.string.home_canvas_share_content_description),
                            onClick = {
                                setReactionRail(false)
                                onShareRequested()
                            },
                            floating = true,
                            modifier = Modifier.padding(bottom = CanvasChromeMetrics.ColumnSpacing)
                        ) { ChromeSymbol(R.drawable.ic_material_share) }
                        HudSlot(visible = brushInHand, reduceMotion = reduceMotion) {
                            BrushWidthControl(
                                savedWidth = uiState.toolState.selectedWidth,
                                colorArgb = brushColorArgb,
                                open = showWidthPicker,
                                onOpenChanged = { open ->
                                    showWidthPicker = open
                                    if (open) showColorPicker = false
                                },
                                onWidthChosen = onBrushWidthChanged
                            )
                        }
                        HudSlot(visible = brushInHand || eyedropperArmed, reduceMotion = reduceMotion) {
                            CanvasChromeButton(
                                contentDescription = "Colour palette",
                                onClick = {
                                    showColorPicker = true
                                    showWidthPicker = false
                                },
                                floating = true,
                                modifier = Modifier.colourState(brushColorArgb)
                            ) { ColourWheelIcon(colorArgb = brushColorArgb) }
                        }
                        CanvasChromeButton(
                            contentDescription = stringResource(R.string.home_canvas_clear_content_description),
                            onClick = onClearRequested,
                            // Nothing to clear on a blank canvas.
                            enabled = !uiState.canvasState.isEmpty,
                            floating = true,
                            modifier = Modifier.testTag(TestTags.CLEAR_BUTTON)
                        ) { ChromeSymbol(R.drawable.ic_material_delete) }
                    }
                }

                CanvasToolRow {
                    CanvasChromeButton(
                        contentDescription = stringResource(R.string.home_canvas_brush_content_description),
                        onClick = {
                            chooseTool(DrawingTool.DRAW, onBrushToggle)
                            if (uiState.showBrushToolGuide) onBrushToolGuideDismissed()
                        },
                        selected = !eyedropperArmed && shownTool == DrawingTool.DRAW,
                        modifier = Modifier
                            .testTag(TestTags.BRUSH_BUTTON)
                            .onGloballyPositioned { coordinates ->
                                brushButtonBoundsInRoot = coordinates.boundsInRoot()
                            }
                    ) {
                        ChromeIllustration(
                            R.drawable.canvas_action_brush,
                            selected = !eyedropperArmed && shownTool == DrawingTool.DRAW
                        )
                    }
                    CanvasChromeButton(
                        contentDescription = stringResource(R.string.home_canvas_text_content_description),
                        onClick = {
                            val next = if (!eyedropperArmed && shownTool == DrawingTool.TEXT) DrawingTool.DRAW else DrawingTool.TEXT
                            chooseTool(next, onTextToggle)
                        },
                        selected = !eyedropperArmed && shownTool == DrawingTool.TEXT,
                        modifier = Modifier.testTag(TestTags.TEXT_BUTTON)
                    ) {
                        ChromeIllustration(
                            R.drawable.canvas_action_text,
                            selected = !eyedropperArmed && shownTool == DrawingTool.TEXT,
                            tinted = true
                        )
                    }
                    CanvasChromeButton(
                        contentDescription = stringResource(R.string.home_canvas_sticker_content_description),
                        onClick = {
                            val next = if (!eyedropperArmed && shownTool == DrawingTool.STICKER) DrawingTool.DRAW else DrawingTool.STICKER
                            chooseTool(next, onStickerToggle)
                        },
                        selected = !eyedropperArmed && shownTool == DrawingTool.STICKER,
                        modifier = Modifier.testTag(TestTags.STICKER_BUTTON)
                    ) {
                        ChromeIllustration(
                            R.drawable.canvas_action_sticker,
                            selected = !eyedropperArmed && shownTool == DrawingTool.STICKER
                        )
                    }
                    CanvasChromeButton(
                        contentDescription = stringResource(R.string.home_canvas_camera_content_description),
                        onClick = {
                            val next = if (!eyedropperArmed && shownTool == DrawingTool.PHOTO) DrawingTool.DRAW else DrawingTool.PHOTO
                            chooseTool(next, photo.onCameraToggle)
                        },
                        selected = !eyedropperArmed && shownTool == DrawingTool.PHOTO,
                        enabled = !isEditorOpen
                    ) {
                        ChromeIllustration(
                            R.drawable.canvas_action_camera,
                            selected = !eyedropperArmed && shownTool == DrawingTool.PHOTO
                        )
                    }
                    CanvasChromeButton(
                        contentDescription = stringResource(R.string.home_canvas_erase_content_description),
                        onClick = { chooseTool(DrawingTool.ERASE, onEraserToggle) },
                        selected = !eyedropperArmed && shownTool == DrawingTool.ERASE,
                        modifier = Modifier.testTag(TestTags.ERASER_BUTTON)
                    ) {
                        ChromeIllustration(
                            R.drawable.canvas_action_erase,
                            selected = !eyedropperArmed && shownTool == DrawingTool.ERASE
                        )
                    }
                    CanvasChromeButton(
                        contentDescription = stringResource(R.string.home_canvas_eyedropper_content_description),
                        onClick = {
                            showWidthPicker = false
                            showColorPicker = false
                            showCustomColorPicker = false
                            onEyedropperSelected()
                        },
                        selected = eyedropperArmed,
                        enabled = !isEditorOpen,
                        modifier = Modifier.testTag(TestTags.EYEDROPPER_BUTTON)
                    ) {
                        ChromeIllustration(R.drawable.canvas_action_eyedropper, selected = eyedropperArmed)
                    }
                }
            }

            if (reactionRailOpen) {
                // A tap anywhere else puts the reactions away without drawing.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    setReactionRail(false)
                                }
                            }
                        }
                )
                BackHandler { setReactionRail(false) }
            }
            val reactionAnchor = reactionButtonBoundsInRoot
            if (reactionRailShown && reactionAnchor != null) {
                // Grown out of the reactions button, reaching left across the canvas.
                ReactionRail(
                    progress = reactionRailProgress.value,
                    open = reactionRailOpen,
                    anchor = reactionAnchor.translate(-paneOriginInRoot),
                    maxWidth = paneWidth - 32.dp,
                    reduceMotion = reduceMotion,
                    onReactionSelected = sendAndAnimate
                )
            }

            reactionArc?.let { request ->
                ReactionArc(
                    tip = request.tip,
                    paneWidthPx = with(density) { paneWidth.toPx() },
                    minTopPx = with(density) { CanvasChromeMetrics.HeaderHeight.toPx() },
                    pointer = reactionArcPointer,
                    pointerOrigin = Offset(0f, canvasTopPx),
                    reduceMotion = reduceMotion,
                    onSend = { type ->
                        reactionArc = null
                        sendAndAnimate(type)
                    },
                    onDismiss = { reactionArc = null }
                )
                BackHandler { reactionArc = null }
            }

            if (showColorPicker) {
                CanvasPaletteSheet(
                    palette = uiState.palette,
                    selectedColorArgb = uiState.toolState.selectedColorArgb,
                    showSelection = uiState.toolState.activeTool == DrawingTool.DRAW ||
                        uiState.toolState.activeTool == DrawingTool.TEXT,
                    onColorSelected = { colorArgb ->
                        chooseColour(colorArgb)
                        showColorPicker = false
                    },
                    onCustomColourRequested = {
                        showColorPicker = false
                        showCustomColorPicker = true
                    },
                    onDismiss = { showColorPicker = false }
                )
            }

            RgbColorPickerSheet(
                visible = showCustomColorPicker,
                initialColorArgb = uiState.toolState.selectedColorArgb,
                onDismissRequest = { showCustomColorPicker = false },
                onApply = { colorArgb ->
                    chooseColour(colorArgb)
                    showCustomColorPicker = false
                }
            )

            if (uiState.showBrushToolGuide) {
                val guideBob = rememberBobbingPhase(label = "brush_tool_guide_bob")
                val brushBounds = brushButtonBoundsInRoot
                if (brushBounds != null && paneSizePx.width > 0 && paneSizePx.height > 0) {
                    val horizontalPaddingPx = with(density) { 14.dp.toPx() }
                    val verticalPaddingPx = with(density) { 8.dp.toPx() }

                    val brushCenterXInPane =
                        brushBounds.center.x - paneOriginInRoot.x
                    val brushTopInPane =
                        brushBounds.top - paneOriginInRoot.y

                    val tooltipX =
                        (brushCenterXInPane - guideTooltipSizePx.width / 2f)
                            .coerceIn(
                                horizontalPaddingPx,
                                (paneSizePx.width - guideTooltipSizePx.width - horizontalPaddingPx)
                                    .coerceAtLeast(horizontalPaddingPx)
                            )
                    val tooltipY =
                        (brushTopInPane - guideTooltipSizePx.height - verticalPaddingPx)
                            .coerceAtLeast(verticalPaddingPx)

                    BrushToolGuideTooltip(
                        text = stringResource(R.string.home_canvas_brush_tool_guide_tooltip),
                        onDismiss = onBrushToolGuideDismissed,
                        modifier = Modifier
                            .zIndex(50f)
                            .offset {
                                IntOffset(
                                    x = tooltipX.toInt(),
                                    y = tooltipY.toInt()
                                )
                            }
                            .bobbing(guideBob)
                            .onGloballyPositioned { coordinates ->
                                guideTooltipSizePx = coordinates.size
                            }
                    )
                }
            }
        }
    }

@Composable
/**
 * Fills the visible canvas, as on iPhone: the prompt in its middle, and Squee at its foot, at
 * [squeeStart], a button's width in from the controls' left edge, as on iPhone.
 */
private fun AnimatedVisibilityScope.CanvasBlankStateGuidance(
    squeeStart: Dp,
    modifier: Modifier = Modifier
) {
    val guidanceFontFamily = rememberMulberryGuidanceFontFamily()
    val squeeExitExtraOffsetPx = with(LocalDensity.current) { 28.dp.roundToPx() }
    // As on iOS: with animations removed Squee rests, and at large text sizes it's smaller.
    val animate = rememberSystemAnimationsEnabled()
    val squeeHeight = if (isLargeText()) 96.dp else 160.dp

    // Read only while placing Squee, so the idle bob never rebuilds the prompt.
    val squeeBobPhase: State<Float> = if (animate) {
        rememberInfiniteTransition(label = "squee_idle_bob").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1800
                    0f at 0
                    0f at 240
                    1f at 900 with LinearOutSlowInEasing
                    0f at 1800 with EaseIn
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "squee_idle_bob_phase"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
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

            Image(
                painter = painterResource(R.drawable.squee_with_pencil),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = squeeStart)
                    .bobbing(squeeBobPhase, amplitude = 6.dp)
                    .height(squeeHeight)
                    .aspectRatio(432f / 498f)
                    .animateEnterExit(
                        enter = if (animate) {
                            slideInHorizontally(
                                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                                initialOffsetX = { fullWidth -> -fullWidth - squeeExitExtraOffsetPx }
                            )
                        } else {
                            EnterTransition.None
                        },
                        exit = if (animate) {
                            slideOutHorizontally(
                                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                                targetOffsetX = { fullWidth -> -fullWidth - squeeExitExtraOffsetPx }
                            )
                        } else {
                            ExitTransition.None
                        }
                    )
        )
    }
}

@Composable
private fun BrushToolGuideTooltip(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            color = MulberryPrimary,
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 10.dp
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = text,
                    color = Color.White,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium
                )
                GuideCloseButton(onClick = onDismiss)
            }
        }

        Canvas(
            modifier = Modifier
                .size(width = 22.dp, height = 10.dp)
                .offset(y = (-1).dp)
        ) {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width / 2f, size.height)
                lineTo(size.width, 0f)
                close()
            }
            drawPath(path = path, color = MulberryPrimary)
        }
    }
}

@Composable
private fun GuideCloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.22f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(8.dp)
    ) {
        val stroke = Stroke(width = 2.8f, cap = StrokeCap.Round)
        drawLine(
            color = Color.White,
            start = androidx.compose.ui.geometry.Offset(0f, 0f),
            end = androidx.compose.ui.geometry.Offset(size.width, size.height),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White,
            start = androidx.compose.ui.geometry.Offset(size.width, 0f),
            end = androidx.compose.ui.geometry.Offset(0f, size.height),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round
        )
    }
}

/**
 * The reactions bar, grown out of the reactions button as on iPhone: the button's surface
 * stretches left into a capsule as the reactions arrive, right to left, and folds back into the
 * button as they go. [progress] runs from the button (0) to the open bar (1); [anchor] is the
 * button, in the pane's pixels. With animations removed the bar simply fades in and out.
 */
@Composable
private fun ReactionRail(
    progress: Float,
    open: Boolean,
    anchor: androidx.compose.ui.geometry.Rect,
    maxWidth: Dp,
    reduceMotion: Boolean,
    onReactionSelected: (ReactionType) -> Unit
) {
    val reactions = ReactionType.entries
    val naturalWidth = REACTION_SLOT * reactions.size + REACTION_GAP * (reactions.size - 1) + REACTION_RAIL_PADDING * 2
    // On a narrow screen the reactions shrink to fit, as iOS scales its picker.
    val fit = (maxWidth / naturalWidth).coerceIn(0.5f, 1f)
    val barWidth = naturalWidth * fit
    val shown = progress.coerceIn(0f, 1f)
    val width = if (reduceMotion) barWidth else lerp(CanvasChromeMetrics.ButtonSize, barWidth, progress.coerceAtLeast(0f))
    val surface = MaterialTheme.mulberryAppColors.softSurfaceStrong
    val label = stringResource(R.string.home_canvas_reactions_content_description)
    val density = LocalDensity.current
    val widthPx = with(density) { width.toPx() }

    Box(
        modifier = Modifier
            .offset { IntOffset((anchor.right - widthPx).roundToInt(), anchor.top.roundToInt()) }
            .size(width = width, height = CanvasChromeMetrics.ButtonSize)
            .graphicsLayer { alpha = if (reduceMotion) shown else 1f }
            .shadow(elevation = 6.dp * shown, shape = CircleShape, clip = false)
            .clip(CircleShape)
            // Translucent like the button, firmer once open so the reactions read over a drawing.
            .background(surface.copy(alpha = 0.72f + 0.24f * shown))
            .testTag(TestTags.REACTION_RAIL)
            .semantics { paneTitle = label }
    ) {
        // The button's heart, fading as the bar takes over.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(CanvasChromeMetrics.ButtonSize)
                .graphicsLayer { alpha = if (reduceMotion) 0f else (1f - shown * 3f).coerceIn(0f, 1f) }
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center
        ) { ChromeSymbol(R.drawable.ic_material_favorite_border) }

        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .wrapContentWidth(align = Alignment.End, unbounded = true)
                .graphicsLayer {
                    scaleX = fit
                    scaleY = fit
                    transformOrigin = TransformOrigin(1f, 0.5f)
                }
                .padding(horizontal = REACTION_RAIL_PADDING),
            horizontalArrangement = Arrangement.spacedBy(REACTION_GAP),
            verticalAlignment = Alignment.CenterVertically
        ) {
            reactions.forEachIndexed { index, reaction ->
                // The ones nearest the button arrive first.
                val fromButton = reactions.size - 1 - index
                val arrived = if (reduceMotion) 1f else ((shown - 0.25f - fromButton * 0.07f) / 0.4f).coerceIn(0f, 1f)
                ReactionGif(
                    type = reaction,
                    modifier = Modifier
                        .size(REACTION_SLOT)
                        .graphicsLayer {
                            alpha = arrived
                            scaleX = 0.6f + 0.4f * arrived
                            scaleY = 0.6f + 0.4f * arrived
                        }
                        .mulberryTapScale()
                        .clickable(
                            enabled = open,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button
                        ) { onReactionSelected(reaction) }
                        .padding(3.dp)
                        .semantics { contentDescription = reaction.shortcutLabel }
                )
            }
        }
    }
}

private val REACTION_SLOT = 44.dp
private val REACTION_GAP = 4.dp
private val REACTION_RAIL_PADDING = 6.dp

@Composable
private fun ReactionSentOverlay(
    reactionType: ReactionType,
    token: Long,
    modifier: Modifier = Modifier
) {
    val scale = remember { Animatable(0.92f) }
    val alpha = remember { Animatable(0f) }
    val offsetYDp = remember { Animatable(18f) }
    val offsetXDp = remember { Animatable(0f) }
    val rotationZAnim = remember { Animatable(0f) }
    val animate = rememberSystemAnimationsEnabled()

    LaunchedEffect(token) {
        if (!animate) {
            // iOS's still pose: no rise, sway or fall; it shows in place, holds, and goes.
            scale.snapTo(1f)
            offsetYDp.snapTo(0f)
            offsetXDp.snapTo(0f)
            rotationZAnim.snapTo(0f)
            alpha.animateTo(1f, tween(durationMillis = 150, easing = FastOutSlowInEasing))
            delay(550)
            alpha.animateTo(0f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
            return@LaunchedEffect
        }
        scale.snapTo(0.92f)
        alpha.snapTo(0f)
        offsetYDp.snapTo(18f)
        offsetXDp.snapTo(0f)
        rotationZAnim.snapTo(0f)

        launch {
            alpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing)
            )
        }
        val slideMs = 170f
        val holdMs = 380f
        val fallMs = 260f
        val startAtMs = SystemClock.uptimeMillis()
        val wiggleHz = 3.5f

        val wiggleJob = launch {
            while (true) {
                val tMs = (SystemClock.uptimeMillis() - startAtMs).toFloat().coerceAtLeast(0f)
                val slideRamp = (tMs / slideMs).coerceIn(0f, 1f)
                val fallProgress = ((tMs - slideMs - holdMs) / fallMs).coerceIn(0f, 1f)
                val fallDamp =
                    if (tMs < slideMs + holdMs) 1f
                    else (1f - fallProgress * 0.75f).coerceIn(0f, 1f)
                val amplitudeDeg = 5.0f * slideRamp * fallDamp
                val tSec = tMs / 1000f
                val rotation = sin(2f * PI.toFloat() * wiggleHz * tSec) * amplitudeDeg
                rotationZAnim.snapTo(rotation)
                delay(16)
            }
        }
        launch {
            scale.animateTo(
                targetValue = 1.06f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
        offsetYDp.animateTo(
            targetValue = -12f,
            animationSpec = tween(durationMillis = slideMs.toInt(), easing = FastOutSlowInEasing)
        )

        delay(holdMs.toLong())

        launch {
            offsetXDp.animateTo(
                targetValue = 10f,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
            )
            offsetXDp.animateTo(
                targetValue = -6f,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
            )
            offsetXDp.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing)
            )
        }
        launch {
            alpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
            )
        }
        launch {
            scale.animateTo(
                targetValue = 0.95f,
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
            )
        }
        // Faster fall-down.
        offsetYDp.animateTo(
            targetValue = 22f,
            animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
        )

        wiggleJob.cancel()
    }

    ReactionGif(
        type = reactionType,
        modifier = modifier
            .size(128.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
                translationY = offsetYDp.value.dp.toPx()
                translationX = offsetXDp.value.dp.toPx()
                rotationZ = rotationZAnim.value
            }
    )
}

private data class SentReactionOverlayState(
    val type: ReactionType,
    val token: Long
)

@Composable
private fun rememberMulberryGuidanceFontFamily(): FontFamily {
    return VirgilFontFamily
}

@Composable
private fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    colors: androidx.compose.material3.SliderColors,
    modifier: Modifier = Modifier
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
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
private fun WallpaperHomePane(
    uiState: CanvasHomeUiState,
    presets: List<WallpaperPreset>,
    onSetUpWallpaper: () -> Unit,
    onNavigateToWallpaperHelp: () -> Unit,
    onViewMoreWallpapers: () -> Unit,
    onUploadFromGallery: () -> Unit,
    onPresetSelected: (WallpaperPreset) -> Unit,
    onRemoteWallpaperSelected: (RemoteWallpaper) -> Unit
) {
    val selectedPreviewResId = presets
        .firstOrNull { it.id == uiState.selectedWallpaperPresetId }
        ?.previewDrawableResId
        ?: R.drawable.wallpaper_default_bg_preview
    val previewAssetPath = if (uiState.selectedWallpaperPresetId == null) {
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

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (!isSelectedOnBoth) {
                    val setupCtaResId =
                        if (!isSelectedOnLock) {
                            R.string.onboarding_wallpaper_setup_button
                        } else {
                            R.string.wallpaper_setup_set_both_lock_home
                        }

                    val badgeResId = when {
                        isSelectedOnHome -> R.string.wallpaper_setup_badge_home
                        isSelectedOnLock -> R.string.wallpaper_setup_badge_lock
                        else -> R.string.wallpaper_setup_badge_not_set
                    }
                    val badgeStyle = if (isSelectedOnHome || isSelectedOnLock) {
                        WallpaperSetupStatusBadgeStyle.Success
                    } else {
                        WallpaperSetupStatusBadgeStyle.Error
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        WallpaperPrimaryButtonWithStatusBadge(
                            text = stringResource(setupCtaResId),
                            statusText = stringResource(badgeResId),
                            statusStyle = badgeStyle,
	                            onClick = onSetUpWallpaper,
                            enabled = !uiState.isWallpaperBusy,
	                            buttonModifier = Modifier.testTag(TestTags.HOME_OPEN_WALLPAPER_BUTTON)
                        )
                        WallpaperWhyDoIDoThisLink(
                            onClick = onNavigateToWallpaperHelp,
                            modifier = Modifier.testTag(TestTags.HOME_WALLPAPER_HELP_LINK)
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
                    selectedPresetId = uiState.selectedWallpaperPresetId,
                    selectedRemoteWallpaperId = uiState.selectedRemoteWallpaperId,
                    applyingRemoteWallpaperId = uiState.applyingRemoteWallpaperId,
                    onUploadFromGallery = onUploadFromGallery,
                    onPresetSelected = onPresetSelected,
                    onRemoteWallpaperSelected = onRemoteWallpaperSelected,
                    onViewMoreWallpapers = onViewMoreWallpapers
                )
            }
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
                    .heightIn(min = 50.dp)
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
                    .heightIn(min = 50.dp)
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
    onHelpRequested: () -> Unit,
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
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.invite_code_help),
                color = MulberryPrimary,
                fontFamily = PoppinsFontFamily,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .mulberryTapScale()
                    .clickable(onClick = onHelpRequested)
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
                    .heightIn(min = 50.dp)
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
                        .heightIn(min = 50.dp)
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
                        append(
                            partnerName?.takeIf { it.isNotBlank() }
                                ?.let { stringResource(R.string.invite_acceptance_not_partner, it) }
                                ?: stringResource(R.string.invite_acceptance_wrong_partner)
                        )
                        append(" ")
                        withStyle(SpanStyle(color = MulberryPrimary, fontWeight = FontWeight.SemiBold)) {
                            append(
                                if (confirmation.isDisconnecting) {
                                    stringResource(R.string.home_pairing_disconnect_progress)
                                } else {
                                    stringResource(R.string.pairing_confirmed_unpair)
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
            TwoSidedConfettiBurst(
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
                    .heightIn(min = 50.dp)
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
                    .heightIn(min = 50.dp)
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
            .focusRequester(focusRequester)
            .semantics { contentDescription = "Six-digit invite code" },
        decorationBox = { innerTextField ->
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    // The cells only draw the field's digits; TalkBack reads the field itself.
                    modifier = Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics { },
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
            .inviteCodeCellShape()
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
        // Read as one code, digit by digit, as on iOS.
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                if (code.isNotBlank()) {
                    contentDescription = "Invite code ${code.toList().joinToString(" ")}"
                }
            },
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        digits.forEach { digit ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .inviteCodeCellShape()
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

/**
 * An invite code cell's shape: a fixed portrait tile, which at large text sizes grows taller to
 * hold its digit rather than clipping it.
 */
@Composable
private fun Modifier.inviteCodeCellShape(): Modifier =
    if (isLargeText()) heightIn(min = 60.dp) else aspectRatio(0.84f)

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
        onCanvasEraseDrag = viewModel::onCanvasEraseDrag,
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
    onCanvasEraseDrag: (StrokePoint, StrokePoint) -> Unit,
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
                val clearEnabled = !uiState.canvasState.isEmpty
                FloatingActionButton(
                    onClick = { if (clearEnabled) onClearRequested() },
                    modifier = Modifier
                        .testTag(TestTags.CLEAR_BUTTON)
                        .alpha(if (clearEnabled) 1f else 0.45f)
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
                    viewportTransform = CanvasViewportTransform(),
                    onDrawStart = onCanvasPress,
                    onDrawPoint = onCanvasDrag,
                    onDrawEnd = onCanvasRelease,
                    onEraseTap = onCanvasTap,
                    onEraseDrag = onCanvasEraseDrag,
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
            .selectable(selected = isSelected, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = ColourNames.name(colorArgb) }
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = outlineColor,
                shape = CircleShape
            ),
        color = Color(colorArgb),
        shape = CircleShape
    ) {}
}

@Composable
private fun CustomColorSwatch(
    isSelected: Boolean,
    onClick: () -> Unit,
    size: Dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .selectable(selected = isSelected, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Custom colour" }
            .then(
                if (isSelected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_text_toolbar_color),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}

private fun HomePairingSheetMode.onboardingStep(): OnboardingStep? = when (this) {
    HomePairingSheetMode.ShareInvite -> OnboardingStep.INVITE_CODE_SHOWN
    HomePairingSheetMode.JoinCodeEntry -> OnboardingStep.REDEEM_CODE_ENTRY
    HomePairingSheetMode.PartnerDetails -> OnboardingStep.PARTNER_DETAILS
    HomePairingSheetMode.PairingConfirmed -> OnboardingStep.PAIRED_CELEBRATION
    // Asks an already paired person whether to switch partners; no contract step fits it.
    HomePairingSheetMode.InboundInvitePaired,
    HomePairingSheetMode.Hidden -> null
}

/**
 * Every colour, and the custom colour, in a sheet: the iPhone opens its palette the same way.
 * The chosen colour carries a check.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CanvasPaletteSheet(
    palette: List<Long>,
    selectedColorArgb: Long,
    showSelection: Boolean,
    onColorSelected: (Long) -> Unit,
    onCustomColourRequested: () -> Unit,
    onDismiss: () -> Unit
) {
    val haptics = rememberMulberryHaptics()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.mulberryAppColors.softSurface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 44.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(palette.size) { index ->
                val colorArgb = palette[index]
                val selected = showSelection && colorArgb == selectedColorArgb
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(colorArgb))
                        .border(1.dp, MaterialTheme.mulberryAppColors.mutedText.copy(alpha = 0.3f), CircleShape)
                        .selectable(
                            selected = selected,
                            role = Role.Button,
                            onClick = {
                                haptics.perform(MulberryHaptic.Selection)
                                onColorSelected(colorArgb)
                            }
                        )
                        .semantics { contentDescription = ColourNames.name(colorArgb) },
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Image(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(
                                if (Color(colorArgb).luminance() > 0.6f) Color.Black else Color.White
                            ),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
            item {
                CustomColorSwatch(
                    isSelected = showSelection && selectedColorArgb !in palette,
                    onClick = onCustomColourRequested,
                    size = 44.dp
                )
            }
        }
    }
}

/**
 * The width button and its slider. The width being dragged lives here, so a drag redraws only
 * this control: read any higher, it rebuilt the whole canvas pane on every movement. It is saved
 * once, as the finger lifts.
 */
@Composable
private fun BrushWidthControl(
    savedWidth: Float,
    colorArgb: Long,
    open: Boolean,
    onOpenChanged: (Boolean) -> Unit,
    onWidthChosen: (Float) -> Unit
) {
    var draftWidth by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(savedWidth) {
        if (draftWidth == savedWidth) draftWidth = null
    }
    val width = draftWidth ?: savedWidth
    Box {
        CanvasChromeButton(
            contentDescription = "Brush width",
            onClick = { onOpenChanged(true) },
            floating = true,
            modifier = Modifier
                .testTag(TestTags.BRUSH_WIDTH_BUTTON)
                .semantics { stateDescription = "${width.toInt()}" }
        ) { BrushWidthScribble(width = width) }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { onOpenChanged(false) },
            containerColor = MaterialTheme.mulberryAppColors.softSurface,
            modifier = Modifier.width(72.dp),
            properties = PopupProperties(focusable = true)
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 14.dp)
                    .height(220.dp)
                    .width(52.dp),
                contentAlignment = Alignment.Center
            ) {
                VerticalSlider(
                    value = width,
                    onValueChange = { draftWidth = it },
                    onValueChangeFinished = { draftWidth?.let(onWidthChosen) },
                    valueRange = DrawingDefaults.MIN_WIDTH..DrawingDefaults.MAX_WIDTH,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(colorArgb),
                        activeTrackColor = Color(colorArgb),
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

/**
 * A place in the canvas's right-hand column whose control the tool in hand has no use for: it
 * slides off to the side and fades, but keeps its place, so nothing above it moves. Hidden, it
 * takes no touches and TalkBack skips it. With animations removed it only fades.
 */
@Composable
private fun HudSlot(
    visible: Boolean,
    reduceMotion: Boolean,
    content: @Composable () -> Unit
) {
    val shown by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = if (reduceMotion) 150 else 240, easing = FastOutSlowInEasing),
        label = "hudSlot"
    )
    Box(
        modifier = Modifier
            .padding(bottom = CanvasChromeMetrics.ColumnSpacing)
            .graphicsLayer {
                alpha = shown
                translationX = if (reduceMotion) 0f else (1f - shown) * 64.dp.toPx()
            }
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics { })
    ) {
        content()
        if (!visible) {
            // Over the hidden control, so a tap where it was does nothing.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            )
        }
    }
}
