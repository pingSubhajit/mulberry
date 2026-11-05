package com.subhajit.mulberry.streak

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.ui.ApplySystemBarStyle
import com.subhajit.mulberry.core.ui.MulberryHaptic
import com.subhajit.mulberry.core.ui.performMulberryHaptic
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.core.ui.metadata.AppSystemBarStyle
import com.subhajit.mulberry.network.StreakResponse
import com.subhajit.mulberry.network.StreakWeekDayResponse
import com.subhajit.mulberry.ui.animation.rememberSystemAnimationsEnabled
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.absoluteValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val StreakScreenBackgroundColor = Color(0xFF370108)
private val StreakScreenEllipseColor = Color(0xFF52020C)
private val StreakScreenAccentColor = Color(0xFFB31329)
private val StreakScreenMutedCircleColor = Color(0xFF4B2227)
private val StreakScreenBodyTextColor = Color.White.copy(alpha = 0.7f)
private val StreakScreenMutedTextColor = Color.White.copy(alpha = 0.4f)
/** The bolt's light, wider than its artwork's own halo can reach. */
private val StreakBoltGlowColor = Color(red = 1f, green = 0.16f, blue = 0.27f)
private const val HeroSizeFraction = 0.8f
private const val HeroBottomMarginDp = 24f

@Composable
fun StreakRoute(
    onClose: () -> Unit,
    viewModel: StreakViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.load() }
    // Back closes the same way the buttons do, so the toolbar bolt can take the streak back.
    BackHandler(onBack = onClose)
    StreakScreen(
        uiState = uiState,
        onClose = onClose,
        onBreakShown = viewModel::breakShown,
        onStreakShown = viewModel::streakShown
    )
}

/** This showing of the bolt: whether it breaks, and how far along it is. */
private enum class BreakPhase { Undecided, Waiting, Playing, Done }

/** Lets the screen finish entering before the bolt breaks. */
private const val BreakEntryDelayMs = 350L

