package com.subhajit.mulberry.streak

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.core.ui.ApplySystemBarStyle
import com.subhajit.mulberry.core.ui.metadata.AppSystemBarStyle
import com.subhajit.mulberry.network.StreakResponse
import com.subhajit.mulberry.ui.theme.KalamFontFamily
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import java.time.LocalDate
import kotlin.math.absoluteValue

private val StreakScreenBackgroundColor = Color(0xFF370108)
private val StreakScreenEllipseColor = Color(0xFF52020C)
private val StreakScreenAccentColor = Color(0xFFB31329)
private val StreakScreenMutedCircleColor = Color(0xFF4B2227)
private val StreakScreenBodyTextColor = Color.White.copy(alpha = 0.7f)
private val StreakScreenMutedTextColor = Color.White.copy(alpha = 0.4f)
private const val HeroSizeFraction = 0.8f
private const val HeroBottomMarginDp = 24f
private const val EntryBackgroundDurationMs = 220
private const val EntryContentOverlapDelayMs = 165
private const val EntryCloseDurationMs = 90
private const val EntryContentDurationMs = 140
private const val EntryStaggerMs = 55
private val EntryBackgroundStartOffsetDp = 24.dp
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
fun StreakRoute(
    onClose: () -> Unit,
    viewModel: StreakViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.load() }
    StreakScreen(uiState = uiState, onClose = onClose)
}

