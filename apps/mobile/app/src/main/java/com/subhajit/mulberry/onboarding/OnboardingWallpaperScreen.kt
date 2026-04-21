package com.subhajit.mulberry.onboarding

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.ui.ApplySystemBarStyle
import com.subhajit.mulberry.core.ui.OnboardingLightSystemBars
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.ui.theme.MulberryError
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.wallpaper.WallpaperPreset
import com.subhajit.mulberry.wallpaper.WallpaperIntentFactory
import com.subhajit.mulberry.wallpaper.ui.WallpaperBackgroundSelectionSection
import com.subhajit.mulberry.wallpaper.ui.WallpaperLockScreenPreview
import kotlin.math.max

private val OnboardingBackground = Color.White
private val HeadingInk = Color(0xFF030A14)
private val MutedInk = Color(0xFF676A70)
private val UploadBackground = Color(0xFFFFEBED)
private val PreviewFrame = Color(0xFFD2D2D2)

@Composable
fun OnboardingWallpaperRoute(
    onNavigateHome: () -> Unit,
    viewModel: OnboardingWallpaperViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val backgroundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.onGalleryBackgroundSelected(uri)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                OnboardingWallpaperEffect.NavigateHome -> onNavigateHome()
                OnboardingWallpaperEffect.OpenWallpaperSetup ->
                    WallpaperIntentFactory.openWallpaperPicker(context)
            }
        }
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

    ApplySystemBarStyle(OnboardingLightSystemBars)

    OnboardingWallpaperScreen(
        uiState = uiState,
        presets = viewModel.presets,
        onHelp = {},
        onSetUpLockScreen = viewModel::onSetUpLockScreenClicked,
        onUploadFromGallery = {
            backgroundPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        onPresetSelected = viewModel::onPresetSelected,
        onAllDone = viewModel::onAllDoneClicked
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingWallpaperScreen(
    uiState: OnboardingWallpaperUiState,
    presets: List<WallpaperPreset>,
    onHelp: () -> Unit,
    onSetUpLockScreen: () -> Unit,
    onUploadFromGallery: () -> Unit,
    onPresetSelected: (Int) -> Unit,
    onAllDone: () -> Unit
) {
    val scrollState = rememberScrollState()
    val helpSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showHelp by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.wallpaperStatus.isWallpaperSelected) {
        if (uiState.wallpaperStatus.isWallpaperSelected) {
            withFrameNanos { }
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OnboardingBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .testTag(TestTags.ONBOARDING_WALLPAPER_SCREEN)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
                .padding(top = 38.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(40.dp)
        ) {
            WallpaperHeader(
                onHelp = {
                    onHelp()
                    showHelp = true
                }
            )

            val selectedPreviewResId = presets
                .firstOrNull { it.drawableResId == uiState.selectedPresetResId }
                ?.previewDrawableResId
                ?: R.drawable.wallpaper_default_bg_preview
            val previewAssetPath = if (uiState.selectedPresetResId == null) {
                uiState.backgroundImageState.assetPath
            } else {
                null
            }

            WallpaperLockScreenPreview(
                assetPath = previewAssetPath,
                assetUpdatedAt = uiState.backgroundImageState.lastUpdatedAt,
                fallbackBackgroundRes = selectedPreviewResId,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            if (!uiState.wallpaperStatus.isWallpaperSelected) {
                PrimaryOnboardingButton(
                    text = stringResource(R.string.onboarding_wallpaper_setup_button),
                    onClick = onSetUpLockScreen,
                    enabled = !uiState.isBusy,
                    modifier = Modifier.testTag(TestTags.ONBOARDING_WALLPAPER_SETUP_BUTTON)
                )
            }

            WallpaperBackgroundSelectionSection(
                presets = presets,
                selectedPresetResId = uiState.selectedPresetResId,
                onUploadFromGallery = onUploadFromGallery,
                onPresetSelected = onPresetSelected
            )

            WallpaperCompletionSection(
                isWallpaperSelected = uiState.wallpaperStatus.isWallpaperSelected,
                errorMessage = uiState.errorMessage,
                canComplete = uiState.canComplete && !uiState.isBusy,
                onAllDone = onAllDone
            )
        }

        if (uiState.isBusy) {
            CircularProgressIndicator(
                color = MulberryPrimary,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }

    if (showHelp) {
        ModalBottomSheet(
            onDismissRequest = { showHelp = false },
            sheetState = helpSheetState,
            containerColor = Color.White,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            WallpaperHelpSheet(onDismiss = { showHelp = false })
        }
    }
}

@Composable
private fun WallpaperCompletionSection(
    isWallpaperSelected: Boolean,
    errorMessage: String?,
    canComplete: Boolean,
    onAllDone: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (isWallpaperSelected) {
            Text(
                text = stringResource(R.string.onboarding_wallpaper_ready),
                color = MulberryPrimary,
                style = TextStyle(
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        errorMessage?.let { message ->
            Text(
                text = message,
                color = MulberryError,
                style = TextStyle(
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        PrimaryOnboardingButton(
            text = stringResource(R.string.onboarding_wallpaper_done),
            onClick = onAllDone,
            enabled = canComplete,
            modifier = Modifier.testTag(TestTags.ONBOARDING_WALLPAPER_DONE_BUTTON)
        )
    }
}

@Composable
private fun WallpaperHelpSheet(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(
            text = stringResource(R.string.onboarding_wallpaper_help_title),
            color = HeadingInk,
            style = TextStyle(
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 31.sp
            )
        )
        Text(
            text = stringResource(R.string.onboarding_wallpaper_help_body),
            color = HeadingInk.copy(alpha = 0.78f),
            style = TextStyle(
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                lineHeight = 24.sp
            )
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HelpStep(text = stringResource(R.string.onboarding_wallpaper_help_step_one))
            HelpStep(text = stringResource(R.string.onboarding_wallpaper_help_step_two))
            HelpStep(text = stringResource(R.string.onboarding_wallpaper_help_step_three))
        }
        PrimaryOnboardingButton(
            text = stringResource(R.string.onboarding_wallpaper_help_confirm),
            onClick = onDismiss,
            enabled = true
        )
    }
}

@Composable
private fun HelpStep(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(7.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(MulberryPrimary)
        )
        Text(
            text = text,
            color = HeadingInk.copy(alpha = 0.78f),
            style = TextStyle(
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 22.sp
            )
        )
    }
}

@Composable
private fun WallpaperHeader(
    onHelp: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = stringResource(R.string.onboarding_wallpaper_eyebrow),
                color = MulberryPrimary,
                style = TextStyle(
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    lineHeight = 27.sp
                )
            )
            Text(
                text = stringResource(R.string.onboarding_wallpaper_help),
                color = MutedInk,
                style = TextStyle(
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    lineHeight = 24.sp
                ),
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onHelp
                    )
                    .padding(start = 16.dp, bottom = 16.dp)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.onboarding_wallpaper_title),
                color = HeadingInk,
                style = TextStyle(
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 28.sp,
                    lineHeight = 32.sp
                )
            )
            Text(
                text = stringResource(R.string.onboarding_wallpaper_description),
                color = HeadingInk,
                style = TextStyle(
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 15.sp,
                    lineHeight = 25.sp
                )
            )
        }
    }
}

@Composable
private fun PrimaryOnboardingButton(
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
