package com.subhajit.mulberry.share

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.ui.MulberryHaptic
import com.subhajit.mulberry.core.ui.rememberMulberryHaptics
import com.subhajit.mulberry.home.EditorIconButton
import com.subhajit.mulberry.home.RoundCameraButton
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import kotlinx.coroutines.delay

private enum class SharePanel { THEME, WALLPAPER, TEXT }

/**
 * The share screen, laid out like the sticker, text and photo editors: the image as it will be
 * shared over the dimmed canvas, Close and Share at the top, and a row of round tools at the
 * bottom, each opening its panel just above it. Share hands a PNG to the system share sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareSheet(
    onDismiss: () -> Unit,
    viewModel: ShareViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val chooserTitle = stringResource(R.string.share_action)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val haptics = rememberMulberryHaptics()
    val captionFocus = remember { FocusRequester() }

    var panel by rememberSaveable { mutableStateOf<SharePanel?>(SharePanel.THEME) }
    var captionFocused by remember { mutableStateOf(false) }
    var imeShownForCaption by remember { mutableStateOf(false) }
    var enter by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible

    LaunchedEffect(viewModel) { viewModel.start() }
    // However the screen goes away, it frees its images and starts afresh next time; a rotation
    // keeps it as it is.
    DisposableEffect(viewModel, context) {
        onDispose {
            if (context.findActivity()?.isChangingConfigurations != true) viewModel.stop()
        }
    }
    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ShareEffect.OpenShareSheet -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, effect.imageUri)
                        clipData = ClipData.newRawUri(null, effect.imageUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                }
            }
        }
    }

    // Fades in as the editors do, and out again before it goes.
    LaunchedEffect(Unit) { enter = true }
    val overlayAlpha by animateFloatAsState(
        targetValue = if (enter) 1f else 0f,
        animationSpec = tween(durationMillis = 110, easing = FastOutLinearInEasing),
        label = "shareOverlayAlpha"
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (enter) 1f else 0f,
        animationSpec = tween(durationMillis = 120, delayMillis = if (enter) 45 else 0, easing = FastOutSlowInEasing),
        label = "shareContentAlpha"
    )
    val contentScale by animateFloatAsState(
        targetValue = if (enter) 1f else 0.98f,
        animationSpec = tween(durationMillis = 120, delayMillis = if (enter) 45 else 0, easing = FastOutSlowInEasing),
        label = "shareContentScale"
    )
    LaunchedEffect(closing) {
        if (!closing) return@LaunchedEffect
        delay(CLOSE_FADE_MS)
        onDismiss()
    }

    fun close() {
        if (closing) return
        closing = true
        focusManager.clearFocus()
        keyboard?.hide()
        enter = false
    }

    BackHandler(enabled = !closing) { close() }

    // Opening the text panel starts typing; any other panel puts the keyboard away.
    LaunchedEffect(panel) {
        if (panel == SharePanel.TEXT) {
            captionFocus.requestFocus()
            keyboard?.show()
        } else {
            focusManager.clearFocus()
        }
    }
    // Putting the keyboard away, with Back or its Done key, closes the text panel too.
    LaunchedEffect(imeVisible, captionFocused) {
        if (imeVisible && captionFocused) {
            imeShownForCaption = true
        } else if (!imeVisible && imeShownForCaption) {
            imeShownForCaption = false
            focusManager.clearFocus()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f * overlayAlpha))
    ) {
        // Background hit target: keeps touches off the canvas, and a tap puts the keyboard away.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { focusManager.clearFocus() }
                }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .alpha(contentAlpha)
        ) {
            ShareTopBar(
                canShare = state.preview != null && !state.isSharing && !closing,
                isSharing = state.isSharing,
                onClose = ::close,
                onShare = {
                    focusManager.clearFocus()
                    viewModel.onShareClicked()
                }
            )

            // Fills the space between the top bar and the tools, so it shrinks only for the keyboard.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                SharePreview(
                    preview = state.preview,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .graphicsLayer {
                            scaleX = contentScale
                            scaleY = contentScale
                        }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Room for the tallest panel, so switching panels never moves the preview. While
                // the caption is being typed the keyboard needs the space, so the slot fits the field.
                val typing = captionFocused && imeVisible
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (typing) Modifier else Modifier.height(PANEL_SLOT_HEIGHT)),
                    contentAlignment = Alignment.BottomStart
                ) {
                    when (panel) {
                        SharePanel.THEME -> ThemePanel(
                            frames = state.frames,
                            selectedKey = state.selectedKey,
                            onFrameSelected = { key ->
                                if (key != state.selectedKey) haptics.perform(MulberryHaptic.Selection)
                                viewModel.onFrameSelected(key)
                            }
                        )
                        SharePanel.WALLPAPER -> WallpaperPanel(
                            useRealWallpaper = state.useRealWallpaper,
                            realWallpaperAvailable = state.realWallpaperAvailable,
                            realThumbnail = state.realWallpaperThumbnail,
                            fallbackThumbnail = state.fallbackWallpaperThumbnail,
                            onChoice = { real ->
                                if (real != state.useRealWallpaper) haptics.perform(MulberryHaptic.Selection)
                                viewModel.onUseRealWallpaperChanged(real)
                            }
                        )
                        SharePanel.TEXT -> CaptionPanel(
                            caption = state.caption,
                            placeholder = state.defaultCaption,
                            focusRequester = captionFocus,
                            onCaptionChanged = { value ->
                                // A return ends editing, as the Done key promises; captions are one paragraph.
                                if (value.contains('\n')) {
                                    viewModel.onCaptionChanged(value.replace("\n", ""))
                                    focusManager.clearFocus()
                                } else {
                                    viewModel.onCaptionChanged(value)
                                }
                            },
                            onDone = { focusManager.clearFocus() },
                            onFocusChanged = { focused ->
                                val wasFocused = captionFocused
                                captionFocused = focused
                                if (wasFocused && !focused && panel == SharePanel.TEXT) panel = null
                            }
                        )
                        null -> Unit
                    }
                }

                ShareToolBar(
                    panel = panel,
                    // Choosing the open panel again closes it, as in the editors.
                    onPanelChanged = { target -> panel = if (panel == target) null else target }
                )
            }
        }
    }
}

@Composable
private fun ShareTopBar(
    canShare: Boolean,
    isSharing: Boolean,
    onClose: () -> Unit,
    onShare: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoundCameraButton(
            iconRes = R.drawable.ic_material_close,
            contentDescription = stringResource(R.string.share_close),
            onClick = onClose
        )
        Spacer(modifier = Modifier.weight(1f))
        val description = stringResource(R.string.share_action_content_description)
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (canShare || isSharing) MulberryPrimary else MulberryPrimary.copy(alpha = 0.45f))
                .clickable(enabled = canShare, role = Role.Button, onClick = onShare)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center
        ) {
            if (isSharing) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_material_share),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = if (canShare) 1f else 0.8f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun ShareToolBar(panel: SharePanel?, onPanelChanged: (SharePanel) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EditorIconButton(
            iconRes = R.drawable.ic_material_filter_frames,
            contentDescription = stringResource(R.string.share_panel_theme),
            selected = panel == SharePanel.THEME
        ) { onPanelChanged(SharePanel.THEME) }
        EditorIconButton(
            iconRes = R.drawable.ic_material_smartphone,
            contentDescription = stringResource(R.string.share_panel_wallpaper),
            selected = panel == SharePanel.WALLPAPER
        ) { onPanelChanged(SharePanel.WALLPAPER) }
        EditorIconButton(
            iconRes = R.drawable.ic_material_text_format,
            contentDescription = stringResource(R.string.share_panel_text),
            selected = panel == SharePanel.TEXT
        ) { onPanelChanged(SharePanel.TEXT) }
    }
}

@Composable
private fun SharePreview(preview: Bitmap?, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.share_preview_content_description)
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .aspectRatio(SHARE_OUTPUT_WIDTH.toFloat() / SHARE_OUTPUT_HEIGHT)
            .shadow(elevation = 16.dp, shape = shape)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        if (preview == null) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        } else {
            val image = remember(preview) { preview.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** A tool's panel over the dimmed canvas, its title above its contents. */