@Composable
private fun StreakScreen(
    uiState: StreakUiState,
    onClose: () -> Unit
) {
    val streak = uiState.streak
    val content = streak?.toStreakContent()
    val hasForegroundContent = content != null || uiState.errorMessage != null
    val systemAnimationSettings = rememberSystemAnimationSettings()
    val systemAnimationsEnabled = systemAnimationSettings.enabled
    val durationScaleNormalizationFactor = systemAnimationSettings.durationScale
        .takeIf { it > 1f }
        ?: 1f

    fun tweenDurationMs(ms: Int): Int =
        (ms / durationScaleNormalizationFactor).toInt().coerceAtLeast(1)

    fun delayDurationMs(ms: Int): Long =
        (ms / durationScaleNormalizationFactor).toLong().coerceAtLeast(0L)

    val showHero = content != null
    val showPill = content != null
    val showWeekRow = content != null

    var backgroundEntered by remember { mutableStateOf(!systemAnimationsEnabled) }
    var closeEntered by remember { mutableStateOf(!systemAnimationsEnabled) }
    var heroEntered by remember { mutableStateOf(!systemAnimationsEnabled) }
    var pillEntered by remember { mutableStateOf(!systemAnimationsEnabled) }
    var weekRowEntered by remember { mutableStateOf(!systemAnimationsEnabled) }
    var copyEntered by remember { mutableStateOf(!systemAnimationsEnabled) }
    var continueEntered by remember { mutableStateOf(!systemAnimationsEnabled) }
    var backgroundStartNanos by remember { mutableLongStateOf(0L) }

    ApplySystemBarStyle(
        style = AppSystemBarStyle(
            statusBarColorArgb = 0x00000000,
            navigationBarColorArgb = 0x00000000,
            useDarkIcons = false
        )
    )

    LaunchedEffect(systemAnimationsEnabled) {
        if (!systemAnimationsEnabled) return@LaunchedEffect
        backgroundStartNanos = 0L
        closeEntered = false
        backgroundEntered = false
        heroEntered = false
        pillEntered = false
        weekRowEntered = false
        copyEntered = false
        continueEntered = false

        closeEntered = true
        backgroundStartNanos = androidx.compose.runtime.withFrameNanos { it }
        backgroundEntered = true
    }

    LaunchedEffect(hasForegroundContent, showHero, showPill, showWeekRow, systemAnimationsEnabled, backgroundStartNanos) {
        if (!systemAnimationsEnabled) return@LaunchedEffect
        if (!hasForegroundContent) return@LaunchedEffect
        if (continueEntered) return@LaunchedEffect

        val now = androidx.compose.runtime.withFrameNanos { it }
        val elapsedMs = if (backgroundStartNanos == 0L) {
            0L
        } else {
            (now - backgroundStartNanos) / 1_000_000L
        }
        val remainingDelayMs = (EntryContentOverlapDelayMs - elapsedMs).coerceAtLeast(0L)
        if (remainingDelayMs > 0L) kotlinx.coroutines.delay(delayDurationMs(remainingDelayMs.toInt()))

        if (showHero) {
            heroEntered = true
            kotlinx.coroutines.delay(delayDurationMs(EntryStaggerMs))
        }
        if (showPill) {
            pillEntered = true
            kotlinx.coroutines.delay(delayDurationMs(EntryStaggerMs))
        }
        if (showWeekRow) {
            weekRowEntered = true
            kotlinx.coroutines.delay(delayDurationMs(EntryStaggerMs))
        }
        copyEntered = true
        kotlinx.coroutines.delay(delayDurationMs(EntryStaggerMs))
        continueEntered = true
    }

    val closeAlpha by animateFloatAsState(
        targetValue = if (closeEntered) 1f else 0f,
        animationSpec = tween(
            durationMillis = tweenDurationMs(EntryCloseDurationMs),
            easing = FastOutLinearInEasing
        ),
        label = "streakEntryCloseAlpha"
    )

    val backgroundOffsetFactor by animateFloatAsState(
        targetValue = if (backgroundEntered) 0f else 1f,
        animationSpec = tween(
            durationMillis = tweenDurationMs(EntryBackgroundDurationMs),
            easing = FastOutSlowInEasing
        ),
        label = "streakEntryBackgroundOffsetFactor"
    )

    val heroAlpha by animateFloatAsState(
        targetValue = if (heroEntered) 1f else EntryContentMinAlpha,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = LinearOutSlowInEasing),
        label = "streakEntryHeroAlpha"
    )
    val pillAlpha by animateFloatAsState(
        targetValue = if (pillEntered) 1f else EntryContentMinAlpha,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = LinearOutSlowInEasing),
        label = "streakEntryPillAlpha"
    )
    val weekRowAlpha by animateFloatAsState(
        targetValue = if (weekRowEntered) 1f else EntryContentMinAlpha,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = LinearOutSlowInEasing),
        label = "streakEntryWeekRowAlpha"
    )
    val copyAlpha by animateFloatAsState(
        targetValue = if (copyEntered) 1f else EntryContentMinAlpha,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = LinearOutSlowInEasing),
        label = "streakEntryCopyAlpha"
    )
    val continueAlpha by animateFloatAsState(
        targetValue = if (continueEntered) 1f else EntryContentMinAlpha,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = LinearOutSlowInEasing),
        label = "streakEntryContinueAlpha"
    )

    val heroOffsetFactor by animateFloatAsState(
        targetValue = if (heroEntered) 0f else 1f,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = FastOutSlowInEasing),
        label = "streakEntryHeroOffsetFactor"
    )
    val pillOffsetFactor by animateFloatAsState(
        targetValue = if (pillEntered) 0f else 1f,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = FastOutSlowInEasing),
        label = "streakEntryPillOffsetFactor"
    )
    val weekRowOffsetFactor by animateFloatAsState(
        targetValue = if (weekRowEntered) 0f else 1f,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = FastOutSlowInEasing),
        label = "streakEntryWeekRowOffsetFactor"
    )
    val copyOffsetFactor by animateFloatAsState(
        targetValue = if (copyEntered) 0f else 1f,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = FastOutSlowInEasing),
        label = "streakEntryCopyOffsetFactor"
    )
    val continueOffsetFactor by animateFloatAsState(
        targetValue = if (continueEntered) 0f else 1f,
        animationSpec = tween(durationMillis = tweenDurationMs(EntryContentDurationMs), easing = FastOutSlowInEasing),
        label = "streakEntryContinueOffsetFactor"
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(StreakScreenBackgroundColor)
    ) {
        val scale = maxWidth.value / 402f

        StreakBackground(
            scale = scale,
            entryOffsetDp = EntryBackgroundStartOffsetDp * scale * backgroundOffsetFactor
        )

        if (content != null) {
            val heroSize = maxWidth * HeroSizeFraction
            val heroBottomTarget = 470.dp * scale - HeroBottomMarginDp.dp * scale
            val heroTop = (heroBottomTarget - heroSize).coerceAtLeast(0.dp)
            val drift = EntryContentStartOffsetDp * scale * heroOffsetFactor
            StreakHeroImage(
                hero = content.hero,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = heroTop + drift)
                    .size(heroSize)
                    .graphicsLayer { alpha = if (heroEntered) heroAlpha else 0f }
                    .testTag(TestTags.STREAK_HERO_IMAGE)
            )
        }

        if (content != null) {
            val drift = EntryContentStartOffsetDp * scale * pillOffsetFactor
            StreakPill(
                currentStreakDays = content.currentStreakDays,
                scale = scale,
                modifier = Modifier
                    .offset(x = 243.dp * scale, y = 72.dp * scale + drift)
                    .graphicsLayer { alpha = if (pillEntered) pillAlpha else 0f }
            )
        }

        StreakCloseButton(
            onClick = onClose,
            scale = scale,
            modifier = Modifier
                .offset(
                    x = maxWidth - 20.dp * scale - 25.dp * scale,
                    y = 75.dp * scale
                )
                .graphicsLayer { alpha = closeAlpha }
        )

        if (content != null) {
            val driftWeekRow = EntryContentStartOffsetDp * scale * weekRowOffsetFactor
            FigmaWeekRow(
                streak = streak,
                scale = scale,
                modifier = Modifier
                    .offset(x = 45.dp * scale, y = 470.dp * scale + driftWeekRow)
                    .graphicsLayer { alpha = if (weekRowEntered) weekRowAlpha else 0f }
            )

            val driftCopy = EntryContentStartOffsetDp * scale * copyOffsetFactor
            Column(
                modifier = Modifier
                    .offset(x = 65.dp * scale, y = 565.dp * scale + driftCopy)
                    .width(273.dp * scale),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = content.title,
                    color = Color.White,
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 24.sp,
                    lineHeight = 20.sp,
                    letterSpacing = 0.25.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = if (copyEntered) copyAlpha else 0f }
                )
                Spacer(modifier = Modifier.height(16.dp * scale))
                Text(
                    text = content.body,
                    color = StreakScreenBodyTextColor,
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    letterSpacing = 0.25.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = if (copyEntered) copyAlpha else 0f }
                )
            }
        } else if (uiState.errorMessage != null) {
            val driftCopy = EntryContentStartOffsetDp * scale * copyOffsetFactor
            Text(
                text = uiState.errorMessage,
                color = StreakScreenBodyTextColor,
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .offset(x = 65.dp * scale, y = 565.dp * scale + driftCopy)
                    .width(273.dp * scale)
                    .graphicsLayer { alpha = if (copyEntered) copyAlpha else 0f }
            )
        }

        val driftContinue = EntryContentStartOffsetDp * scale * continueOffsetFactor
        StreakContinueButton(
            onClick = onClose,
            scale = scale,
            modifier = Modifier
                .offset(x = 20.dp * scale, y = 782.dp * scale + driftContinue)
                .graphicsLayer { alpha = if (continueEntered) continueAlpha else 0f }
        )
    }
}