@Composable
private fun StreakScreen(
    uiState: StreakUiState,
    onClose: () -> Unit,
    onBreakShown: () -> Unit,
    onStreakShown: () -> Unit
) {
    val streak = uiState.streak
    val content = streak?.toStreakContent()
    val reduceMotion = !rememberSystemAnimationsEnabled()
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var breakPhase by remember { mutableStateOf(BreakPhase.Undecided) }
    var breakCopyRevealed by remember { mutableStateOf(false) }
    val showsFilm = streak?.isLostRecently() == true && when (breakPhase) {
        BreakPhase.Undecided -> uiState.breakPending && !reduceMotion
        BreakPhase.Waiting, BreakPhase.Playing -> true
        BreakPhase.Done -> false
    }
    // The words follow the shards.
    val breakCopyAlpha by animateFloatAsState(
        targetValue = if (showsFilm && !breakCopyRevealed) 0f else 1f,
        animationSpec = tween(durationMillis = 450, easing = LinearOutSlowInEasing),
        label = "streakBreakCopyAlpha"
    )

    LaunchedEffect(uiState.isLoading, streak) {
        if (breakPhase != BreakPhase.Undecided || uiState.isLoading || streak == null) return@LaunchedEffect
        if (!uiState.breakPending) {
            breakPhase = BreakPhase.Done
            return@LaunchedEffect
        }
        if (reduceMotion) {
            // The broken bolt, still, counts as seeing it.
            onBreakShown()
            breakPhase = BreakPhase.Done
            return@LaunchedEffect
        }
        breakPhase = BreakPhase.Waiting
        delay(BreakEntryDelayMs)
        onBreakShown()
        breakPhase = BreakPhase.Playing
    }

    // Everything settles in from the moment the screen opens; each piece keeps its own delay.
    var arrived by remember { mutableStateOf(false) }
    val openedAtMs = remember { System.currentTimeMillis() }
    LaunchedEffect(Unit) { arrived = true }

    // Today's moment: a rise since the screen last showed the streak ticks up and fills today in;
    // a day still to do calls with a ripple. A streak done earlier and already seen keeps still.
    var todayPlayed by remember { mutableStateOf(false) }
    var celebrations by remember { mutableIntStateOf(0) }
    var ripples by remember { mutableIntStateOf(0) }
    val tickFrom = if (todayPlayed) null else uiState.celebrationStart
    val currentShowsFilm by rememberUpdatedState(showsFilm)
    LaunchedEffect(uiState.isLoading, streak) {
        if (todayPlayed || uiState.isLoading || streak == null) return@LaunchedEffect
        snapshotFlow { breakPhase }.first { it != BreakPhase.Undecided }
        val elapsed = System.currentTimeMillis() - openedAtMs
        delay((StreakMotion.TODAY_MOMENT_MS - elapsed).coerceAtLeast(0L))
        val rose = uiState.celebrationStart != null
        todayPlayed = true
        onStreakShown()
        if (rose) {
            celebrations += 1
            view.performMulberryHaptic(MulberryHaptic.Success)
        } else if (!streak.hasActivityToday && !currentShowsFilm) {
            ripples += 1
        }
    }

    // The light crosses a whole bolt as it settles.
    val glint = remember { BoltGlintState() }
    LaunchedEffect(streak != null, reduceMotion) {
        if (streak == null || reduceMotion) return@LaunchedEffect
        val elapsed = System.currentTimeMillis() - openedAtMs
        delay((StreakMotion.GLINT_MS - elapsed).coerceAtLeast(0L))
        if (!streak.isLostRecently() && !currentShowsFilm) glint.play()
    }
    val squish = remember { SquishState() }

    ApplySystemBarStyle(
        style = AppSystemBarStyle(
            statusBarColorArgb = 0x00000000,
            navigationBarColorArgb = 0x00000000,
            useDarkIcons = false
        )
    )

    val floorEntrance = rememberEntrance(arrived, 200L, appleSpring(0.42f, 0.2f), reduceMotion)
    val glowEntrance = rememberEntrance(arrived, 50L, appleSpring(0.7f), reduceMotion)
    val boltEntrance = rememberEntrance(arrived, 50L, appleSpring(0.55f, 0.42f), reduceMotion)
    val titleEntrance = rememberEntrance(arrived, 240L, appleSpring(0.45f), reduceMotion)
    val messageEntrance = rememberEntrance(arrived, 320L, appleSpring(0.45f), reduceMotion)
    val continueEntrance = rememberEntrance(arrived, 360L, appleSpring(0.45f), reduceMotion)
    val closeEntrance = rememberEntrance(arrived, 0L, appleSpring(0.3f), reduceMotion)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(StreakScreenBackgroundColor)
    ) {
        val scale = maxWidth.value / 402f

        // Once the screen has nearly settled, the floor swells up from below, overshooting a
        // touch, under the bolt.
        StreakBackground(
            scale = scale,
            modifier = Modifier.streakEntrance(
                floorEntrance,
                reduceMotion,
                rise = 130.dp * scale,
                fromScale = 1.12f
            )
        )

        if (content != null) {
            val heroSize = maxWidth * HeroSizeFraction
            val heroBottomTarget = 470.dp * scale - HeroBottomMarginDp.dp * scale
            val heroTop = (heroBottomTarget - heroSize).coerceAtLeast(0.dp)
            val brokenScale = if (content.hero == StreakHero.BrokenBolt) StreakBreakBrokenScale else 1f

            // Behind the whole bolt, the breaking one and the broken one alike.
            BoltGlow(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = heroTop)
                    .size(heroSize)
                    .graphicsLayer {
                        scaleX = 1.25f
                        scaleY = 1.25f
                    }
                    .streakEntrance(glowEntrance, reduceMotion, fromScale = 0.6f)
            )

            val heroModifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = heroTop)
                .size(heroSize)
                .testTag(TestTags.STREAK_HERO_IMAGE)
                .clearAndSetSemantics {}
            if (showsFilm) {
                StreakBreakFilm(
                    start = breakPhase == BreakPhase.Playing,
                    onCrack = {},
                    onReveal = { breakCopyRevealed = true },
                    onFinish = { breakPhase = BreakPhase.Done },
                    modifier = heroModifier
                        .streakEntrance(boltEntrance, reduceMotion)
                        .graphicsLayer {
                            scaleX = StreakBreakBrokenScale
                            scaleY = StreakBreakBrokenScale
                        }
                )
            } else {
                Image(
                    painter = painterResource(content.hero.imageRes),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = heroModifier
                        .streakEntrance(boltEntrance, reduceMotion, fromScale = 0.82f)
                        .then(if (reduceMotion) Modifier else Modifier.squish(squish))
                        .graphicsLayer {
                            scaleX = brokenScale
                            scaleY = brokenScale
                        }
                        .then(if (content.hero == StreakHero.Bolt) Modifier.boltGlint(glint) else Modifier)
                        .pointerInput(reduceMotion) {
                            detectTapGestures {
                                view.performMulberryHaptic(MulberryHaptic.LightImpact)
                                if (!reduceMotion) scope.launch { squish.play() }
                            }
                        }
                )
            }
        }

        StreakCloseButton(
            onClick = onClose,
            scale = scale,
            modifier = Modifier
                .offset(
                    x = maxWidth - 20.dp * scale - 25.dp * scale,
                    y = 75.dp * scale
                )
                .streakEntrance(closeEntrance, reduceMotion)
        )

        if (content != null) {
            WeekRow(
                streak = streak,
                arrived = arrived,
                todayWaits = tickFrom != null,
                celebrations = celebrations,
                ripples = ripples,
                reduceMotion = reduceMotion,
                scale = scale,
                modifier = Modifier.offset(x = 45.dp * scale, y = 470.dp * scale)
            )

            Column(
                modifier = Modifier
                    .offset(x = 65.dp * scale, y = 565.dp * scale)
                    .width(273.dp * scale)
                    .graphicsLayer { alpha = breakCopyAlpha },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                StreakTitle(
                    content = content,
                    days = tickFrom ?: content.currentStreakDays,
                    reduceMotion = reduceMotion,
                    modifier = Modifier
                        .fillMaxWidth()
                        .streakEntrance(titleEntrance, reduceMotion, rise = 10.dp, blur = 6.dp)
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
                        .streakEntrance(messageEntrance, reduceMotion, rise = 10.dp, blur = 6.dp)
                )
            }
        } else if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage,
                color = StreakScreenBodyTextColor,
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .offset(x = 65.dp * scale, y = 565.dp * scale)
                    .width(273.dp * scale)
                    .streakEntrance(titleEntrance, reduceMotion, rise = 10.dp)
            )
        }

        StreakContinueButton(
            onClick = onClose,
            scale = scale,
            modifier = Modifier
                .offset(x = 20.dp * scale, y = 782.dp * scale)
                .streakEntrance(continueEntrance, reduceMotion, rise = 8.dp * scale)
        )
    }
}

