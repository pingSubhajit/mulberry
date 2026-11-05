package com.subhajit.mulberry.home

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.ui.HapticOnChange
import com.subhajit.mulberry.core.ui.MulberryHaptic
import com.subhajit.mulberry.core.ui.rememberMulberryHaptics
import com.subhajit.mulberry.drawing.model.CanvasPhotoElement
import com.subhajit.mulberry.drawing.model.CanvasPhotoStyle
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.network.CanvasPhotoUploadResponse
import com.subhajit.mulberry.photos.CanvasPhotoProcessing
import com.subhajit.mulberry.photos.CanvasPhotoRenderer
import com.subhajit.mulberry.photos.CanvasPhotoStore
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** A photo on its way to the canvas, or one already there being restyled. */
data class CanvasPhotoSession(
    val id: String,
    /** The photo on the canvas being edited; null for a new one, which starts at the camera. */
    val element: CanvasPhotoElement?
) {
    val isNew: Boolean
        get() = element == null

    companion object {
        fun new(): CanvasPhotoSession = CanvasPhotoSession(id = UUID.randomUUID().toString(), element = null)
        fun edit(element: CanvasPhotoElement): CanvasPhotoSession = CanvasPhotoSession(id = element.id, element = element)
    }
}

/** What the canvas needs for the Camera tool, handed down the screen as one. */
class CanvasPhotoHooks(
    val photoStore: CanvasPhotoStore,
    val onCameraToggle: () -> Unit,
    val onPhotoElementUpdated: (CanvasPhotoElement) -> Unit,
    val onPhotoElementDeleted: (String) -> Unit,
    val onPhotoEditRequested: (CanvasPhotoElement) -> Unit,
    val onNewPhotoRequested: () -> Unit,
    val onCanvasBoundsChanged: (Rect) -> Unit,
    /** The photo the editor has open, which the canvas leaves out meanwhile. */
    val hiddenElementId: String?
)

private enum class PhotoPhase { CAMERA, EDITING }
private enum class CutoutState { IDLE, WORKING, READY, MISSING }
private enum class PhotoPanel { NONE, SIZE, ROTATION }

