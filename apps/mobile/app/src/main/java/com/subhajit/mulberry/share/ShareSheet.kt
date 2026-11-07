package com.subhajit.mulberry.share

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.ui.MulberryHaptic
import com.subhajit.mulberry.core.ui.rememberMulberryHaptics
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.mulberryAppColors

/**
 * The share sheet, a standard Material modal bottom sheet at full height: the image as it will
 * be shared, filling what the rest leaves; a row of frame thumbnails with no names; the caption
 * and the "My wallpaper" switch; and a full-width Share button at the foot. Like any Android
 * sheet it closes with a swipe down, Back or a tap outside, so it has no close button. Share
 * hands a PNG to the system share sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    onDismiss: () -> Unit,
    viewModel: ShareViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val chooserTitle = stringResource(R.string.share_action)
    val focusManager = LocalFocusManager.current
    val haptics = rememberMulberryHaptics()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(viewModel) { viewModel.start() }
    // However the sheet goes away, it frees its images and starts afresh next time; a rotation
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Full height, but below the status bar, as a large sheet sits.
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 8.dp),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        // Material's own handle, which also gives TalkBack the sheet's dismiss action.
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.mulberryAppColors.dragHandle) }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // The preview takes whatever the rest leaves, so the settings and Share sit at the
            // bottom on every screen; the keyboard shrinks it.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                SharePreview(preview = state.preview)
            }

            FrameRow(
                frames = state.frames,
                selectedKey = state.selectedKey,
                onFrameSelected = { key ->
                    if (key != state.selectedKey) haptics.perform(MulberryHaptic.Selection)
                    viewModel.onFrameSelected(key)
                }
            )

            ShareSettings(
                caption = state.caption,
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
                useRealWallpaper = state.useRealWallpaper,
                realWallpaperAvailable = state.realWallpaperAvailable,
                onUseRealWallpaperChanged = { real ->
                    haptics.perform(MulberryHaptic.Selection)
                    viewModel.onUseRealWallpaperChanged(real)
                }
            )

            ShareButton(
                enabled = state.preview != null && !state.isSharing,
                isSharing = state.isSharing,
                onClick = {
                    focusManager.clearFocus()
                    viewModel.onShareClicked()
                }
            )
        }
    }
}

/** The sheet's one action, full width at its foot, as Material sheets place their primary action. */
@Composable
private fun ShareButton(
    enabled: Boolean,
    isSharing: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MulberryPrimary,
            contentColor = Color.White,
            // While it's preparing the image, it stays in colour with a spinner.
            disabledContainerColor = if (isSharing) MulberryPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledContentColor = if (isSharing) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        ),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
            .height(56.dp)
    ) {
        if (isSharing) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_material_share),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
        }
        Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
        Text(
            text = stringResource(R.string.share_action),
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun SharePreview(preview: Bitmap?) {
    val description = stringResource(R.string.share_preview_content_description)
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .aspectRatio(SHARE_OUTPUT_WIDTH.toFloat() / SHARE_OUTPUT_HEIGHT)
            .shadow(elevation = 10.dp, shape = shape)
            .clip(shape)
            .background(MaterialTheme.mulberryAppColors.inputSurface)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        if (preview == null) {
            CircularProgressIndicator(color = MulberryPrimary, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
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

/** The frames, shown only as thumbnails; TalkBack still reads each name. */
@Composable
private fun FrameRow(
    frames: List<ShareFrameOption>,
    selectedKey: String?,
    onFrameSelected: (String) -> Unit
) {
    // The catalog's frames arrive after the bundled ones and can go in front of them; the row
    // goes back to its start each time, so the chosen frame isn't left half out of view.
    val listState = rememberLazyListState()
    val keys = frames.map { it.key }
    LaunchedEffect(keys) { listState.scrollToItem(0) }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(frames, key = { it.key }) { frame ->
            val selected = frame.key == selectedKey
            Box(
                modifier = Modifier
                    .clip(TILE_SHAPE)
                    .selectable(selected = selected, role = Role.Button, onClick = { onFrameSelected(frame.key) })
                    .semantics { contentDescription = frame.title }
                    .border(
                        width = 2.5.dp,
                        color = if (selected) MulberryPrimary else Color.Transparent,
                        shape = TILE_SHAPE
                    )
                    .padding(3.dp)
                    .size(width = 62.dp, height = 110.dp)
                    .clip(TILE_IMAGE_SHAPE)
                    .background(MaterialTheme.mulberryAppColors.inputSurface)
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

/** The caption, as a Material outlined field, and the wallpaper switch as a list row. */
@Composable
private fun ShareSettings(
    caption: String,
    onCaptionChanged: (String) -> Unit,
    onDone: () -> Unit,
    useRealWallpaper: Boolean,
    realWallpaperAvailable: Boolean,
    onUseRealWallpaperChanged: (Boolean) -> Unit
) {
    val textStyle = TextStyle(fontFamily = PoppinsFontFamily, fontSize = 16.sp, lineHeight = 22.sp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 4.dp)
    ) {
        OutlinedTextField(
            value = caption,
            onValueChange = onCaptionChanged,
            label = { Text(stringResource(R.string.share_caption_content_description), fontFamily = PoppinsFontFamily) },
            maxLines = 3,
            textStyle = textStyle,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MulberryPrimary,
                focusedLabelColor = MulberryPrimary,
                cursorColor = MulberryPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        )
        // One row to TalkBack: the label and its switch together.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_HEIGHT)
                .toggleable(
                    value = useRealWallpaper,
                    enabled = realWallpaperAvailable,
                    role = Role.Switch,
                    onValueChange = onUseRealWallpaperChanged
                )
                .padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.share_wallpaper_mine),
                style = textStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .alpha(if (realWallpaperAvailable) 1f else 0.38f)
            )
            Switch(
                checked = useRealWallpaper,
                onCheckedChange = null,
                enabled = realWallpaperAvailable
            )
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private val TILE_SHAPE = RoundedCornerShape(13.dp)
private val TILE_IMAGE_SHAPE = RoundedCornerShape(10.dp)
private val ROW_HEIGHT = 56.dp
