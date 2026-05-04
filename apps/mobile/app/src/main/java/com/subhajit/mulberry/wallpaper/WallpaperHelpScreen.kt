package com.subhajit.mulberry.wallpaper

import android.provider.Settings
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.ui.ApplySystemBarStyle
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.core.ui.metadata.AppSystemBarStyle
import com.subhajit.mulberry.core.ui.mulberryTapScale
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

private const val FigmaBaseWidthDp = 402f
private val WallpaperHelpBackgroundColor = Color(0xFF350007)
private val WallpaperHelpBodyTextColor = Color.White.copy(alpha = 0.8f)
private val WallpaperHelpTimelineCircleFill = Color(0xFFCF1D35)
private val WallpaperHelpTimelineCircleStroke = Color(0xFFE3E3E3)
private val WallpaperHelpTimelineLine = Color(0xFF800E1E)
private val WallpaperHelpCtaTextColor = Color(0xFF595959)
private const val WallpaperHelpHeartAspectRatio = 804f / 716f
private const val WallpaperHelpHeartOverscale = 1.08f
private val WallpaperHelpHeartTopTrim = (-22).dp

private val WallpaperHelpPillTop = 226.dp
private val WallpaperHelpTitleTop = 281.dp
private val WallpaperHelpBodyTop = 377.dp
private val WallpaperHelpTimelineGap = 28.dp
private val WallpaperHelpTimelineCircleRadius = 8.5.dp
private val WallpaperHelpTimelineLineWidth = 2.dp
private val WallpaperHelpTimelineTextWidth = 260.dp

private const val EntryBackgroundDurationMs = 220
private const val EntryContentOverlapDelayMs = 165
private const val EntryCloseDurationMs = 90
private const val EntryContentDurationMs = 140
private const val EntryStaggerMs = 55
private val EntryContentStartOffsetDp = 8.dp
private const val EntryContentMinAlpha = 0.9f

private data class SystemAnimationSettings(
    val enabled: Boolean,
    val durationScale: Float
)

@Composable
private fun rememberSystemAnimationSettings(): SystemAnimationSettings {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            val windowScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.WINDOW_ANIMATION_SCALE,
                1f
            )
            val transitionScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1f
            )
            val animatorScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
            SystemAnimationSettings(
                enabled = windowScale != 0f && transitionScale != 0f && animatorScale != 0f,
                durationScale = animatorScale
            )
        }.getOrDefault(SystemAnimationSettings(enabled = true, durationScale = 1f))
    }
}

