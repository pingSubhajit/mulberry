package com.subhajit.mulberry.home

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.ui.ColourNames
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.core.ui.mulberryTapScale
import com.subhajit.mulberry.drawing.model.DrawingDefaults
import com.subhajit.mulberry.streak.RollingNumber
import com.subhajit.mulberry.streak.boltHop
import com.subhajit.mulberry.ui.animation.mulberrySelectionPop
import com.subhajit.mulberry.ui.animation.rememberSystemAnimationsEnabled
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.mulberryAppColors

/**
 * The canvas screen's controls, laid out as on iPhone: the streak and a wallpaper-and-profile
 * capsule at the top right, reactions with undo and redo on the left, colour and width on the
 * right, and the tools in a row along the bottom. Android draws them as Material surfaces where
 * iOS uses glass.
 */
internal object CanvasChromeMetrics {
    /** The top bar's height, from the bottom of the status bar. */
    val HeaderHeight = 56.dp

    /** Each control is a 56dp circle, as on iPhone. */
    val ButtonSize = 56.dp

    /** Space between the controls in a column. */
    val ColumnSpacing = 10.dp

    /** Between the side columns and the tool row. */
    val ColumnsToTools = 28.dp

    /** The tool row's padding, around its 56dp buttons. */
    val ToolRowPadding = 8.dp

    /** Six tools 10dp apart, with the row's padding: the widest the controls get. */
    val ControlsMaxWidth = ButtonSize * 6 + 10.dp * 5 + ToolRowPadding * 2

    /** The tool row's height. */
    val ToolRowHeight = ButtonSize + ToolRowPadding * 2

    /** Above the gesture area, below the tools. */
    val BottomMargin = 8.dp
}

/**
 * Where the canvas sits: strokes are stored relative to the canvas's size, so it keeps the shape
 * a drawing area inset 24dp at the sides and 28dp in all vertically would have, scaled up to span
 * the full width. iOS uses the same rule, so a drawing has the same shape on both.
 */
internal fun canvasHeightFor(width: Dp, slotHeight: Dp): Dp {
    val insetWidth = width - 48.dp
    val insetHeight = slotHeight - 28.dp
    if (insetWidth <= 0.dp || insetHeight <= 0.dp) return slotHeight
    return insetHeight * (width / insetWidth)
}

/** The streak pill and the wallpaper-and-profile capsule, at the top right. */
@Composable
internal fun CanvasHomeTopBar(
    showStreak: Boolean,
    currentStreakDays: Int,
    streakJustClosed: Boolean,
    onStreakClosingSeen: () -> Unit,
    userName: String,
    userPhotoUrl: String?,
    onStreakClick: () -> Unit,
    onWallpaperClick: () -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Undo and redo at the top left, as in the Mac app's main window; null where there's no canvas. */
    history: CanvasHistoryControls? = null
) {
    val wallpaperLabel = stringResource(R.string.home_wallpaper_button_content_description)
    val profileLabel = stringResource(R.string.home_profile_content_description)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = CanvasChromeMetrics.HeaderHeight)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        history?.let { controls -> HistoryCapsule(controls) }
        Spacer(modifier = Modifier.weight(1f))
        if (showStreak) {
            StreakPill(
                currentStreakDays = currentStreakDays,
                streakJustClosed = streakJustClosed,
                onStreakClosingSeen = onStreakClosingSeen,
                onClick = onStreakClick
            )
        }
        Row(
            modifier = Modifier
                .height(48.dp)
                .chromeSurface(CircleShape, elevated = true),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 48.dp)
                    .clickable(role = Role.Button, onClick = onWallpaperClick)
                    .clearAndSetSemantics { contentDescription = wallpaperLabel },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_material_image),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.size(26.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 48.dp)
                    .clickable(role = Role.Button, onClick = onProfileClick)
                    .clearAndSetSemantics { contentDescription = profileLabel },
                contentAlignment = Alignment.Center
            ) {
                ChromeAvatar(photoUrl = userPhotoUrl, displayName = userName, size = 32.dp)
            }
        }
    }
}

/** Undo and redo, for the top bar. */
internal data class CanvasHistoryControls(
    val canUndo: Boolean,
    val canRedo: Boolean,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit
)