/**
 * The Camera tool, as on iPhone: a camera with the library a tap away, then the photo over the
 * dimmed canvas, where it takes a frame, a polaroid's paper or a sticker's outline. [canvasBounds]
 * is the canvas in root pixels, so the photo is edited at the size it lands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasPhotoFlow(
    session: CanvasPhotoSession,
    canvasBounds: Rect,
    photoStore: CanvasPhotoStore,
    onDone: (CanvasPhotoElement) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val haptics = rememberMulberryHaptics()
    val existing = session.element

    var phase by remember(session.id) { mutableStateOf(if (existing == null) PhotoPhase.CAMERA else PhotoPhase.EDITING) }
    var photo by remember(session.id) { mutableStateOf(existing?.let { photoStore.cachedBitmap(it.photoId) }) }
    var cutout by remember(session.id) { mutableStateOf(existing?.cutoutPhotoId?.let(photoStore::cachedBitmap)) }
    var cutoutState by remember(session.id) { mutableStateOf(if (cutout != null) CutoutState.READY else CutoutState.IDLE) }
    var style by remember(session.id) { mutableStateOf(existing?.style ?: CanvasPhotoStyle.FRAME) }
    var scale by remember(session.id) { mutableFloatStateOf(existing?.renderScale ?: CanvasPhotoElement.DEFAULT_SCALE) }
    var rotationDeg by remember(session.id) {
        mutableFloatStateOf(existing?.let { (it.rotationRad * 180f / PI.toFloat()).coerceIn(-180f, 180f) } ?: 0f)
    }
    var upload by remember(session.id) { mutableStateOf<Deferred<Result<CanvasPhotoUploadResponse>>?>(null) }
    var uploadedPhotoId by remember(session.id) { mutableStateOf(existing?.photoId) }
    var uploadedCutoutId by remember(session.id) { mutableStateOf(existing?.cutoutPhotoId) }
    var saving by remember(session.id) { mutableStateOf(false) }
    var failed by remember(session.id) { mutableStateOf(false) }
    var panel by remember(session.id) { mutableStateOf(PhotoPanel.NONE) }
    var flowOrigin by remember { mutableStateOf(Offset.Zero) }

    HapticOnChange(style, MulberryHaptic.Selection)
    HapticOnChange(rotationDeg == 0f, MulberryHaptic.RotationSnap)

    fun startCutout(image: Bitmap) {
        cutoutState = CutoutState.WORKING
        scope.launch {
            val result = runCatching { CanvasPhotoProcessing.cutout(image) }.getOrNull()
            if (photo !== image) return@launch
            cutout = result
            cutoutState = if (result == null) CutoutState.MISSING else CutoutState.READY
        }
    }

    /** The photo is in: it uploads, and its subject is cut out, while it is styled. */
    fun arrive(image: Bitmap) {
        photo = image
        failed = false
        uploadedPhotoId = null
        uploadedCutoutId = null
        cutout = null
        upload = scope.async(Dispatchers.IO) {
            runCatching { photoStore.upload(CanvasPhotoProcessing.jpeg(image), "image/jpeg", image) }
        }
        startCutout(image)
        haptics.perform(MulberryHaptic.LightImpact)
        phase = PhotoPhase.EDITING
    }

    // A photo already on the canvas: what wasn't in memory, then its cut-out, or a new one.
    LaunchedEffect(session.id) {
        val element = existing ?: return@LaunchedEffect
        if (photo == null) photo = photoStore.loadBitmap(element.photoId)
        if (cutoutState == CutoutState.READY) return@LaunchedEffect
        val stored = element.cutoutPhotoId?.let { photoStore.loadBitmap(it) }
        if (stored != null) {
            cutout = stored
            cutoutState = CutoutState.READY
        } else {
            photo?.let(::startCutout)
        }
    }

    fun retake() {
        upload?.cancel()
        upload = null
        photo = null
        cutout = null
        cutoutState = CutoutState.IDLE
        style = CanvasPhotoStyle.FRAME
        scale = CanvasPhotoElement.DEFAULT_SCALE
        rotationDeg = 0f
        panel = PhotoPanel.NONE
        failed = false
        phase = PhotoPhase.CAMERA
    }

    fun done() {
        val image = photo ?: return
        if (saving) return
        saving = true
        failed = false
        scope.launch {
            try {
                val photoId = uploadedPhotoId
                    ?: (upload ?: error("No upload")).await().getOrThrow().photoId.also { uploadedPhotoId = it }
                var cutoutId = uploadedCutoutId
                val cutoutImage = cutout
                if (style == CanvasPhotoStyle.STICKER && cutoutId == null && cutoutImage != null) {
                    val bytes = withContext(Dispatchers.Default) { CanvasPhotoProcessing.png(cutoutImage) }
                    cutoutId = photoStore.upload(bytes, "image/png", cutoutImage).photoId
                    uploadedCutoutId = cutoutId
                }
                val base = existing ?: CanvasPhotoElement(
                    id = UUID.randomUUID().toString(),
                    createdAt = System.currentTimeMillis(),
                    center = StrokePoint(0.5f, 0.5f),
                    photoId = photoId,
                    aspectRatio = image.width.toFloat() / image.height.coerceAtLeast(1)
                )
                onDone(
                    base.copy(
                        photoId = photoId,
                        cutoutPhotoId = cutoutId,
                        style = style,
                        scale = scale,
                        rotationRad = rotationDeg * PI.toFloat() / 180f
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                saving = false
                failed = true
            }
        }
    }

    fun cancel() {
        upload?.cancel()
        onCancel()
    }

    // Back leaves the camera; in the editor it keeps the photo, as tapping outside it does.
    BackHandler {
        if (phase == PhotoPhase.CAMERA) cancel() else done()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (phase == PhotoPhase.CAMERA) 0.9f else 0.78f))
            .onGloballyPositioned { coordinates -> flowOrigin = coordinates.boundsInRoot().topLeft }
    ) {
        when (phase) {
            PhotoPhase.CAMERA -> CameraPhase(
                lifecycleOwner = lifecycleOwner,
                context = context,
                onClose = ::cancel,
                onCaptured = ::arrive
            )
            PhotoPhase.EDITING -> {
                // Tapping outside the photo keeps it, as in the other editors.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerTapToDone(enabled = !saving) { done() }
                )
                val image = photo
                if (image != null) {
                    PhotoCard(
                        image = image,
                        cutout = if (style == CanvasPhotoStyle.STICKER && cutoutState == CutoutState.READY) cutout else null,
                        style = style,
                        scale = scale,
                        rotationDeg = rotationDeg,
                        canvasBounds = canvasBounds,
                        flowOrigin = flowOrigin
                    )
                }
                PhotoEditorControls(
                    isNew = session.isNew,
                    saving = saving,
                    failed = failed,
                    canFinish = photo != null,
                    style = style,
                    cutoutWorking = cutoutState == CutoutState.WORKING,
                    scale = scale,
                    rotationDeg = rotationDeg,
                    panel = panel,
                    onRetake = ::retake,
                    onDone = ::done,
                    onStyle = { style = it },
                    onScale = { scale = it },
                    onRotation = { rotationDeg = ElementRotation.settleSliderDegrees(it.coerceIn(-180f, 180f)) },
                    onPanel = { next -> panel = if (panel == next) PhotoPanel.NONE else next },
                    onDelete = { if (session.isNew) cancel() else onDelete() }
                )
            }
        }
    }
}