@Composable
fun WallpaperHelpRoute(
    onClose: () -> Unit,
    onExitToMain: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var launchedWallpaperPicker by remember { mutableStateOf(false) }
    var pausedAfterLaunchingPicker by remember { mutableStateOf(false) }
    var exitRequested by remember { mutableStateOf(false) }

    LaunchedEffect(exitRequested) {
        if (!exitRequested) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        onExitToMain()
        exitRequested = false
    }

    DisposableEffect(lifecycleOwner, launchedWallpaperPicker) {
        if (!launchedWallpaperPicker) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                pausedAfterLaunchingPicker = true
            }
            if (event == Lifecycle.Event.ON_RESUME && pausedAfterLaunchingPicker) {
                exitRequested = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    ApplySystemBarStyle(
        style = AppSystemBarStyle(
            statusBarColorArgb = 0x00000000,
            navigationBarColorArgb = 0x00000000,
            useDarkIcons = false
        )
    )
    WallpaperHelpScreen(
        onClose = onClose,
        onSetUpWallpaper = {
            launchedWallpaperPicker = true
            pausedAfterLaunchingPicker = false
            WallpaperIntentFactory.openWallpaperPicker(context)
        }
    )
}

@Composable
private fun WallpaperHelpScreen(
    onClose: () -> Unit,
    onSetUpWallpaper: () -> Unit
) {
    val systemAnimationSettings = rememberSystemAnimationSettings()
    val systemAnimationsEnabled = systemAnimationSettings.enabled
    val durationScaleNormalizationFactor = systemAnimationSettings.durationScale
        .takeIf { it > 1f }
        ?: 1f

    fun tweenDurationMs(ms: Int): Int =
        (ms / durationScaleNormalizationFactor).toInt().coerceAtLeast(1)

    fun delayDurationMs(ms: Int): Long =
        (ms / durationScaleNormalizationFactor).toLong().coerceAtLeast(0L)

    var closeEntered by remember { mutableStateOf(!systemAnimationsEnabled) }
    var heartEntered by remember { mutableStateOf(!systemAnimationsEnabled) }
    var pillEntered by remember { mutableStateOf(!systemAnimationsEnabled) }
    var titleEntered by remember { mutableStateOf(!systemAnimationsEnabled) }
    var bodyEntered by remember { mutableStateOf(!systemAnimationsEnabled) }
    var bulletsEntered by remember { mutableStateOf(!systemAnimationsEnabled) }
    var noteEntered by remember { mutableStateOf(!systemAnimationsEnabled) }
    var ctaEntered by remember { mutableStateOf(!systemAnimationsEnabled) }
    var startNanos by remember { mutableLongStateOf(0L) }

    LaunchedEffect(systemAnimationsEnabled) {
        if (!systemAnimationsEnabled) return@LaunchedEffect
        startNanos = 0L
        closeEntered = false
        heartEntered = false
        pillEntered = false
        titleEntered = false
        bodyEntered = false
        bulletsEntered = false
        noteEntered = false
        ctaEntered = false

        closeEntered = true
        startNanos = androidx.compose.runtime.withFrameNanos { it }
    }

    LaunchedEffect(systemAnimationsEnabled, startNanos) {
        if (!systemAnimationsEnabled) return@LaunchedEffect
        if (ctaEntered) return@LaunchedEffect

        val now = androidx.compose.runtime.withFrameNanos { it }
        val elapsedMs = if (startNanos == 0L) {
            0L
        } else {
            (now - startNanos) / 1_000_000L
        }
        val remainingDelayMs = (EntryContentOverlapDelayMs - elapsedMs).coerceAtLeast(0L)
        if (remainingDelayMs > 0L) delay(delayDurationMs(remainingDelayMs.toInt()))

        heartEntered = true
        delay(delayDurationMs(EntryStaggerMs))
        pillEntered = true
        delay(delayDurationMs(EntryStaggerMs))
        titleEntered = true
        delay(delayDurationMs(EntryStaggerMs))
        bodyEntered = true
        delay(delayDurationMs(EntryStaggerMs))
        bulletsEntered = true
        delay(delayDurationMs(EntryStaggerMs))
        noteEntered = true
        delay(delayDurationMs(EntryStaggerMs))
        ctaEntered = true
    }

    val closeAlpha by animateFloatAsState(
        targetValue = if (closeEntered) 1f else 0f,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryCloseDurationMs), easing = FastOutLinearInEasing),
        label = "wallpaperHelpCloseAlpha"
    )

    val heartAlpha by animateFloatAsState(
        targetValue = if (heartEntered) 1f else EntryContentMinAlpha,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = LinearOutSlowInEasing),
        label = "wallpaperHelpHeartAlpha"
    )
    val pillAlpha by animateFloatAsState(
        targetValue = if (pillEntered) 1f else EntryContentMinAlpha,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = LinearOutSlowInEasing),
        label = "wallpaperHelpPillAlpha"
    )
    val titleAlpha by animateFloatAsState(
        targetValue = if (titleEntered) 1f else EntryContentMinAlpha,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = LinearOutSlowInEasing),
        label = "wallpaperHelpTitleAlpha"
    )
    val bodyAlpha by animateFloatAsState(
        targetValue = if (bodyEntered) 1f else EntryContentMinAlpha,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = LinearOutSlowInEasing),
        label = "wallpaperHelpBodyAlpha"
    )
    val bulletsAlpha by animateFloatAsState(
        targetValue = if (bulletsEntered) 1f else EntryContentMinAlpha,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = LinearOutSlowInEasing),
        label = "wallpaperHelpBulletsAlpha"
    )
    val noteAlpha by animateFloatAsState(
        targetValue = if (noteEntered) 1f else EntryContentMinAlpha,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = LinearOutSlowInEasing),
        label = "wallpaperHelpNoteAlpha"
    )
    val ctaAlpha by animateFloatAsState(
        targetValue = if (ctaEntered) 1f else EntryContentMinAlpha,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = LinearOutSlowInEasing),
        label = "wallpaperHelpCtaAlpha"
    )

    val heartOffsetFactor by animateFloatAsState(
        targetValue = if (heartEntered) 0f else 1f,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = FastOutSlowInEasing),
        label = "wallpaperHelpHeartOffset"
    )
    val pillOffsetFactor by animateFloatAsState(
        targetValue = if (pillEntered) 0f else 1f,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = FastOutSlowInEasing),
        label = "wallpaperHelpPillOffset"
    )
    val titleOffsetFactor by animateFloatAsState(
        targetValue = if (titleEntered) 0f else 1f,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = FastOutSlowInEasing),
        label = "wallpaperHelpTitleOffset"
    )
    val bodyOffsetFactor by animateFloatAsState(
        targetValue = if (bodyEntered) 0f else 1f,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = FastOutSlowInEasing),
        label = "wallpaperHelpBodyOffset"
    )
    val bulletsOffsetFactor by animateFloatAsState(
        targetValue = if (bulletsEntered) 0f else 1f,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = FastOutSlowInEasing),
        label = "wallpaperHelpBulletsOffset"
    )
    val noteOffsetFactor by animateFloatAsState(
        targetValue = if (noteEntered) 0f else 1f,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = FastOutSlowInEasing),
        label = "wallpaperHelpNoteOffset"
    )
    val ctaOffsetFactor by animateFloatAsState(
        targetValue = if (ctaEntered) 0f else 1f,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = FastOutSlowInEasing),
        label = "wallpaperHelpCtaOffset"
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(WallpaperHelpBackgroundColor)
            .testTag(TestTags.WALLPAPER_HELP_SCREEN)
    ) {
        val scale = maxWidth.value / FigmaBaseWidthDp
        val driftHeart = EntryContentStartOffsetDp * scale * heartOffsetFactor
        val driftPill = EntryContentStartOffsetDp * scale * pillOffsetFactor
        val driftTitle = EntryContentStartOffsetDp * scale * titleOffsetFactor
        val driftBody = EntryContentStartOffsetDp * scale * bodyOffsetFactor
        val driftBullets = EntryContentStartOffsetDp * scale * bulletsOffsetFactor
        val driftNote = EntryContentStartOffsetDp * scale * noteOffsetFactor
        val driftCta = EntryContentStartOffsetDp * scale * ctaOffsetFactor

        Image(
            painter = painterResource(R.drawable.wallpaper_help_heart),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = WallpaperHelpHeartTopTrim * scale + driftHeart)
                .fillMaxWidth()
                .aspectRatio(WallpaperHelpHeartAspectRatio)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    scaleX = WallpaperHelpHeartOverscale
                    scaleY = WallpaperHelpHeartOverscale
                }
                .graphicsLayer { alpha = if (heartEntered) heartAlpha else 0f }
        )

        Image(
            painter = painterResource(R.drawable.ic_streak_close),
            contentDescription = stringResource(R.string.wallpaper_help_close),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .offset(
                    x = maxWidth - 20.dp * scale - 25.dp * scale,
                    y = 75.dp * scale
                )
                .size(25.dp * scale)
                .mulberryTapScale()
                .clickable(onClick = onClose)
                .graphicsLayer { alpha = closeAlpha }
        )

        WallpaperHelpPill(
            scale = scale,
            modifier = Modifier
                .offset(x = 103.dp * scale, y = WallpaperHelpPillTop * scale + driftPill)
                .graphicsLayer { alpha = if (pillEntered) pillAlpha else 0f }
        )

        Text(
            text = stringResource(R.string.wallpaper_help_title),
            color = Color.White,
            style = TextStyle(
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 32.sp,
                lineHeight = 42.sp,
                letterSpacing = 0.25.sp
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .offset(x = 18.dp * scale, y = WallpaperHelpTitleTop * scale + driftTitle)
                .width(366.dp * scale)
                .graphicsLayer { alpha = if (titleEntered) titleAlpha else 0f }
        )

        Text(
            text = stringResource(R.string.wallpaper_help_body),
            color = WallpaperHelpBodyTextColor,
            style = TextStyle(
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.25.sp
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .offset(x = 36.dp * scale, y = WallpaperHelpBodyTop * scale + driftBody)
                .width(330.dp * scale)
                .graphicsLayer { alpha = if (bodyEntered) bodyAlpha else 0f }
        )

        WallpaperHelpBulletList(
            scale = scale,
            modifier = Modifier
                .offset(x = 55.dp * scale, y = 491.dp * scale + driftBullets)
                .graphicsLayer { alpha = if (bulletsEntered) bulletsAlpha else 0f }
        )

        Text(
            text = stringResource(R.string.wallpaper_help_note),
            color = Color.White,
            style = TextStyle(
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.25.sp
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .offset(x = 48.5.dp * scale, y = 732.dp * scale + driftNote)
                .width(305.dp * scale)
                .graphicsLayer { alpha = if (noteEntered) noteAlpha else 0f }
        )

        WallpaperHelpCtaButton(
            scale = scale,
            onClick = onSetUpWallpaper,
            modifier = Modifier
                .offset(x = 20.dp * scale, y = 787.dp * scale + driftCta)
                .graphicsLayer { alpha = if (ctaEntered) ctaAlpha else 0f }
                .testTag(TestTags.WALLPAPER_HELP_SETUP_BUTTON)
        )
    }
}

@Composable
private fun WallpaperHelpPill(
    scale: Float,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp * scale)
    BoxWithConstraints(modifier = modifier.size(width = 197.dp * scale, height = 26.dp * scale)) {
        val brush = Brush.linearGradient(colors = listOf(Color(0xFFA0730C), Color(0xFFDCA612)))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(brush),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.wallpaper_help_pill),
                color = Color.White,
                style = TextStyle(
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    letterSpacing = 0.25.sp
                )
            )
        }
    }
}