/** Undo and redo side by side in one capsule, mirroring the wallpaper and profile capsule. */
@Composable
private fun HistoryCapsule(controls: CanvasHistoryControls) {
    Row(
        modifier = Modifier
            .height(48.dp)
            .chromeSurface(CircleShape, elevated = true),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HistoryButton(
            label = stringResource(R.string.home_canvas_undo_content_description),
            icon = R.drawable.ic_material_undo,
            enabled = controls.canUndo,
            onClick = controls.onUndo,
            modifier = Modifier.testTag(TestTags.UNDO_BUTTON)
        )
        HistoryButton(
            label = stringResource(R.string.home_canvas_redo_content_description),
            icon = R.drawable.ic_material_redo,
            enabled = controls.canRedo,
            onClick = controls.onRedo,
            modifier = Modifier.testTag(TestTags.REDO_BUTTON)
        )
    }
}

@Composable
private fun HistoryButton(
    label: String,
    @DrawableRes icon: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(width = 56.dp, height = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
            modifier = Modifier
                .size(24.dp)
                .alpha(if (enabled) 1f else 0.35f)
        )
    }
}

/** The bolt and the day count, as on iPhone; it hops as the streak screen closes back into it. */
@Composable
private fun StreakPill(
    currentStreakDays: Int,
    streakJustClosed: Boolean,
    onStreakClosingSeen: () -> Unit,
    onClick: () -> Unit
) {
    val reduceMotion = !rememberSystemAnimationsEnabled()
    val boltScale = remember { Animatable(1f) }
    LaunchedEffect(streakJustClosed) {
        if (!streakJustClosed) return@LaunchedEffect
        if (!reduceMotion) boltScale.boltHop()
        onStreakClosingSeen()
    }
    val label = pluralStringResource(R.plurals.home_streak_label, currentStreakDays, currentStreakDays)
    Row(
        modifier = Modifier
            .height(48.dp)
            .chromeSurface(CircleShape, elevated = true)
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.home_streak_open_action),
                onClick = onClick
            )
            .clearAndSetSemantics { contentDescription = label }
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.ic_home_streak_bolt),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(width = 14.dp, height = 21.dp)
                .graphicsLayer {
                    scaleX = boltScale.value
                    scaleY = boltScale.value
                }
        )
        // The count rolls to its new value, as on iPhone.
        RollingNumber(value = currentStreakDays, reduceMotion = reduceMotion) { days ->
            Text(
                text = days.toString(),
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
private fun ChromeAvatar(photoUrl: String?, displayName: String, size: Dp) {
    val initial = displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "M"
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MulberryPrimary),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = Color.White,
            fontFamily = PoppinsFontFamily,
            fontSize = (size.value * 0.4f).sp,
            fontWeight = FontWeight.SemiBold
        )
        if (!photoUrl.isNullOrBlank()) {
            coil.compose.AsyncImage(
                model = photoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * A round control. The tools along the bottom sit on solid surfaces; the actions floating over
 * the canvas ([floating]) are translucent so the drawing shows through, as iOS's clear glass does.
 */
@Composable
internal fun CanvasChromeButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    floating: Boolean = false,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(CanvasChromeMetrics.ButtonSize)
            .chromeSurface(CircleShape, elevated = !floating, translucent = floating)
            .then(
                if (selected) {
                    Modifier
                        .background(MulberryPrimary.copy(alpha = 0.12f), CircleShape)
                        .border(1.5.dp, MulberryPrimary.copy(alpha = 0.4f), CircleShape)
                } else {
                    Modifier
                }
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                this.selected = selected
            },
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.alpha(if (enabled) 1f else 0.35f), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

/** A Material symbol, tinted for the canvas: undo, redo and reactions. */
@Composable
internal fun ChromeSymbol(@DrawableRes drawableRes: Int) {
    Image(
        painter = painterResource(drawableRes),
        contentDescription = null,
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.size(26.dp)
    )
}

/** One of the tools' illustrations, which both apps share. */
@Composable
internal fun ChromeIllustration(@DrawableRes drawableRes: Int, selected: Boolean, tinted: Boolean = false) {
    Image(
        painter = painterResource(drawableRes),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        colorFilter = if (tinted) ColorFilter.tint(MaterialTheme.colorScheme.onSurface) else null,
        modifier = Modifier
            .size(34.dp)
            .mulberrySelectionPop(selected = selected)
    )
}

/** A colour wheel around the current colour: the palette button, as iOS draws it. */
@Composable
internal fun ColourWheelIcon(colorArgb: Long, size: Dp = 32.dp) {
    val hues = remember {
        (0..12).map { step -> Color.hsv(hue = (step * 30f) % 360f, saturation = 0.8f, value = 1f) }
    }
    val outline = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier = Modifier.size(size)) {
        val ringWidth = this.size.minDimension * 0.15f
        drawCircle(
            brush = Brush.sweepGradient(if (mirrored) hues.reversed() else hues),
            radius = this.size.minDimension / 2f - ringWidth / 2f,
            style = Stroke(width = ringWidth)
        )
        val inner = this.size.minDimension / 2f - this.size.minDimension * 0.23f
        drawCircle(color = Color(colorArgb), radius = inner)
        drawCircle(color = outline, radius = inner, style = Stroke(width = 0.5.dp.toPx()))
    }
}

/**
 * The width button's mark: a scribble drawn as thick as the brush, in ink. It says "line weight",
 * where a dot in the brush's colour read as a second colour button. iOS draws the same path.
 */
@Composable
internal fun BrushWidthScribble(width: Float) {
    val fraction = ((width - DrawingDefaults.MIN_WIDTH) / (DrawingDefaults.MAX_WIDTH - DrawingDefaults.MIN_WIDTH))
        .coerceIn(0f, 1f)
    // 1.25 to 4 dp: every width looks different, and the scribble's loops stay open at the thickest.
    val strokeDp by androidx.compose.animation.core.animateFloatAsState(
        targetValue = 1.25f + 2.75f * fraction,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 150),
        label = "brushWidthScribble"
    )
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = Modifier.size(30.dp)) {
        val w = size.width
        val h = size.height
        fun x(f: Float) = w * f
        fun y(f: Float) = h * f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(x(0.095f), y(0.612f))
            lineTo(x(0.45f), y(0.262f))
            cubicTo(x(0.55f), y(0.165f), x(0.63f), y(0.13f), x(0.68f), y(0.18f))
            cubicTo(x(0.73f), y(0.23f), x(0.67f), y(0.32f), x(0.565f), y(0.43f))
            lineTo(x(0.39f), y(0.645f))
            cubicTo(x(0.30f), y(0.76f), x(0.33f), y(0.83f), x(0.40f), y(0.83f))
            cubicTo(x(0.48f), y(0.83f), x(0.60f), y(0.70f), x(0.70f), y(0.61f))
            cubicTo(x(0.78f), y(0.535f), x(0.84f), y(0.53f), x(0.85f), y(0.59f))
            cubicTo(x(0.86f), y(0.65f), x(0.79f), y(0.73f), x(0.76f), y(0.80f))
            cubicTo(x(0.73f), y(0.88f), x(0.78f), y(0.92f), x(0.90f), y(0.79f))
        }
        drawPath(
            path = path,
            color = ink,
            style = Stroke(
                width = strokeDp.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        )
    }
}

