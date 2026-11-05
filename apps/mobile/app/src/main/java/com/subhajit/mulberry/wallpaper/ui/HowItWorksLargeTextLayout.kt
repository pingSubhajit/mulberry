package com.subhajit.mulberry.wallpaper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.ui.mulberryTapScale
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily

private val HowItWorksBackground = Color(0xFF350007)
private val HowItWorksBodyText = Color.White.copy(alpha = 0.8f)
private val HowItWorksCircleFill = Color(0xFFCF1D35)
private val HowItWorksCircleStroke = Color(0xFFE3E3E3)
private val HowItWorksLine = Color(0xFF800E1E)
private val HowItWorksCtaText = Color(0xFF595959)
private val HowItWorksCircleSize = 17.dp

/**
 * The "How it works" help screens at the large text sizes. Their usual layout places each block
 * at a fixed point, which large text would overlap, so here the blocks flow in a scrolling column
 * under the heart, with the button pinned below, as on iPhone.
 */
@Composable
internal fun HowItWorksLargeTextLayout(
    title: String,
    body: String,
    steps: List<String>,
    note: String,
    ctaText: String,
    onCta: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    ctaModifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(HowItWorksBackground)
    ) {
        Image(
            painter = painterResource(R.drawable.wallpaper_help_heart),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .aspectRatio(804f / 716f)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(role = Role.Button, onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_streak_close),
                        contentDescription = stringResource(R.string.wallpaper_help_close),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(25.dp)
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Leaves the heart in view above the text.
                Spacer(modifier = Modifier.height(96.dp))
                Text(
                    text = stringResource(R.string.wallpaper_help_pill),
                    color = Color.White,
                    style = TextStyle(
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        lineHeight = 20.sp,
                        letterSpacing = 0.25.sp
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFFA0730C), Color(0xFFDCA612))))
                        .padding(horizontal = 14.dp, vertical = 3.dp)
                )
                Text(
                    text = title,
                    color = Color.White,
                    style = TextStyle(
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 32.sp,
                        lineHeight = 42.sp,
                        letterSpacing = 0.25.sp
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = body,
                    color = HowItWorksBodyText,
                    style = TextStyle(
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        letterSpacing = 0.25.sp
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                HowItWorksTimeline(steps = steps)
                Text(
                    text = note,
                    color = Color.White,
                    style = TextStyle(
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        letterSpacing = 0.25.sp
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(
                modifier = ctaModifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 12.dp)
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .clip(RoundedCornerShape(15.385.dp))
                    .background(Color.White)
                    .mulberryTapScale()
                    .clickable(role = Role.Button, onClick = onCta)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = ctaText,
                    color = HowItWorksCtaText,
                    style = TextStyle(
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        letterSpacing = 0.25.sp
                    ),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** The steps, joined by a line through their markers, read as one item. */
@Composable
private fun HowItWorksTimeline(steps: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .drawBehind {
                val x = HowItWorksCircleSize.toPx() / 2f
                val inset = 12.dp.toPx()
                if (size.height > inset * 2) {
                    drawLine(
                        color = HowItWorksLine,
                        start = Offset(x, inset),
                        end = Offset(x, size.height - inset),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            },
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        steps.forEach { step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.size(HowItWorksCircleSize)) {
                    drawCircle(color = HowItWorksCircleFill)
                    drawCircle(
                        color = HowItWorksCircleStroke,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
                Spacer(modifier = Modifier.width(22.dp))
                Text(
                    text = step,
                    color = HowItWorksBodyText,
                    style = TextStyle(
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                        lineHeight = 20.sp,
                        letterSpacing = 0.25.sp
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