/** A soft light behind the bolt, as if it gave off its colour. */
@Composable
private fun BoltGlow(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        // Low and even through the middle, so the bolt keeps its contrast against it, and long in
        // its tail, so it spreads wide before it's gone.
        drawRect(
            brush = Brush.radialGradient(
                0f to StreakBoltGlowColor.copy(alpha = 0.2f),
                0.35f to StreakBoltGlowColor.copy(alpha = 0.15f),
                0.7f to StreakBoltGlowColor.copy(alpha = 0.06f),
                1f to StreakBoltGlowColor.copy(alpha = 0f),
                center = center,
                radius = size.minDimension / 2f
            )
        )
    }
}

@Composable
private fun StreakBackground(
    scale: Float,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    Canvas(modifier = modifier.fillMaxSize()) {
        val ovalWidth = with(density) { (733.dp * scale).toPx() }
        val ovalHeight = with(density) { (635.dp * scale).toPx() }
        val top = with(density) { (387.dp * scale).toPx() }
        val left = with(density) { (-165.dp * scale).toPx() }
        drawOval(
            color = StreakScreenEllipseColor,
            topLeft = Offset(left, top),
            size = Size(ovalWidth, ovalHeight)
        )
    }
}

@Composable
private fun StreakTitle(
    content: StreakContent,
    days: Int,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val titleStyle: @Composable (String) -> Unit = { text ->
        Text(
            text = text,
            color = Color.White,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            letterSpacing = 0.25.sp,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
    if (!content.countsDays) {
        Box(modifier = modifier.semantics { heading() }, contentAlignment = Alignment.Center) {
            titleStyle(content.title)
        }
        return
    }
    // Only the number rolls; the words stay put.
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            heading()
            contentDescription = streakTitle(days)
        },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        RollingNumber(value = days, reduceMotion = reduceMotion) { number -> titleStyle(number.toString()) }
        titleStyle(STREAK_TITLE_SUFFIX)
    }
}

