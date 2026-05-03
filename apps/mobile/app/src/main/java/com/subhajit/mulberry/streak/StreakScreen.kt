package com.subhajit.mulberry.streak

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.ui.ApplySystemBarStyle
import com.subhajit.mulberry.core.ui.metadata.AppSystemBarStyle
import com.subhajit.mulberry.network.StreakResponse
import com.subhajit.mulberry.ui.theme.KalamFontFamily
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import kotlin.math.absoluteValue
import java.time.LocalDate

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

    ApplySystemBarStyle(
        style = AppSystemBarStyle(
            statusBarColorArgb = 0x00000000,
            navigationBarColorArgb = 0x00000000,
            useDarkIcons = false
        )
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        val scale = maxWidth.value / 402f

        StreakBackground(scale = scale)

        if (content != null) {
            StreakPill(
                currentStreakDays = content.currentStreakDays,
                scale = scale,
                modifier = Modifier.offset(x = 243.dp * scale, y = 72.dp * scale)
            )
        }

        StreakCloseButton(
            onClick = onClose,
            scale = scale,
            modifier = Modifier.offset(
                x = maxWidth - 20.dp * scale - 31.dp * scale,
                y = 75.dp * scale
            )
        )

        if (content != null) {
            StreakHeroImage(
                hero = content.hero,
                scale = scale,
                modifier = Modifier.offset(
                    x = (if (content.hero == StreakHero.BrokenBolt) 123.dp else 125.dp) * scale,
                    y = 175.dp * scale
                )
            )

            FigmaWeekRow(
                streak = streak,
                scale = scale,
                modifier = Modifier.offset(x = 45.dp * scale, y = 470.dp * scale)
            )

            Column(
                modifier = Modifier
                    .offset(x = 65.dp * scale, y = 565.dp * scale)
                    .width(273.dp * scale),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = content.title,
                    color = Color.Black,
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 24.sp,
                    lineHeight = 20.sp,
                    letterSpacing = 0.25.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp * scale))
                Text(
                    text = content.body,
                    color = Color.Black.copy(alpha = 0.7f),
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    letterSpacing = 0.25.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                )
            }
        } else if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage,
                color = Color.Black.copy(alpha = 0.7f),
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .offset(x = 65.dp * scale, y = 565.dp * scale)
                    .width(273.dp * scale)
            )
        }

        StreakContinueButton(
            onClick = onClose,
            scale = scale,
            modifier = Modifier.offset(x = 20.dp * scale, y = 782.dp * scale)
        )
    }
}

@Composable
private fun StreakBackground(scale: Float) {
    val fill = Color(0xFFFFEEF2)
    val density = LocalDensity.current
    Canvas(modifier = Modifier.fillMaxSize()) {
        val ovalWidth = with(density) { (733.dp * scale).toPx() }
        val ovalHeight = with(density) { (635.dp * scale).toPx() }
        val top = with(density) { (387.dp * scale).toPx() }
        val left = with(density) { (-165.dp * scale).toPx() }
        drawOval(
            color = fill,
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
            .border(1.dp * scale, MulberryPrimary, shape)
            .background(MulberryPrimary.copy(alpha = 0.15f), shape),
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
                color = MulberryPrimary,
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
    Canvas(
        modifier = modifier
            .size(31.dp * scale)
            .clickable(onClick = onClick)
            .padding(7.dp * scale)
    ) {
        val strokeColor = Color.Black
        val stroke = Stroke(width = 3.8f * scale, cap = StrokeCap.Round)
        drawLine(
            color = strokeColor,
            start = Offset(3f * scale, 3f * scale),
            end = Offset(size.width - 3f * scale, size.height - 3f * scale),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round
        )
        drawLine(
            color = strokeColor,
            start = Offset(size.width - 3f * scale, 3f * scale),
            end = Offset(3f * scale, size.height - 3f * scale),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun StreakHeroImage(
    hero: StreakHero,
    scale: Float,
    modifier: Modifier = Modifier
) {
    val boxWidth = (if (hero == StreakHero.BrokenBolt) 150.dp else 153.dp) * scale
    val boxHeight = 247.dp * scale
    val imageRes = if (hero == StreakHero.BrokenBolt) {
        R.drawable.streak_bolt_broken_asset
    } else {
        R.drawable.streak_bolt_asset
    }

    Box(
        modifier = modifier
            .size(width = boxWidth, height = boxHeight)
            .clipToBounds()
    ) {
        Image(
            painter = painterResource(imageRes),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
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
                Color.Black.copy(alpha = 0.35f)
            } else {
                Color.Black
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
    val fillBase = Color(0xFFFCF4F5)
    val ringStroke = when (style) {
        DayMarkStyle.TodayRing -> 3.dp * scale
        DayMarkStyle.Ring -> 2.dp * scale
        else -> 0.dp
    }
    val ringColor = MulberryPrimary
    val fillColor = when (style) {
        DayMarkStyle.Checked -> MulberryPrimary
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
        val color = Color.Black.copy(alpha = 0.4f)
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

private enum class StreakHero { Bolt, BrokenBolt }

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
            .background(MulberryPrimary)
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