@Composable
private fun StreakBackground(
    scale: Float,
    entryOffsetDp: androidx.compose.ui.unit.Dp
) {
    val density = LocalDensity.current
    Canvas(modifier = Modifier.fillMaxSize()) {
        val ovalWidth = with(density) { (733.dp * scale).toPx() }
        val ovalHeight = with(density) { (635.dp * scale).toPx() }
        val top = with(density) { (387.dp * scale + entryOffsetDp).toPx() }
        val left = with(density) { (-165.dp * scale).toPx() }
        drawOval(
            color = StreakScreenEllipseColor,
            topLeft = Offset(left, top),
            size = Size(ovalWidth, ovalHeight)
        )
    }
}

@Composable
private fun StreakPill(
    currentStreakDays: Int,
    scale: Float,
    modifier: Modifier = Modifier
) {
    val label = if (currentStreakDays == 1) "1 day" else "$currentStreakDays days"
    val shape = RoundedCornerShape(43.dp * scale)

    Row(
        modifier = modifier
            .size(width = 92.dp * scale, height = 31.dp * scale)
            .border(1.dp * scale, StreakScreenAccentColor, shape)
            .background(StreakScreenAccentColor.copy(alpha = 0.15f), shape),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp * scale),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.ic_home_streak_bolt),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = 13.dp * scale, height = 20.dp * scale)
            )
            Text(
                text = label,
                color = Color.White,
                fontFamily = KalamFontFamily,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.25.sp
            )
        }
    }
}

@Composable
private fun StreakCloseButton(
    onClick: () -> Unit,
    scale: Float,
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(R.drawable.ic_streak_close),
        contentDescription = "Close",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(25.dp * scale)
            .clickable(onClick = onClick)
    )
}