@Composable
private fun WallpaperHelpBulletList(
    scale: Float,
    modifier: Modifier = Modifier
) {
    val textStyle = TextStyle(
        fontFamily = PoppinsFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    )

    val containerOffsetInRoot = remember { mutableStateOf(Offset.Zero) }
    val circleCentersInRoot = remember { mutableStateListOf<Offset?>(null, null, null) }

    Box(
        modifier = modifier.onGloballyPositioned { coords ->
            containerOffsetInRoot.value = coords.positionInRoot()
        }
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val centers = circleCentersInRoot.filterNotNull()
            if (centers.size >= 2) {
                val x = centers.first().x - containerOffsetInRoot.value.x
                val minY = centers.minOf { it.y } - containerOffsetInRoot.value.y
                val maxY = centers.maxOf { it.y } - containerOffsetInRoot.value.y
                drawLine(
                    color = WallpaperHelpTimelineLine,
                    start = Offset(x, minY),
                    end = Offset(x, maxY),
                    strokeWidth = WallpaperHelpTimelineLineWidth.toPx() * scale,
                    cap = StrokeCap.Butt
                )
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(WallpaperHelpTimelineGap * scale)
        ) {
            WallpaperHelpTimelineRow(
                text = stringResource(R.string.wallpaper_help_bullet_one),
                scale = scale,
                style = textStyle,
                onCircleCenterInRoot = { circleCentersInRoot[0] = it }
            )
            WallpaperHelpTimelineRow(
                text = stringResource(R.string.wallpaper_help_bullet_two),
                scale = scale,
                style = textStyle,
                onCircleCenterInRoot = { circleCentersInRoot[1] = it }
            )
            WallpaperHelpTimelineRow(
                text = stringResource(R.string.wallpaper_help_bullet_three),
                scale = scale,
                style = textStyle,
                onCircleCenterInRoot = { circleCentersInRoot[2] = it }
            )
        }
    }
}