@Composable
private fun StreakCloseButton(
    onClick: () -> Unit,
    scale: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .offset(x = (-11.5).dp, y = (-11.5).dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_streak_close),
            contentDescription = "Close",
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(25.dp * scale)
        )
    }
}

@Composable
private fun WeekRow(
    streak: StreakResponse,
    arrived: Boolean,
    todayWaits: Boolean,
    celebrations: Int,
    ripples: Int,
    reduceMotion: Boolean,
    scale: Float,
    modifier: Modifier = Modifier
) {
    val dayLabels = listOf("S", "M", "T", "W", "T", "F", "S")
    Box(
        modifier = modifier.size(width = 313.dp * scale, height = 55.dp * scale)
    ) {
        val xPositions = listOf(0, 47, 94, 141, 188, 235, 282)
        streak.week.take(7).forEachIndexed { index, day ->
            val isToday = day.day == streak.today
            WeekDay(
                day = day,
                label = dayLabels.getOrElse(index) { "" },
                isToday = isToday,
                isPast = day.day < streak.today,
                // Today shows as still to do, until its moment fills it in.
                done = day.hasActivity && !(isToday && todayWaits),
                // The days settle in, one after another, as the screen opens.
                entrance = rememberEntrance(
                    arrived = arrived,
                    delayMs = 140L + index * StreakMotion.DAY_STAGGER_MS,
                    spec = appleSpring(0.4f, 0.3f),
                    reduceMotion = reduceMotion
                ),
                celebrations = if (isToday) celebrations else 0,
                ripples = if (isToday) ripples else 0,
                reduceMotion = reduceMotion,
                scale = scale,
                modifier = Modifier.offset(x = xPositions.getOrElse(index) { index * 47 }.dp * scale)
            )
        }
    }
}