@Composable
private fun StreakHeroImage(
    hero: StreakHero,
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(hero.imageRes),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
    )
}

@Composable
private fun FigmaWeekRow(
    streak: StreakResponse,
    scale: Float,
    modifier: Modifier = Modifier
) {
    val dayLabels = listOf("S", "M", "T", "W", "T", "F", "S")
    val today = streak.today

    Box(
        modifier = modifier.size(width = 313.dp * scale, height = 55.dp * scale)
    ) {
        val xPositions = listOf(0, 47, 94, 141, 188, 235, 282)
        streak.week.take(7).forEachIndexed { index, day ->
            val x = xPositions.getOrElse(index) { index * 47 }
            val label = dayLabels.getOrElse(index) { "" }
            val isToday = day.day == today
            val isFuture = day.day > today
            val isPast = day.day < today
            val hasActivity = day.hasActivity

            val style = when {
                isFuture -> DayMarkStyle.Ring
                isToday && hasActivity -> DayMarkStyle.Checked
                isToday && !hasActivity -> DayMarkStyle.TodayRing
                isPast && hasActivity -> DayMarkStyle.Checked
                else -> DayMarkStyle.Missed
            }

            val letterColor = if (isPast && !hasActivity) {
                StreakScreenMutedTextColor
            } else {
                Color.White
            }

            Box(
                modifier = Modifier
                    .offset(x = x.dp * scale, y = 0.dp)
                    .size(width = 31.dp * scale, height = 55.dp * scale)
            ) {
                Text(
                    text = label,
                    color = letterColor,
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    letterSpacing = 0.25.sp,
                    modifier = Modifier.offset(x = dayLetterOffsetX(label).dp * scale, y = 0.dp)
                )

                DayMark(style = style, scale = scale, modifier = Modifier.offset(x = 0.dp, y = 24.dp * scale))
            }
        }
    }
}

private enum class DayMarkStyle { Checked, Missed, Ring, TodayRing }

@Composable
private fun DayMark(
    style: DayMarkStyle,
    scale: Float,
    modifier: Modifier = Modifier
) {
    val fillBase = StreakScreenMutedCircleColor
    val ringStroke = when (style) {
        DayMarkStyle.TodayRing -> 3.dp * scale
        DayMarkStyle.Ring -> 2.dp * scale
        else -> 0.dp
    }
    val ringColor = StreakScreenAccentColor
    val fillColor = when (style) {
        DayMarkStyle.Checked -> StreakScreenAccentColor
        DayMarkStyle.Missed -> fillBase
        DayMarkStyle.Ring, DayMarkStyle.TodayRing -> fillBase
    }

    Box(
        modifier = modifier
            .size(31.dp * scale)
            .clip(CircleShape)
            .background(fillColor)
            .then(
                when (style) {
                    DayMarkStyle.Ring, DayMarkStyle.TodayRing -> Modifier.border(ringStroke, ringColor, CircleShape)
                    else -> Modifier.border(2.dp * scale, fillColor, CircleShape)
                }
            )
    ) {
        when (style) {
            DayMarkStyle.Checked -> CheckMark(
                scale = scale,
                modifier = Modifier.offset(x = 10.dp * scale, y = 12.dp * scale)
            )
            DayMarkStyle.Missed -> XMark(scale = scale, modifier = Modifier.offset(x = 8.dp * scale, y = 8.dp * scale))
            DayMarkStyle.Ring, DayMarkStyle.TodayRing -> Unit
        }
    }
}