private fun Modifier.pointerTapToDone(enabled: Boolean, onTap: () -> Unit): Modifier =
    if (!enabled) this else pointerInput(onTap) { detectTapGestures { onTap() } }

/** The photo as the canvas will draw it, at the canvas's middle, at the size it lands. */
@Composable
private fun PhotoCard(
    image: Bitmap,
    cutout: Bitmap?,
    style: CanvasPhotoStyle,
    scale: Float,
    rotationDeg: Float,
    canvasBounds: Rect,
    flowOrigin: Offset
) {
    val canvasWidth = canvasBounds.width.coerceAtLeast(1f)
    // Drawn once per look at the largest size, then scaled while the size slider moves.
    val baseScale = CanvasPhotoElement.SCALE_MAX
    val rendered by produceState<CanvasPhotoRenderer.Rendered?>(null, image, cutout, style, canvasWidth) {
        value = withContext(Dispatchers.Default) {
            CanvasPhotoRenderer.render(
                photo = CanvasPhotoElement(
                    id = "editor",
                    createdAt = 0L,
                    center = StrokePoint(0.5f, 0.5f),
                    scale = baseScale,
                    photoId = "editor",
                    aspectRatio = image.width.toFloat() / image.height.coerceAtLeast(1),
                    style = style
                ),
                image = image,
                cutout = cutout,
                canvasWidth = canvasWidth
            )
        }
    }
    val appear by animateFloatAsState(if (rendered != null) 1f else 0f, tween(180), label = "photoCardAppear")
    val label = stringResource(R.string.photo_editor_photo)
    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = label }
    ) {
        val card = rendered ?: return@Canvas
        val center = canvasBounds.center - flowOrigin
        val grow = scale / baseScale
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate(center.x, center.y)
            native.rotate(rotationDeg)
            native.scale(grow, grow)
            val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
                alpha = (appear * 255).roundToInt()
            }
            native.drawBitmap(card.bitmap, -card.width / 2f, -card.height / 2f, paint)
            native.restore()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoEditorControls(
    isNew: Boolean,
    saving: Boolean,
    failed: Boolean,
    canFinish: Boolean,
    style: CanvasPhotoStyle,
    cutoutWorking: Boolean,
    scale: Float,
    rotationDeg: Float,
    panel: PhotoPanel,
    onRetake: () -> Unit,
    onDone: () -> Unit,
    onStyle: (CanvasPhotoStyle) -> Unit,
    onScale: (Float) -> Unit,
    onRotation: (Float) -> Unit,
    onPanel: (PhotoPanel) -> Unit,
    onDelete: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        // A new photo can go back to the camera; Done works as in the other editors.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isNew) {
                RoundCameraButton(
                    iconRes = R.drawable.ic_material_refresh,
                    contentDescription = stringResource(R.string.photo_editor_retake),
                    enabled = !saving,
                    onClick = onRetake
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (saving) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            } else {
                Text(
                    text = stringResource(R.string.photo_editor_done),
                    color = Color.White.copy(alpha = if (canFinish) 1f else 0.4f),
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    modifier = Modifier.clickable(
                        enabled = canFinish,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onDone
                    )
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (failed) {
                Text(
                    text = stringResource(R.string.photo_editor_failed),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
            when (panel) {
                PhotoPanel.SIZE -> PhotoSliderBar {
                    val sizeLabel = stringResource(R.string.photo_editor_size)
                    Slider(
                        value = scale,
                        onValueChange = onScale,
                        valueRange = CanvasPhotoElement.SCALE_MIN..CanvasPhotoElement.SCALE_MAX,
                        modifier = Modifier.weight(1f).semantics {
                            contentDescription = sizeLabel
                            stateDescription = "${(scale / CanvasPhotoElement.DEFAULT_SCALE * 100).roundToInt()} percent"
                        }
                    )
                }
                PhotoPanel.ROTATION -> PhotoSliderBar {
                    val rotationLabel = stringResource(R.string.photo_editor_rotation)
                    Slider(
                        value = rotationDeg,
                        onValueChange = onRotation,
                        valueRange = -180f..180f,
                        modifier = Modifier.weight(1f).semantics {
                            contentDescription = rotationLabel
                            stateDescription = "${rotationDeg.roundToInt()} degrees"
                        }
                    )
                }
                PhotoPanel.NONE -> Unit
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The three styles as one native segmented control, as icons.
                SingleChoiceSegmentedButtonRow(modifier = Modifier.width(168.dp)) {
                    val styles = CanvasPhotoStyle.entries
                    styles.forEachIndexed { index, option ->
                        val title = stringResource(option.titleRes())
                        SegmentedButton(
                            selected = style == option,
                            onClick = { onStyle(option) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = styles.size),
                            icon = {},
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = Color.White.copy(alpha = 0.28f),
                                activeContentColor = Color.White,
                                activeBorderColor = Color.White.copy(alpha = 0.3f),
                                inactiveContainerColor = Color.White.copy(alpha = 0.10f),
                                inactiveContentColor = Color.White.copy(alpha = 0.85f),
                                inactiveBorderColor = Color.White.copy(alpha = 0.3f)
                            )
                        ) {
                            if (option == CanvasPhotoStyle.STICKER && cutoutWorking) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp).semantics { contentDescription = title }
                                )
                            } else {
                                Icon(
                                    painter = painterResource(option.iconRes()),
                                    contentDescription = title,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
                PhotoEditorIconButton(
                    iconRes = R.drawable.ic_text_toolbar_size,
                    contentDescription = stringResource(R.string.photo_editor_size),
                    selected = panel == PhotoPanel.SIZE
                ) { onPanel(PhotoPanel.SIZE) }
                PhotoEditorIconButton(
                    iconRes = R.drawable.ic_sticker_toolbar_rotate,
                    contentDescription = stringResource(R.string.photo_editor_rotation),
                    selected = panel == PhotoPanel.ROTATION
                ) { onPanel(PhotoPanel.ROTATION) }
                Spacer(modifier = Modifier.weight(1f))
                PhotoEditorIconButton(
                    iconRes = R.drawable.ic_text_toolbar_delete,
                    contentDescription = stringResource(R.string.photo_editor_delete),
                    selected = false,
                    onClick = onDelete
                )
            }
        }
    }
}

private fun CanvasPhotoStyle.titleRes(): Int = when (this) {
    CanvasPhotoStyle.FRAME -> R.string.photo_editor_style_simple
    CanvasPhotoStyle.POLAROID -> R.string.photo_editor_style_polaroid
    CanvasPhotoStyle.STICKER -> R.string.photo_editor_style_sticker
}

private fun CanvasPhotoStyle.iconRes(): Int = when (this) {
    CanvasPhotoStyle.FRAME -> R.drawable.ic_photo_style_simple
    CanvasPhotoStyle.POLAROID -> R.drawable.ic_photo_style_polaroid
    CanvasPhotoStyle.STICKER -> R.drawable.ic_photo_style_sticker
}

@Composable
private fun PhotoSliderBar(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** The editors' round button: the sticker and text editors draw theirs the same way. */
@Composable
private fun PhotoEditorIconButton(
    iconRes: Int,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(Color.White.copy(alpha = 0.10f), CircleShape)
            .then(
                if (selected) Modifier.border(2.dp, MulberryPrimary, CircleShape)
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .semantics(mergeDescendants = true) { this.selected = selected },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun RoundCameraButton(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean = true,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(22.dp)
        )
    }
}

private val fourByThree: ResolutionSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .build()

/** The camera's rounded 3:4 window, as photos are, with the shutter row low where camera apps keep it. */
@Composable
private fun CameraPhase(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    context: Context,
    onClose: () -> Unit,
    onCaptured: (Bitmap) -> Unit
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMulberryHaptics()
    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    var asked by remember { mutableStateOf(false) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashOn by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    var loadingPick by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(fourByThree)
            .build()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        asked = true
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            loadingPick = true
            val prepared = CanvasPhotoProcessing.prepared(context, uri)
            loadingPick = false
            if (prepared != null) onCaptured(prepared)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    // Back from Settings with access now given.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermission = context.hasCameraPermission()
    }

    LaunchedEffect(hasPermission, lensFacing) {
        if (!hasPermission) return@LaunchedEffect
        val cameraProvider = provider ?: context.cameraProvider().also { provider = it }
        val bound: Camera? = runCatching {
            cameraProvider.unbindAll()
            val preview = Preview.Builder().setResolutionSelector(fourByThree).build()
            preview.surfaceProvider = previewView.surfaceProvider
            var selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            if (!cameraProvider.hasCamera(selector)) selector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
        }.getOrNull()
        unavailable = bound == null
        running = bound != null
        hasFlash = bound?.cameraInfo?.hasFlashUnit() == true
    }
    DisposableEffect(Unit) {
        onDispose { provider?.unbindAll() }
    }

    fun snap() {
        if (capturing || !running) return
        capturing = true
        flash = true
        haptics.perform(MulberryHaptic.LightImpact)
        imageCapture.flashMode = if (flashOn && hasFlash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        val mirrored = lensFacing == CameraSelector.LENS_FACING_FRONT
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotation = image.imageInfo.rotationDegrees
                    val raw = runCatching { image.toBitmap() }.getOrNull()
                    image.close()
                    flash = false
                    if (raw == null) {
                        capturing = false
                        return
                    }
                    scope.launch {
                        val upright = withContext(Dispatchers.Default) {
                            CanvasPhotoProcessing.upright(raw, rotation, mirrored)
                        }
                        capturing = false
                        provider?.unbindAll()
                        onCaptured(upright)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    flash = false
                    capturing = false
                }
            }
        )
    }

    val flashAlpha by animateFloatAsState(if (flash) 0.6f else 0f, tween(if (flash) 60 else 200), label = "cameraFlash")

    BoxWithConstraints(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        val topReserve = 72.dp
        val bottomReserve = 128.dp
        val windowWidth = min(maxWidth.value - 32f, (maxHeight.value - topReserve.value - bottomReserve.value) * 3f / 4f).dp
        val windowHeight = windowWidth * 4f / 3f
        val windowTop = topReserve + ((maxHeight - topReserve - bottomReserve - windowHeight) / 2)

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = windowTop)
                .size(width = windowWidth, height = windowHeight)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF141414))
        ) {
            if (hasPermission) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            }
            Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = flashAlpha)))
            when {
                !hasPermission -> CameraNotice(
                    title = stringResource(R.string.photo_camera_access_off_title),
                    body = stringResource(R.string.photo_camera_access_off_body),
                    action = stringResource(R.string.photo_camera_allow_access)
                ) {
                    val activity = context.findActivity()
                    val canAsk = !asked || activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true
                    if (canAsk) {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    } else {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                unavailable -> CameraNotice(
                    title = stringResource(R.string.photo_camera_unavailable_title),
                    body = stringResource(R.string.photo_camera_unavailable_body),
                    action = null,
                    onAction = {}
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundCameraButton(
                iconRes = R.drawable.ic_material_close,
                contentDescription = stringResource(R.string.photo_camera_close),
                onClick = onClose
            )
            Spacer(modifier = Modifier.weight(1f))
            if (hasFlash) {
                RoundCameraButton(
                    iconRes = if (flashOn) R.drawable.ic_material_flash_on else R.drawable.ic_material_flash_off,
                    contentDescription = stringResource(if (flashOn) R.string.photo_camera_flash_on else R.string.photo_camera_flash_off),
                    tint = if (flashOn) Color(0xFFFFD60A) else Color.White,
                    onClick = { flashOn = !flashOn }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.size(44.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 32.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable(enabled = !loadingPick, role = Role.Button) {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                contentAlignment = Alignment.Center
            ) {
                val libraryLabel = stringResource(R.string.photo_camera_library)
                if (loadingPick) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_material_photo_library),
                        contentDescription = libraryLabel,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            val shutterLabel = stringResource(R.string.photo_camera_take)
            val pressScale by animateFloatAsState(if (capturing) 0.9f else 1f, tween(120), label = "shutterPress")
            Box(
                modifier = Modifier
                    .size(78.dp * pressScale)
                    .border(5.dp, Color.White.copy(alpha = if (running) 1f else 0.4f), CircleShape)
                    .clip(CircleShape)
                    .clickable(enabled = running && !capturing, role = Role.Button, onClick = ::snap)
                    .semantics { contentDescription = shutterLabel },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp * pressScale)
                        .background(Color.White.copy(alpha = if (running) 1f else 0.4f), CircleShape)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            RoundCameraButton(
                iconRes = R.drawable.ic_material_cameraswitch,
                contentDescription = stringResource(R.string.photo_camera_switch),
                enabled = running,
                onClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                }
            )
        }
    }
}

/** Why there is no picture: access not given, or the camera unavailable. */
@Composable
private fun CameraNotice(title: String, body: String, action: String?, onAction: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.size(6.dp))
        Text(body, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(modifier = Modifier.size(16.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = MulberryPrimary, contentColor = Color.White)
            ) { Text(action) }
        }
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private suspend fun Context.cameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { continuation ->
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({ continuation.resume(future.get()) }, ContextCompat.getMainExecutor(this))
}