@Composable
private fun SharePanelSurface(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PANEL_SHAPE)
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), PANEL_SHAPE)
            .padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (title != null) {
            Text(
                text = title,
                color = Color.White,
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp)
            )
        }
        content()
    }
}

@Composable
private fun ThemePanel(
    frames: List<ShareFrameOption>,
    selectedKey: String?,
    onFrameSelected: (String) -> Unit
) {
    val title = frames.firstOrNull { it.key == selectedKey }?.title ?: stringResource(R.string.share_panel_theme)
    SharePanelSurface(title = title) {
        // Scrolls edge to edge of the panel while resting aligned with the title.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(frames, key = { it.key }) { frame ->
                val selected = frame.key == selectedKey
                ShareTile(
                    selected = selected,
                    modifier = Modifier
                        .clip(TILE_SHAPE)
                        .selectable(selected = selected, role = Role.Button, onClick = { onFrameSelected(frame.key) })
                        .semantics { contentDescription = frame.title }
                ) {
                    AsyncImage(
                        model = frame.thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun WallpaperPanel(
    useRealWallpaper: Boolean,
    realWallpaperAvailable: Boolean,
    realThumbnail: Bitmap?,
    fallbackThumbnail: Bitmap?,
    onChoice: (Boolean) -> Unit
) {
    SharePanelSurface(title = stringResource(R.string.share_panel_wallpaper)) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            WallpaperOption(
                title = stringResource(R.string.share_wallpaper_mine),
                thumbnail = realThumbnail,
                selected = useRealWallpaper,
                enabled = realWallpaperAvailable,
                onClick = { onChoice(true) }
            )
            WallpaperOption(
                title = stringResource(R.string.share_wallpaper_mulberry),
                thumbnail = fallbackThumbnail,
                selected = !useRealWallpaper,
                enabled = true,
                onClick = { onChoice(false) }
            )
        }
    }
}

@Composable
private fun WallpaperOption(
    title: String,
    thumbnail: Bitmap?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    // One choice to TalkBack: the tile and its title together.
    Column(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(TILE_SHAPE)
            .selectable(selected = selected, enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ShareTile(selected = selected) {
            if (thumbnail != null) {
                val image = remember(thumbnail) { thumbnail.asImageBitmap() }
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Text(
            text = title,
            color = Color.White.copy(alpha = if (selected) 1f else 0.7f),
            fontFamily = PoppinsFontFamily,
            fontSize = 12.sp,
            maxLines = 1
        )
    }
}

/** A phone-shaped choice, ringed in the accent colour when chosen, as the sticker picker's packs are. */
@Composable
private fun ShareTile(
    selected: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MulberryPrimary else Color.White.copy(alpha = 0.08f),
                shape = TILE_SHAPE
            )
            .padding(3.dp)
            .size(width = 54.dp, height = 96.dp)
            .clip(TILE_IMAGE_SHAPE)
            .background(Color.White.copy(alpha = 0.06f)),
        content = content
    )
}

@Composable
private fun CaptionPanel(
    caption: String,
    placeholder: String,
    focusRequester: FocusRequester,
    onCaptionChanged: (String) -> Unit,
    onDone: () -> Unit,
    onFocusChanged: (Boolean) -> Unit
) {
    val description = stringResource(R.string.share_caption_content_description)
    val textStyle = TextStyle(color = Color.White, fontFamily = PoppinsFontFamily, fontSize = 16.sp)
    BasicTextField(
        value = caption,
        onValueChange = onCaptionChanged,
        maxLines = 3,
        textStyle = textStyle,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        cursorBrush = SolidColor(MulberryPrimary),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .semantics { contentDescription = description },
        decorationBox = { field ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PANEL_SHAPE)
                    .background(Color.White.copy(alpha = 0.10f))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), PANEL_SHAPE)
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (caption.isEmpty()) {
                    Text(text = placeholder, style = textStyle.copy(color = Color.White.copy(alpha = 0.4f)), maxLines = 3)
                }
                field()
            }
        }
    )
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private val PANEL_SHAPE = RoundedCornerShape(22.dp)
private val TILE_SHAPE = RoundedCornerShape(13.dp)
private val TILE_IMAGE_SHAPE = RoundedCornerShape(10.dp)
private val PANEL_SLOT_HEIGHT = 196.dp
private const val CLOSE_FADE_MS = 120L