@Composable
private fun CheckMark(
    scale: Float,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .size(width = 11.dp * scale, height = 7.dp * scale)
    ) {
        val path = Path().apply {
            moveTo(size.width * (11.75f / 12.5f), size.height * (0.75f / 8.5f))
            lineTo(size.width * (4.1875f / 12.5f), size.height * (7.75f / 8.5f))
            lineTo(size.width * (0.75f / 12.5f), size.height * (4.56819f / 8.5f))
        }
        drawPath(
            path = path,
            color = Color.White,
            style = Stroke(
                width = (1.5.dp * scale).toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

@Composable
private fun XMark(
    scale: Float,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.size(15.556348.dp * scale)
    ) {
        val stroke = Stroke(width = (2.dp * scale).toPx(), cap = StrokeCap.Round)
        val color = StreakScreenMutedTextColor
        drawLine(
            color = color,
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(size.width, 0f),
            end = Offset(0f, size.height),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round
        )
    }
}

private fun dayLetterOffsetX(label: String): Int {
    return when (label) {
        "M" -> 9
        "W" -> 8
        "F" -> 12
        else -> 11
    }
}

private enum class StreakHero(val imageRes: Int) {
    Bolt(R.drawable.streak_bolt_large),
    BrokenBolt(R.drawable.streak_bolt_broken_large)
}

private data class StreakContent(
    val currentStreakDays: Int,
    val title: String,
    val body: String,
    val hero: StreakHero
)

private fun StreakResponse.toStreakContent(): StreakContent {
    val today = LocalDate.parse(today)
    val lastActivityDay = lastActivityDay?.let(LocalDate::parse)
    val lostRecently = currentStreakDays == 0 &&
        lastActivityDay != null &&
        lastActivityDay == today.minusDays(2) &&
        previousStreakDays > 0

    val variant = when {
        lostRecently -> CopyVariant.Lost(previousStreakDays = previousStreakDays)
        currentStreakDays > 0 -> CopyVariant.Active(
            currentStreakDays = currentStreakDays,
            hasActivityToday = hasActivityToday
        )
        else -> CopyVariant.NoStreak
    }

    val seed = (today.toString().hashCode() * 31 + currentStreakDays * 7 + previousStreakDays * 13).absoluteValue
    val copy = variant.build(seed)

    return StreakContent(
        currentStreakDays = currentStreakDays,
        title = copy.title,
        body = copy.body,
        hero = if (lostRecently) StreakHero.BrokenBolt else StreakHero.Bolt
    )
}

private data class Copy(val title: String, val body: String)

private sealed interface CopyVariant {
    fun build(seed: Int): Copy

    data class Active(
        val currentStreakDays: Int,
        val hasActivityToday: Boolean
    ) : CopyVariant {
        override fun build(seed: Int): Copy {
            val title = if (currentStreakDays == 1) "1 day streak!" else "$currentStreakDays days streak!"
            val options = if (hasActivityToday) {
                listOf(
                    "Beautiful work today. Keep connecting with your partner and watch the streak grow",
                    "You showed up today. Keep sharing little moments with your partner every day",
                    "Nice momentum. Come back tomorrow and keep building your streak together",
                    "That was a good day to connect. Keep it going with your partner",
                    "Proud of this consistency. A small moment each day adds up"
                )
            } else {
                listOf(
                    "Strong consistency. Keep connecting with your partner every day",
                    "You are on a roll. Make a small moment together today",
                    "Keep the streak alive. A quick hello today is enough",
                    "One small connection today keeps the streak going",
                    "You have built great momentum. Keep it going today"
                )
            }
            return Copy(title = title, body = options[seed % options.size])
        }
    }

    data class Lost(val previousStreakDays: Int) : CopyVariant {
        override fun build(seed: Int): Copy {
            val title = "Oops! Streak lost"
            val options = listOf(
                "You had a $previousStreakDays day streak. Start again today and it will grow in no time",
                "That was a $previousStreakDays day streak. Take a breath and begin again today",
                "You built a $previousStreakDays day streak. A fresh start today is all you need",
                "Your $previousStreakDays day streak was a great run. Let today be day one again",
                "You were consistent for $previousStreakDays days. Restart today and build it back"
            )
            return Copy(title = title, body = options[seed % options.size])
        }
    }

    data object NoStreak : CopyVariant {
        override fun build(seed: Int): Copy {
            val title = "Start your streak"
            val options = listOf(
                "Your streak begins with one day. Share a small moment with your partner today",
                "Start simple. Connect with your partner today and let the streak build naturally",
                "One day at a time. A quick check in today is a great start",
                "Begin today. Little moments with your partner add up quickly",
                "Ready when you are. Make today your first streak day"
            )
            return Copy(title = title, body = options[seed % options.size])
        }
    }
}

@Composable
private fun StreakContinueButton(
    onClick: () -> Unit,
    scale: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(width = 363.dp * scale, height = 50.dp * scale)
            .clip(RoundedCornerShape(15.38.dp * scale))
            .background(StreakScreenAccentColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Continue",
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 23.857.sp,
            color = Color.White,
            textAlign = TextAlign.Center
        )
    }
}