@Composable
private fun WallpaperHelpTimelineRow(
    text: String,
    scale: Float,
    style: TextStyle,
    onCircleCenterInRoot: (Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.width(18.dp * scale),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Canvas(
                modifier = Modifier
                    .size((WallpaperHelpTimelineCircleRadius * 2f) * scale)
                    .onGloballyPositioned { coords ->
                        val pos = coords.positionInRoot()
                        val center = Offset(
                            x = pos.x + coords.size.width / 2f,
                            y = pos.y + coords.size.height / 2f
                        )
                        onCircleCenterInRoot(center)
                    }
            ) {
                val strokeWidth = 1.dp.toPx() * scale
                val radiusPx = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(
                    color = WallpaperHelpTimelineCircleFill,
                    radius = radiusPx,
                    center = center
                )
                drawCircle(
                    color = WallpaperHelpTimelineCircleStroke,
                    radius = radiusPx,
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        Spacer(modifier = Modifier.width(32.dp * scale))

        Text(
            text = text,
            color = WallpaperHelpBodyTextColor,
            style = style,
            modifier = Modifier.width(WallpaperHelpTimelineTextWidth * scale)
        )
    }
}

@Composable
private fun WallpaperHelpCtaButton(
    scale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(15.385.dp * scale)
    Row(
        modifier = modifier
            .size(width = 362.dp * scale, height = 50.dp * scale)
            .clip(shape)
            .background(Color.White)
            .border(0.962.dp * scale, Color(0xFFF5F5F5), shape)
            .mulberryTapScale()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.wallpaper_help_cta),
            color = WallpaperHelpCtaTextColor,
            style = TextStyle(
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.25.sp
            )
        )
    }
}