/** The palette and width buttons' spoken state. */
internal fun Modifier.colourState(colorArgb: Long): Modifier =
    semantics { stateDescription = ColourNames.name(colorArgb) }

/** The tool row: the tools spread across it, as on iPhone. */
@Composable
internal fun CanvasToolRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(CanvasChromeMetrics.ToolRowPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}

/** Material's tonal surface for the canvas controls, raised a little unless it floats over the drawing. */
@Composable
private fun Modifier.chromeSurface(
    shape: androidx.compose.ui.graphics.Shape,
    elevated: Boolean,
    translucent: Boolean = false
): Modifier {
    val surface = MaterialTheme.mulberryAppColors.softSurfaceStrong
    return this
        .then(
            if (elevated) {
                Modifier.shadow(
                    elevation = 6.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = Color(0x1A000000),
                    spotColor = Color(0x26000000)
                )
            } else {
                Modifier
            }
        )
        .clip(shape)
        .background(if (translucent) surface.copy(alpha = 0.72f) else surface, shape)
        .mulberryTapScale()
}

/**
 * The wallpapers, over the canvas, with a Material top app bar and its back arrow: iOS pushes
 * the same screen from its wallpaper button. Back returns to the canvas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WallpapersPage(
    visible: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    val animate = rememberSystemAnimationsEnabled()
    BackHandler(enabled = visible, onBack = onBack)
    AnimatedVisibility(
        visible = visible,
        enter = if (animate) {
            slideInHorizontally(tween(durationMillis = 300, easing = FastOutSlowInEasing)) { it / 4 } +
                fadeIn(tween(durationMillis = 220))
        } else {
            fadeIn(tween(durationMillis = 150))
        },
        exit = if (animate) {
            slideOutHorizontally(tween(durationMillis = 250, easing = FastOutSlowInEasing)) { it / 4 } +
                fadeOut(tween(durationMillis = 180))
        } else {
            fadeOut(tween(durationMillis = 150))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.home_wallpapers_title),
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_material_arrow_back),
                            contentDescription = stringResource(R.string.home_wallpapers_back_content_description)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                ),
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
            Box(modifier = Modifier.weight(1f)) { content() }
        }
    }
}
