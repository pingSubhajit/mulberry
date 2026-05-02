package com.subhajit.mulberry.home

import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.subhajit.mulberry.R
import com.subhajit.mulberry.drawing.model.CanvasStickerElement
import com.subhajit.mulberry.stickers.StickerAssetStore
import com.subhajit.mulberry.stickers.StickerAssetVariant
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import kotlin.math.PI

data class StickerSelection(
    val packKey: String,
    val packVersion: Int,
    val stickerId: String
)

data class CanvasStickerEditorSession(
    val element: CanvasStickerElement,
    val isNew: Boolean
)

private enum class StickerEditorPanel {
    PICKER,
    SIZE,
    ROTATION,
}

@Composable
fun StickerEditorOverlay(
    session: CanvasStickerEditorSession,
    uiState: CanvasHomeUiState,
    stickerAssetStore: StickerAssetStore,
    onPackSelected: (String, Int) -> Unit,
    onStickerPicked: (StickerSelection) -> Unit,
    onDone: (CanvasStickerElement) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by remember(session.element.id) { mutableStateOf(session.element) }
    var scale by remember(session.element.id) { mutableStateOf(draft.scale.coerceIn(SCALE_MIN, SCALE_MAX)) }
    var rotationDeg by remember(session.element.id) {
        mutableStateOf(radToDeg(draft.rotationRad).coerceIn(ROTATION_MIN_DEG, ROTATION_MAX_DEG))
    }
    var panel by remember(session.element.id) { mutableStateOf(StickerEditorPanel.PICKER) }
    var enter by remember(session.element.id) { mutableStateOf(false) }

    LaunchedEffect(session.element.id) {
        enter = true
    }

    val overlayAlpha by animateFloatAsState(
        targetValue = if (enter) 1f else 0f,
        animationSpec = tween(durationMillis = 110, easing = FastOutLinearInEasing),
        label = "stickerEditorOverlayAlpha"
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (enter) 1f else 0f,
        animationSpec = tween(durationMillis = 120, delayMillis = 45, easing = FastOutSlowInEasing),
        label = "stickerEditorContentAlpha"
    )
    val contentScale by animateFloatAsState(
        targetValue = if (enter) 1f else 0.98f,
        animationSpec = tween(durationMillis = 120, delayMillis = 45, easing = FastOutSlowInEasing),
        label = "stickerEditorContentScale"
    )

    fun commitDraftUpdate(next: CanvasStickerElement) {
        draft = next
        scale = next.scale.coerceIn(SCALE_MIN, SCALE_MAX)
        rotationDeg = radToDeg(next.rotationRad).coerceIn(ROTATION_MIN_DEG, ROTATION_MAX_DEG)
    }

    fun commitAndExit() {
        onDone(draft)
    }

    fun deleteAndExit() {
        onDelete(draft.id)
    }

    BackHandler(onBack = { commitAndExit() })

    var previewBitmap by remember(draft.packKey, draft.packVersion, draft.stickerId) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(draft.packKey, draft.packVersion, draft.stickerId) {
        previewBitmap = null
        val file = stickerAssetStore.getOrDownloadStickerAsset(
            packKey = draft.packKey,
            packVersion = draft.packVersion,
            stickerId = draft.stickerId,
            variant = StickerAssetVariant.FULL
        ) ?: return@LaunchedEffect
        previewBitmap = BitmapFactory.decodeFile(file.absolutePath)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f * overlayAlpha))
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Background hit target: tapping outside exits edit mode.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { commitAndExit() }
                }
        )

        // Top "Done"
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 18.dp, vertical = 12.dp)
                .alpha(contentAlpha)
        ) {
            Text(
                text = "Done",
                color = Color.White,
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        commitAndExit()
                    }
            )
        }

        // Centered sticker preview (no chrome/background).
        // Keep preview placement stable across panels to avoid visual jitter.
        val previewBottomPadding = 320.dp
        val previewYOffset = (-96).dp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .padding(top = 64.dp, bottom = previewBottomPadding),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = previewBitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Sticker preview",
                    modifier = Modifier
                        .size(240.dp)
                        .offset(y = previewYOffset)
                        .graphicsLayer {
                            scaleX = contentScale
                            scaleY = contentScale
                            rotationZ = rotationDeg
                            val t = previewScaleMultiplier(scale)
                            scaleX *= t
                            scaleY *= t
                        }
                )
            }
        }

        // Bottom toolbars (panel content + secondary bar).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .alpha(contentAlpha),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (panel) {
                StickerEditorPanel.PICKER -> {
                    StickerPickerPanel(
                        uiState = uiState,
                        stickerAssetStore = stickerAssetStore,
                        onPackSelected = onPackSelected,
                        onStickerChosen = { packKey, packVersion, stickerId ->
                            onStickerPicked(StickerSelection(packKey, packVersion, stickerId))
                            commitDraftUpdate(draft.copy(packKey = packKey, packVersion = packVersion, stickerId = stickerId))
                        },
                        columns = 3,
                        stickerTileSize = 92.dp,
                        gridHeight = 240.dp,
                        chrome = StickerPickerChrome.Floating
                    )
                }

                StickerEditorPanel.SIZE -> {
                    EditorTertiaryBar {
                        Slider(
                            value = scale.coerceIn(SCALE_MIN, SCALE_MAX),
                            onValueChange = { value ->
                                val next = value.coerceIn(SCALE_MIN, SCALE_MAX)
                                commitDraftUpdate(draft.copy(scale = next))
                            },
                            valueRange = SCALE_MIN..SCALE_MAX,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                StickerEditorPanel.ROTATION -> {
                    EditorTertiaryBar {
                        Slider(
                            value = rotationDeg.coerceIn(ROTATION_MIN_DEG, ROTATION_MAX_DEG),
                            onValueChange = { value ->
                                val nextDeg = value.coerceIn(ROTATION_MIN_DEG, ROTATION_MAX_DEG)
                                commitDraftUpdate(draft.copy(rotationRad = degToRad(nextDeg)))
                            },
                            valueRange = ROTATION_MIN_DEG..ROTATION_MAX_DEG,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            EditorSecondaryBar(
                panel = panel,
                onPanelChanged = { next ->
                    panel = if (panel == next) StickerEditorPanel.PICKER else next
                },
                onDelete = { deleteAndExit() }
            )
        }
    }
}

@Composable
private fun EditorSecondaryBar(
    panel: StickerEditorPanel,
    onPanelChanged: (StickerEditorPanel) -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EditorIconButton(
            iconRes = R.drawable.ic_sticker_toolbar_picker,
            contentDescription = "Picker",
            selected = panel == StickerEditorPanel.PICKER
        ) { onPanelChanged(StickerEditorPanel.PICKER) }

        EditorIconButton(
            iconRes = R.drawable.ic_text_toolbar_size,
            contentDescription = "Size",
            selected = panel == StickerEditorPanel.SIZE
        ) { onPanelChanged(StickerEditorPanel.SIZE) }

        EditorIconButton(
            iconRes = R.drawable.ic_sticker_toolbar_rotate,
            contentDescription = "Rotation",
            selected = panel == StickerEditorPanel.ROTATION
        ) { onPanelChanged(StickerEditorPanel.ROTATION) }

        Box(modifier = Modifier.weight(1f))

        EditorIconButton(
            iconRes = R.drawable.ic_text_toolbar_delete,
            contentDescription = "Delete",
            selected = false
        ) { onDelete() }
    }
}

@Composable
private fun EditorTertiaryBar(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun EditorIconButton(
    iconRes: Int,
    contentDescription: String,
    selected: Boolean,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(Color.White.copy(alpha = 0.10f), CircleShape)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MulberryPrimary, CircleShape)
                } else {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}

private const val SCALE_MIN = 0.08f
private const val SCALE_MAX = 1.6f
private const val DEFAULT_SCALE = 0.22f
private const val ROTATION_MIN_DEG = -180f
private const val ROTATION_MAX_DEG = 180f

private fun radToDeg(rad: Float): Float = (rad * 180f / PI.toFloat())

private fun degToRad(deg: Float): Float = (deg * PI.toFloat() / 180f)

private fun previewScaleMultiplier(scale: Float): Float {
    val t = ((scale - SCALE_MIN) / (SCALE_MAX - SCALE_MIN)).coerceIn(0f, 1f)
    // Keep the preview comfortable: even max size shouldn't dominate the screen.
    return lerp(0.70f, 1.35f, t)
}

private fun lerp(start: Float, end: Float, t: Float): Float = start + (end - start) * t