@Composable
private fun WeekDay(
    day: StreakWeekDayResponse,
    label: String,
    isToday: Boolean,
    isPast: Boolean,
    done: Boolean,
    entrance: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    celebrations: Int,
    ripples: Int,
    reduceMotion: Boolean,
    scale: Float,
    modifier: Modifier = Modifier
) {
    val pop = remember { Animatable(1f) }
    LaunchedEffect(celebrations) { if (celebrations > 0 && !reduceMotion) pop.dayPop() }
    val ripple = remember { Animatable(0f) }
    LaunchedEffect(ripples) { if (ripples > 0 && !reduceMotion) ripple.ripple() }
    // The check draws itself as the day fills in; a day done already shows it whole.
    val check = remember { Animatable(if (done) 1f else 0f) }
    LaunchedEffect(done) {
        if (!done) {
            check.snapTo(0f)
        } else if (check.value < 1f) {
            check.animateTo(1f, tween(durationMillis = if (reduceMotion) 250 else 450, easing = FastOutSlowInEasing))
        }
    }
    val fill by animateColorAsState(
        targetValue = if (done) StreakScreenAccentColor else StreakScreenMutedCircleColor,
        animationSpec = tween(durationMillis = if (reduceMotion) 250 else 400),
        label = "streakDayFill"
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (!isPast && !done) 1f else 0f,
        animationSpec = tween(durationMillis = if (reduceMotion) 250 else 400),
        label = "streakDayRing"
    )
    val status = when {
        done -> "Completed"
        isPast -> "No activity"
        else -> "Not completed yet"
    }
    val spokenDay = LocalDate.parse(day.day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))

    Box(
        modifier = modifier
            .size(width = 31.dp * scale, height = 55.dp * scale)
            .streakEntrance(entrance, reduceMotion, fromScale = 0.6f)
            .clearAndSetSemantics {
                contentDescription = "$spokenDay${if (isToday) ", today" else ""}: $status"
            }
    ) {
        Text(
            text = label,
            color = if (isPast && !done) StreakScreenMutedTextColor else Color.White,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.25.sp,
            modifier = Modifier.offset(x = dayLetterOffsetX(label).dp * scale)
        )
        Box(
            modifier = Modifier
                .offset(y = 24.dp * scale)
                .size(31.dp * scale)
                .dayRipple(ripple, StreakScreenAccentColor)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                }
                .clip(CircleShape)
                .background(fill)
                .border(
                    width = (if (isToday) 3.dp else 2.dp) * scale,
                    color = StreakScreenAccentColor.copy(alpha = ringAlpha),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                done || check.value > 0f -> CheckMark(
                    progress = if (reduceMotion) 1f else check.value,
                    scale = scale,
                    modifier = Modifier.graphicsLayer { alpha = if (reduceMotion) check.value else 1f }
                )
                isPast -> XMark(scale = scale)
            }
        }
    }
}

/** A check drawn as far as [progress] has come, from its short stroke to its long one. */
@Composable
private fun CheckMark(
    progress: Float,
    scale: Float,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.size(width = 11.dp * scale, height = 7.dp * scale)
    ) {
        val path = Path().apply {
            moveTo(size.width * (0.75f / 12.5f), size.height * (4.56819f / 8.5f))
            lineTo(size.width * (4.1875f / 12.5f), size.height * (7.75f / 8.5f))
            lineTo(size.width * (11.75f / 12.5f), size.height * (0.75f / 8.5f))
        }
        val drawn = if (progress >= 1f) {
            path
        } else {
            val measure = PathMeasure().apply { setPath(path, false) }
            Path().also { segment -> measure.getSegment(0f, measure.length * progress.coerceIn(0f, 1f), segment, true) }
        }
        drawPath(
            path = drawn,
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
        val strokeWidth = (2.dp * scale).toPx()
        drawLine(
            color = StreakScreenMutedTextColor,
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = StreakScreenMutedTextColor,
            start = Offset(size.width, 0f),
            end = Offset(0f, size.height),
            strokeWidth = strokeWidth,
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

private const val STREAK_TITLE_SUFFIX = "-day streak!"

private fun streakTitle(days: Int): String = "$days$STREAK_TITLE_SUFFIX"

private enum class StreakHero(val imageRes: Int) {
    Bolt(R.drawable.streak_bolt_large),
    BrokenBolt(R.drawable.streak_bolt_broken_large)
}

private data class StreakContent(
    val currentStreakDays: Int,
    val title: String,
    val body: String,
    val hero: StreakHero,
    /** The title is the day count, as "4-day streak!", so its number can tick up. */
    val countsDays: Boolean
)

private fun StreakResponse.toStreakContent(): StreakContent {
    val lostRecently = isLostRecently()

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
        hero = if (lostRecently) StreakHero.BrokenBolt else StreakHero.Bolt,
        countsDays = variant is CopyVariant.Active
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
            val title = streakTitle(currentStreakDays)
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
                "You had a $previousStreakDays-day streak. Start again today and it will grow in no time",
                "That was a $previousStreakDays-day streak. Take a breath and begin again today",
                "You built a $previousStreakDays-day streak. A fresh start today is all you need",
                "Your $previousStreakDays-day streak was a great run. Let today be day one again",
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
