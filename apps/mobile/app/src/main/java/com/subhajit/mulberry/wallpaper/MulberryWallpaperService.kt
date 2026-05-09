package com.subhajit.mulberry.wallpaper

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.service.wallpaper.WallpaperService
import android.util.DisplayMetrics
import android.util.Log
import android.view.SurfaceHolder
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.content.res.ResourcesCompat
import com.subhajit.mulberry.R
import com.subhajit.mulberry.reactions.PendingReactionBatch
import com.subhajit.mulberry.reactions.PendingReactionStore
import com.subhajit.mulberry.reactions.ReactionGifAssets
import com.subhajit.mulberry.reactions.ReactionLocalStore
import com.subhajit.mulberry.reactions.ReactionRepository
import com.subhajit.mulberry.reactions.ReactionType
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.PI
import kotlin.math.sin

@AndroidEntryPoint
class MulberryWallpaperService : WallpaperService() {

    @Inject lateinit var wallpaperCoordinator: WallpaperCoordinator
    @Inject lateinit var wallpaperRenderStateLoader: WallpaperRenderStateLoader
    @Inject lateinit var backgroundImageRepository: BackgroundImageRepository
    @Inject lateinit var wallpaperSyncSettingsRepository: WallpaperSyncSettingsRepository
    @Inject lateinit var pendingReactionStore: PendingReactionStore
    @Inject lateinit var reactionRepository: ReactionRepository
    @Inject lateinit var reactionLocalStore: ReactionLocalStore

    override fun onCreateEngine(): Engine = MulberryWallpaperEngine()

    private inner class MulberryWallpaperEngine : Engine() {
        private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val drawMutex = Mutex()
        private var isEngineVisible: Boolean = false
        private var isSurfaceAvailable: Boolean = false
        private var pendingRedraw: Boolean = false
        private var wallpaperSyncEnabled: Boolean = true
        private var lastKnownSurfaceWidthPx: Int = 0
        private var lastKnownSurfaceHeightPx: Int = 0
        private var lastKnownViewportWidthPx: Int = 0
        private var lastKnownViewportHeightPx: Int = 0
        private var stabilityRedrawJob: Job? = null
        private var latestWallpaperStatus: WallpaperStatusState = WallpaperStatusState()
        private var pendingReaction: PendingReactionBatch? = null
        private var reactionLeaseJob: Job? = null
        private var reactionFrameJob: Job? = null
        private var reactionAnimation: ReactionAnimationState? = null
        private var reactionRetryJob: Job? = null
        private var receiverRegistered: Boolean = false

        private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }

        private val reactionGifLayerPaint = Paint()
        private val reactionMovieCache = mutableMapOf<ReactionType, Movie?>()

        private var cachedBackgroundBitmap: CachedBitmap? = null
        private var cachedSnapshotBitmap: CachedBitmap? = null

        private val spamTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = 0xFFFFFFFF.toInt()
        }

        private val userPresentReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_USER_PRESENT) return
                evaluateReactionPlayback("user_present")
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            isSurfaceAvailable = surfaceHolder.surface?.isValid == true
            engineScope.launch(Dispatchers.IO) {
                // Ensure wallpaper selection status is refreshed even if no drawing updates have
                // occurred yet (reactions rely on this for lock-screen eligibility decisions).
                wallpaperCoordinator.notifyWallpaperUpdated()
            }
            engineScope.launch {
                WallpaperRenderBus.updates().collect {
                    requestDraw("render_bus")
                }
            }
            engineScope.launch {
                wallpaperSyncSettingsRepository.enabled.collectLatest { enabled ->
                    wallpaperSyncEnabled = enabled
                    requestDraw("wallpaper_sync_changed")
                }
            }
            engineScope.launch {
                wallpaperCoordinator.wallpaperStatus().collectLatest { status ->
                    latestWallpaperStatus = status
                    evaluateReactionPlayback("wallpaper_status")
                }
            }
            engineScope.launch {
                pendingReactionStore.pending.collectLatest { batch ->
                    pendingReaction = batch
                    evaluateReactionPlayback("reaction_pending_changed")
                    requestDraw("reaction_pending_changed")
                }
            }
            registerUserPresentReceiverIfNeeded()
            requestDraw("engine_create")
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            isEngineVisible = visible
            if (visible) {
                requestDraw("visibility_changed")
                scheduleStabilityRedraw("visibility_changed")
                evaluateReactionPlayback("visibility_changed")
            } else {
                resetUnrenderedReactionAnimation()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            isSurfaceAvailable = true
            requestDraw("surface_created")
            scheduleStabilityRedraw("surface_created")
            evaluateReactionPlayback("surface_created")
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            isSurfaceAvailable = false
            resetUnrenderedReactionAnimation()
            clearBitmapCaches()
            super.onSurfaceDestroyed(holder)
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            requestDraw("surface_redraw_needed")
            scheduleStabilityRedraw("surface_redraw_needed")
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            lastKnownSurfaceWidthPx = width
            lastKnownSurfaceHeightPx = height
            isSurfaceAvailable = holder.surface?.isValid == true
            requestDraw("surface_changed")
            scheduleStabilityRedraw("surface_changed")
        }

        override fun onApplyWindowInsets(insets: WindowInsets) {
            super.onApplyWindowInsets(insets)
            // System bars / cutout insets can change without a surface resize (e.g. returning
            // from an immersive fullscreen app). Request a redraw so our viewport sizing stays correct.
            requestDraw("window_insets_changed")
            scheduleStabilityRedraw("window_insets_changed")
        }

        override fun onDestroy() {
            unregisterUserPresentReceiverIfNeeded()
            clearBitmapCaches()
            engineScope.cancel()
            super.onDestroy()
        }

        private fun scheduleStabilityRedraw(reason: String) {
            stabilityRedrawJob?.cancel()
            stabilityRedrawJob = engineScope.launch {
                // Some OEM launchers report intermediate/incorrect surface sizes after leaving a
                // fullscreen (immersive) app. A follow-up redraw helps ensure we render again once
                // the wallpaper surface stabilizes.
                delay(250)
                requestDraw("stability_250ms:$reason")
                delay(750)
                requestDraw("stability_1000ms:$reason")
            }
        }

        private fun requestDraw(reason: String) {
            if (!isEngineVisible || !isSurfaceAvailable || surfaceHolder.surface?.isValid != true) {
                pendingRedraw = true
                Log.d(
                    TAG,
                    "Skipping draw reason=$reason visible=$isEngineVisible surface=$isSurfaceAvailable"
                )
                return
            }
            pendingRedraw = false
            drawFrame()
        }

        private fun registerUserPresentReceiverIfNeeded() {
            if (receiverRegistered) return
            receiverRegistered = true
            runCatching {
                registerReceiver(userPresentReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
            }.onFailure { error ->
                Log.w(TAG, "Unable to register ACTION_USER_PRESENT receiver", error)
            }
        }

        private fun unregisterUserPresentReceiverIfNeeded() {
            if (!receiverRegistered) return
            receiverRegistered = false
            runCatching { unregisterReceiver(userPresentReceiver) }
        }

        private fun isWallpaperOnLockScreen(): Boolean {
            val manager = getSystemService(KeyguardManager::class.java) ?: return false
            return manager.isKeyguardLocked || manager.isDeviceLocked
        }

        private fun evaluateReactionPlayback(reason: String) {
            if (!isEngineVisible || !isSurfaceAvailable || surfaceHolder.surface?.isValid != true) return
            val batch = pendingReaction ?: return
            if (batch.totalCount <= 0) return
            if (reactionAnimation != null) return

            val locked = isWallpaperOnLockScreen()
            val preview = isPreview

            // Simplified policy: never play reactions on the lock screen (even if the wallpaper is
            // selected there). If a user uses Mulberry only on lock screen, we can support that
            // later with a separate, privacy-reviewed policy.
            if (!reactionPlaybackSurfaceEligible(isLocked = locked, isPreview = preview)) return

            maybeStartReactionPlayback("eligible_unlocked:$reason")
        }

        private fun maybeStartReactionPlayback(reason: String) {
            val batch = pendingReaction ?: return
            if (reactionAnimation != null) return
            if (reactionLeaseJob?.isActive == true) return

            reactionRetryJob?.cancel()
            reactionLeaseJob = engineScope.launch(Dispatchers.IO) {
                val deviceId = reactionLocalStore.getOrCreateDeviceId()
                val leaseResult = reactionRepository.leasePlayback(
                    generation = batch.generation,
                    deviceId = deviceId
                )
                val leaseStatus = leaseResult.getOrNull()

                if (leaseStatus != "CLAIMED") {
                    if (leaseStatus == "NO_PENDING") {
                        pendingReactionStore.clear()
                        WallpaperRenderBus.requestRedraw()
                    } else {
                        Log.i(TAG, "Reaction playback deferred reason=$reason status=${leaseStatus ?: "FAILED"}")
                        scheduleReactionRetry()
                    }
                    return@launch
                }

                val animation = buildReactionAnimation(batch)
                engineScope.launch {
                    reactionAnimation = animation
                    scheduleReactionAnimationFrames()
                }
            }
        }

        private fun scheduleReactionAnimationFrames() {
            val animation = reactionAnimation ?: return
            reactionFrameJob?.cancel()
            reactionFrameJob = engineScope.launch {
                while (true) {
                    if (reactionAnimationShouldEnd(
                            firstRenderedAtMs = animation.firstRenderedAtMs,
                            nowMs = System.currentTimeMillis(),
                            durationMs = animation.durationMs
                        )
                    ) {
                        break
                    }
                    requestDraw("reaction_anim")
                    delay(16)
                }
                requestDraw("reaction_anim_end")
                val generationToConfirm = animation.generation
                val shouldConfirm = animation.hasRendered
                reactionAnimation = null
                if (!shouldConfirm) {
                    evaluateReactionPlayback("reaction_anim_not_rendered")
                    return@launch
                }
                engineScope.launch(Dispatchers.IO) {
                    val deviceId = reactionLocalStore.getOrCreateDeviceId()
                    reactionRepository.confirmPlayed(generationToConfirm, deviceId)
                    pendingReactionStore.clearIfGeneration(generationToConfirm)
                    WallpaperRenderBus.requestRedraw()
                    engineScope.launch {
                        evaluateReactionPlayback("reaction_confirmed")
                    }
                }
            }
        }

        private fun scheduleReactionRetry() {
            reactionRetryJob?.cancel()
            reactionRetryJob = engineScope.launch {
                delay(2_000)
                evaluateReactionPlayback("reaction_retry")
            }
        }

        private fun resetUnrenderedReactionAnimation() {
            val animation = reactionAnimation ?: return
            if (animation.hasRendered) return
            reactionFrameJob?.cancel()
            reactionAnimation = null
        }

        private fun buildReactionAnimation(batch: PendingReactionBatch): ReactionAnimationState {
            val durationMs = 2_350L

            val types = mutableListOf<ReactionType>()
            repeat(batch.heartCount.coerceAtMost(6)) { types.add(ReactionType.HEART) }
            repeat(batch.hugCount.coerceAtMost(6)) { types.add(ReactionType.HUG) }
            repeat(batch.kissCount.coerceAtMost(6)) { types.add(ReactionType.KISS) }
            repeat(batch.smileCount.coerceAtMost(6)) { types.add(ReactionType.SMILE) }
            repeat(batch.laughCount.coerceAtMost(6)) { types.add(ReactionType.LAUGH) }
            repeat(batch.sparkleCount.coerceAtMost(6)) { types.add(ReactionType.SPARKLE) }

            val uniqueTypes = types.map { it.apiValue }.toSet().size
            val glyphs = mutableListOf<ReactionGlyph>()

            if (uniqueTypes <= 1) {
                val type = types.firstOrNull() ?: ReactionType.HEART
                glyphs.add(ReactionGlyph(type, 0.5f, 0.52f, 1.40f))
                val smallCount = (batch.totalCount.coerceAtMost(4) - 1).coerceAtLeast(0)
                val anchors = listOf(
                    Anchor(0.38f, 0.46f),
                    Anchor(0.62f, 0.46f),
                    Anchor(0.42f, 0.62f),
                    Anchor(0.58f, 0.62f),
                )
                repeat(smallCount) { idx ->
                    val a = anchors[idx.coerceIn(0, anchors.lastIndex)]
                    glyphs.add(ReactionGlyph(type, a.x, a.y, 0.92f))
                }
            } else {
                val anchors = listOf(
                    Anchor(0.5f, 0.42f),
                    Anchor(0.35f, 0.50f),
                    Anchor(0.65f, 0.50f),
                    Anchor(0.42f, 0.62f),
                    Anchor(0.58f, 0.62f),
                    Anchor(0.5f, 0.70f),
                )
                val capped = types.distinctBy { it.apiValue }.take(6)
                capped.forEachIndexed { idx, type ->
                    val a = anchors[idx % anchors.size]
                    glyphs.add(ReactionGlyph(type, a.x, a.y, 1.15f))
                }
            }

            val spamText = if (uniqueTypes <= 1 && batch.totalCount >= 6) {
                when (types.firstOrNull() ?: ReactionType.HEART) {
                    ReactionType.HEART -> "You have been spammed with love."
                    ReactionType.HUG -> "You have been spammed with hugs."
                    ReactionType.KISS -> "You have been spammed with kisses."
                    ReactionType.SMILE -> "You have been spammed with smiles."
                    ReactionType.LAUGH -> "You have been spammed with laughs."
                    ReactionType.SPARKLE -> "You have been spammed with sparkles."
                }
            } else null

            return ReactionAnimationState(
                generation = batch.generation,
                durationMs = durationMs,
                glyphs = glyphs,
                spamText = spamText
            )
        }

        private fun getReactionMovie(type: ReactionType): Movie? {
            if (reactionMovieCache.containsKey(type)) return reactionMovieCache[type]
            val movie = ReactionGifAssets.decodeMovie(this@MulberryWallpaperService, type)
            if (movie != null) {
                reactionMovieCache[type] = movie
            }
            return movie
        }

        private fun drawReactionOverlayIfNeeded(canvas: Canvas) {
            if (isPreview) return
            val animation = reactionAnimation ?: return
            val now = System.currentTimeMillis()
            if (animation.firstRenderedAtMs == null) {
                animation.firstRenderedAtMs = now
            }
            val elapsed = reactionAnimationElapsedMs(
                firstRenderedAtMs = animation.firstRenderedAtMs,
                nowMs = now
            )
            if (elapsed < 0 || elapsed > animation.durationMs) return
            animation.hasRendered = true

            val tMs = elapsed.toFloat()
            val slideMs = 260f
            val wiggleMs = 1_200f
            val fallMs = 850f
            val fadeInMs = 160f
            val fadeOutMs = 650f

            val enterProgress = (tMs / fadeInMs).coerceIn(0f, 1f)
            val fallProgress = ((tMs - slideMs - wiggleMs) / fallMs).coerceIn(0f, 1f)
            val fadeOutProgress = ((tMs - (slideMs + wiggleMs + (fallMs - fadeOutMs))) / fadeOutMs)
                .coerceIn(0f, 1f)

            val alphaFloat = when {
                tMs < fadeInMs -> enterProgress
                tMs < slideMs + wiggleMs + (fallMs - fadeOutMs) -> 1f
                else -> 1f - (fadeOutProgress * fadeOutProgress)
            }
            val alpha = (alphaFloat * 255f).toInt().coerceIn(0, 255)

            val startOffsetPx = canvas.height * 0.06f
            val endUpOffsetPx = -canvas.height * 0.035f
            val fallEndOffsetPx = canvas.height * 0.09f
            val yOffsetPx = when {
                tMs < slideMs -> lerp(
                    startOffsetPx,
                    endUpOffsetPx,
                    easeOutBack((tMs / slideMs).coerceIn(0f, 1f))
                )
                tMs < slideMs + wiggleMs -> {
                    val seconds = (tMs - slideMs) / 1000f
                    val bob = sin((2f * PI.toFloat() * 3.2f) * seconds) * (canvas.height * 0.006f)
                    endUpOffsetPx + bob
                }
                else -> lerp(
                    endUpOffsetPx,
                    fallEndOffsetPx,
                    (fallProgress * fallProgress).coerceIn(0f, 1f)
                )
            }

            val wigglePhaseSeconds = (tMs / 1000f).coerceAtLeast(0f)
            val baseSize = (minOf(canvas.width, canvas.height) * 0.24f).coerceAtLeast(28f)

            // Leaf-like drift during fall.
            val xDriftPx = if (tMs < slideMs + wiggleMs) 0f else run {
                val fallSeconds = (tMs - slideMs - wiggleMs) / 1000f
                val amplitude = canvas.width * 0.035f
                sin((2f * PI.toFloat() * 1.15f) * fallSeconds) * amplitude * (1f - fallProgress * 0.35f)
            }

            emojiPaint.alpha = alpha
            animation.glyphs.forEachIndexed { idx, glyph ->
                val x = (canvas.width * glyph.x) + xDriftPx
                val y = canvas.height * glyph.y + yOffsetPx
                val baseAmp = 7.5f
                val slideRamp = (tMs / slideMs).coerceIn(0f, 1f)
                val fallDamp = if (tMs < slideMs + wiggleMs) 1f else (1f - fallProgress * 0.55f).coerceIn(0f, 1f)
                val wiggleAmp = baseAmp * slideRamp * fallDamp
                val rotationDeg =
                    sin((2f * PI.toFloat() * 3.2f) * wigglePhaseSeconds + idx * 0.7f) * wiggleAmp

                val movie = getReactionMovie(glyph.type)
                if (movie == null) {
                    // Fallback to emoji text if we fail to decode the GIF.
                    emojiPaint.textSize = baseSize * glyph.scale
                    val baseline = y - (emojiPaint.ascent() + emojiPaint.descent()) / 2f
                    canvas.save()
                    canvas.rotate(rotationDeg, x, baseline)
                    canvas.drawText(glyph.type.emoji, x, baseline, emojiPaint)
                    canvas.restore()
                    return@forEachIndexed
                }

                val movieWidth = movie.width().coerceAtLeast(1)
                val movieHeight = movie.height().coerceAtLeast(1)
                val targetSizePx = (baseSize * glyph.scale).coerceAtLeast(1f)
                val scale = (targetSizePx / maxOf(movieWidth, movieHeight).toFloat()).coerceAtLeast(0.01f)

                val drawnWidth = movieWidth * scale
                val drawnHeight = movieHeight * scale
                val left = x - drawnWidth / 2f
                val top = y - drawnHeight / 2f

                val movieDuration = movie.duration().takeIf { it > 0 } ?: 1_000
                movie.setTime((elapsed % movieDuration).toInt())

                reactionGifLayerPaint.alpha = alpha
                canvas.save()
                // Apply rotation about the center of the GIF.
                canvas.rotate(rotationDeg, x, y)
                // Layer bounds are in the current canvas coordinate space; pad them slightly to
                // avoid edge clipping when rotated.
                val pad = targetSizePx * 0.35f
                val layerId = canvas.saveLayer(
                    (left - pad),
                    (top - pad),
                    (left + drawnWidth + pad),
                    (top + drawnHeight + pad),
                    reactionGifLayerPaint
                )
                canvas.translate(left, top)
                canvas.scale(scale, scale)
                movie.draw(canvas, 0f, 0f)
                canvas.restoreToCount(layerId)
                canvas.restore()
            }

            val spam = animation.spamText ?: return
            spamTextPaint.alpha = (alpha * 0.92f).toInt().coerceIn(0, 255)
            spamTextPaint.textSize = (minOf(canvas.width, canvas.height) * 0.045f).coerceAtLeast(18f)
            spamTextPaint.typeface = ResourcesCompat.getFont(
                this@MulberryWallpaperService,
                R.font.virgil_regular
            ) ?: Typeface.DEFAULT
            val x = canvas.width * 0.5f
            // Spam caption should not "wiggle" with the glyph motion; keep it fixed in place.
            val y = canvas.height * 0.76f
            val baseline = y - (spamTextPaint.ascent() + spamTextPaint.descent()) / 2f
            canvas.drawText(spam, x, baseline, spamTextPaint)
        }

        private fun drawFrame() {
            // Drawing/bitmap decode can be expensive; keep it off the main thread so reaction
            // playback can sustain a smooth frame cadence.
            engineScope.launch(Dispatchers.Default) {
                drawMutex.withLock {
                    if (!isEngineVisible || !isSurfaceAvailable || surfaceHolder.surface?.isValid != true) {
                        pendingRedraw = true
                        return@withLock
                    }
                    runCatching {
                        // During reaction animation playback we redraw at ~60fps. Avoid any work
                        // that may touch disk/network or regenerate the snapshot in this hot loop.
                        if (wallpaperSyncEnabled && reactionAnimation == null) {
                            wallpaperCoordinator.ensureSnapshotCurrent()
                        }
                        val renderState = wallpaperRenderStateLoader.loadCurrentState(
                            wallpaperSyncEnabled = wallpaperSyncEnabled
                        )
                        drawToSurface(renderState)
                    }.onFailure { error ->
                        Log.w(TAG, "Wallpaper draw failed", error)
                        pendingRedraw = true
                    }
                }
            }
        }

        private suspend fun drawToSurface(renderState: WallpaperRenderState) {
            val canvas = runCatching { surfaceHolder.lockCanvas() }
                .onFailure { error ->
                    Log.w(TAG, "Unable to lock wallpaper canvas", error)
                }
                .getOrNull()
                ?: return
            try {
                canvas.drawColor(renderState.fallbackColorArgb)

                if (renderState.backgroundImagePath == null) {
                    cachedBackgroundBitmap?.bitmap?.recycle()
                    cachedBackgroundBitmap = null
                }
                if (renderState.snapshotPath == null) {
                    cachedSnapshotBitmap?.bitmap?.recycle()
                    cachedSnapshotBitmap = null
                }

                drawBitmapIfValid(
                    canvas = canvas,
                    filePath = renderState.backgroundImagePath,
                    scaleMode = BitmapScaleMode.CENTER_CROP_TO_CANVAS,
                    cacheSlot = BitmapCacheSlot.BACKGROUND,
                    onCorruptFile = {
                        backgroundImageRepository.clearBackground()
                    }
                )
                drawBitmapIfValid(
                    canvas = canvas,
                    filePath = renderState.snapshotPath,
                    scaleMode = BitmapScaleMode.CENTERED_SCREEN_VIEWPORT,
                    cacheSlot = BitmapCacheSlot.SNAPSHOT,
                    onCorruptFile = {
                        File(it).delete()
                        wallpaperCoordinator.ensureSnapshotCurrent()
                        wallpaperCoordinator.notifyWallpaperUpdatedIfSelected()
                    }
                )
                drawReactionOverlayIfNeeded(canvas)
            } finally {
                try {
                    surfaceHolder.unlockCanvasAndPost(canvas)
                } catch (error: IllegalArgumentException) {
                    Log.w(TAG, "Unable to unlock wallpaper canvas", error)
                } catch (error: Throwable) {
                    Log.w(TAG, "Unexpected error unlocking wallpaper canvas", error)
                }
            }
        }

        private suspend fun drawBitmapIfValid(
            canvas: Canvas,
            filePath: String?,
            scaleMode: BitmapScaleMode,
            cacheSlot: BitmapCacheSlot,
            onCorruptFile: suspend (String) -> Unit
        ) {
            val path = filePath ?: return
            val bitmap = decodeBitmapForCanvas(
                cacheSlot = cacheSlot,
                path = path,
                canvasWidth = canvas.width,
                canvasHeight = canvas.height
            )
            if (bitmap == null) {
                onCorruptFile(path)
                return
            }

            val (viewportWidthPx, viewportHeightPx) = resolveViewportSizePx(
                surfaceWidthPx = canvas.width,
                surfaceHeightPx = canvas.height
            )
            val sourceRect = when (scaleMode) {
                BitmapScaleMode.CENTER_CROP_TO_CANVAS -> centerCropSourceRect(
                    bitmapWidth = bitmap.width,
                    bitmapHeight = bitmap.height,
                    targetWidth = canvas.width,
                    targetHeight = canvas.height
                ).toAndroidRect()
                BitmapScaleMode.CENTERED_SCREEN_VIEWPORT -> centeredScreenSourceRect(
                    bitmapWidth = bitmap.width,
                    bitmapHeight = bitmap.height,
                    screenWidth = viewportWidthPx,
                    screenHeight = viewportHeightPx
                ).toAndroidRect()
            }
            val destinationRect = when (scaleMode) {
                BitmapScaleMode.CENTER_CROP_TO_CANVAS -> Rect(0, 0, canvas.width, canvas.height)
                BitmapScaleMode.CENTERED_SCREEN_VIEWPORT -> centeredScreenSourceRect(
                    bitmapWidth = canvas.width,
                    bitmapHeight = canvas.height,
                    screenWidth = viewportWidthPx,
                    screenHeight = viewportHeightPx
                ).toAndroidRect()
            }
            canvas.drawBitmap(
                bitmap,
                sourceRect,
                destinationRect,
                null
            )
        }

        private fun clearBitmapCaches() {
            cachedBackgroundBitmap?.bitmap?.recycle()
            cachedBackgroundBitmap = null
            cachedSnapshotBitmap?.bitmap?.recycle()
            cachedSnapshotBitmap = null
        }

        private fun resolveViewportSizePx(
            surfaceWidthPx: Int,
            surfaceHeightPx: Int
        ): Pair<Int, Int> {
            val safeSurfaceWidth = surfaceWidthPx.takeIf { it > 0 } ?: lastKnownSurfaceWidthPx
            val safeSurfaceHeight = surfaceHeightPx.takeIf { it > 0 } ?: lastKnownSurfaceHeightPx

            // `lockCanvas()` can occasionally give transient/incorrect sizes immediately after
            // returning from an immersive fullscreen app. The only size we can trust for the draw
            // we are about to perform is the canvas/surface size passed into this method.
            // Treat the surface size as the viewport to avoid accidental cropping.
            if (safeSurfaceWidth > 0 && safeSurfaceHeight > 0) {
                lastKnownViewportWidthPx = safeSurfaceWidth
                lastKnownViewportHeightPx = safeSurfaceHeight
                return Pair(safeSurfaceWidth, safeSurfaceHeight)
            }

            // `resources.displayMetrics` can reflect the *available* size (and may change based on
            // system bar visibility). For a wallpaper we want stable physical metrics, so prefer
            // real display metrics when available.
            val (realWidth, realHeight) = resolveRealDisplaySizePx()
            val available = resources.displayMetrics
            val requestedWidth = realWidth.takeIf { it > 0 } ?: available.widthPixels
            val requestedHeight = realHeight.takeIf { it > 0 } ?: available.heightPixels

            val resolvedWidth = when {
                safeSurfaceWidth > 0 && requestedWidth > 0 -> requestedWidth.coerceAtMost(safeSurfaceWidth)
                requestedWidth > 0 -> requestedWidth
                else -> safeSurfaceWidth.coerceAtLeast(1)
            }
            val resolvedHeight = when {
                safeSurfaceHeight > 0 && requestedHeight > 0 -> requestedHeight.coerceAtMost(safeSurfaceHeight)
                requestedHeight > 0 -> requestedHeight
                else -> safeSurfaceHeight.coerceAtLeast(1)
            }

            if (resolvedWidth > 0) lastKnownViewportWidthPx = resolvedWidth
            if (resolvedHeight > 0) lastKnownViewportHeightPx = resolvedHeight

            return Pair(
                lastKnownViewportWidthPx.coerceAtLeast(1),
                lastKnownViewportHeightPx.coerceAtLeast(1)
            )
        }

        private fun resolveRealDisplaySizePx(): Pair<Int, Int> {
            val windowManager = getSystemService(WindowManager::class.java)
            @Suppress("DEPRECATION")
            val display = windowManager?.defaultDisplay
            if (display != null) {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                return Pair(metrics.widthPixels, metrics.heightPixels)
            }
            return Pair(0, 0)
        }

        private fun decodeBitmapForCanvas(
            cacheSlot: BitmapCacheSlot,
            path: String,
            canvasWidth: Int,
            canvasHeight: Int
        ): Bitmap? {
            val targetWidth = canvasWidth.coerceAtLeast(resources.displayMetrics.widthPixels)
            val targetHeight = canvasHeight.coerceAtLeast(resources.displayMetrics.heightPixels)

            val file = File(path)
            if (!file.exists()) return null
            val lastModified = file.lastModified()
            val currentCache = when (cacheSlot) {
                BitmapCacheSlot.BACKGROUND -> cachedBackgroundBitmap
                BitmapCacheSlot.SNAPSHOT -> cachedSnapshotBitmap
            }

            if (currentCache != null &&
                currentCache.path == path &&
                currentCache.lastModifiedMs == lastModified &&
                currentCache.targetWidth == targetWidth &&
                currentCache.targetHeight == targetHeight &&
                !currentCache.bitmap.isRecycled
            ) {
                return currentCache.bitmap
            }

            val decoded = decodeSampledBitmapFromFile(
                path = path,
                targetWidth = targetWidth,
                targetHeight = targetHeight
            ) ?: return null

            currentCache?.bitmap?.takeIf { !it.isRecycled }?.recycle()
            val updated = CachedBitmap(
                path = path,
                lastModifiedMs = lastModified,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                bitmap = decoded
            )
            when (cacheSlot) {
                BitmapCacheSlot.BACKGROUND -> cachedBackgroundBitmap = updated
                BitmapCacheSlot.SNAPSHOT -> cachedSnapshotBitmap = updated
            }
            return decoded
        }
    }
}

private enum class BitmapCacheSlot {
    BACKGROUND,
    SNAPSHOT
}

private enum class BitmapScaleMode {
    CENTER_CROP_TO_CANVAS,
    CENTERED_SCREEN_VIEWPORT
}

private const val TAG = "MulberryWallpaper"

private data class Anchor(val x: Float, val y: Float)

private data class ReactionGlyph(val type: ReactionType, val x: Float, val y: Float, val scale: Float)

private data class CachedBitmap(
    val path: String,
    val lastModifiedMs: Long,
    val targetWidth: Int,
    val targetHeight: Int,
    val bitmap: Bitmap
)

private data class ReactionAnimationState(
    val generation: Long,
    val durationMs: Long,
    val glyphs: List<ReactionGlyph>,
    val spamText: String?,
    var firstRenderedAtMs: Long? = null,
    var hasRendered: Boolean = false
)

internal fun reactionAnimationElapsedMs(firstRenderedAtMs: Long?, nowMs: Long): Long =
    firstRenderedAtMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L

internal fun reactionAnimationShouldEnd(
    firstRenderedAtMs: Long?,
    nowMs: Long,
    durationMs: Long
): Boolean = firstRenderedAtMs != null && reactionAnimationElapsedMs(firstRenderedAtMs, nowMs) >= durationMs

internal fun reactionPlaybackSurfaceEligible(isLocked: Boolean, isPreview: Boolean): Boolean =
    !isLocked && !isPreview

private fun lerp(start: Float, end: Float, t: Float): Float = start + (end - start) * t

private fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val p = t - 1f
    return 1f + c3 * p * p * p + c1 * p * p
}

internal fun centerCropSourceRect(
    bitmapWidth: Int,
    bitmapHeight: Int,
    targetWidth: Int,
    targetHeight: Int
): ScreenSourceRect {
    val safeBitmapWidth = bitmapWidth.coerceAtLeast(1)
    val safeBitmapHeight = bitmapHeight.coerceAtLeast(1)
    val safeTargetWidth = targetWidth.coerceAtLeast(1)
    val safeTargetHeight = targetHeight.coerceAtLeast(1)

    val bitmapAspectRatio = safeBitmapWidth.toFloat() / safeBitmapHeight.toFloat()
    val targetAspectRatio = safeTargetWidth.toFloat() / safeTargetHeight.toFloat()

    return if (bitmapAspectRatio > targetAspectRatio) {
        val cropWidth = (safeBitmapHeight * targetAspectRatio).toInt().coerceAtLeast(1)
        val left = ((safeBitmapWidth - cropWidth) / 2f).toInt()
        ScreenSourceRect(left, 0, left + cropWidth, safeBitmapHeight)
    } else {
        val cropHeight = (safeBitmapWidth / targetAspectRatio).toInt().coerceAtLeast(1)
        val top = ((safeBitmapHeight - cropHeight) / 2f).toInt()
        ScreenSourceRect(0, top, safeBitmapWidth, top + cropHeight)
    }
}

internal fun centeredScreenSourceRect(
    bitmapWidth: Int,
    bitmapHeight: Int,
    screenWidth: Int,
    screenHeight: Int
): ScreenSourceRect {
    val safeBitmapWidth = bitmapWidth.coerceAtLeast(1)
    val safeBitmapHeight = bitmapHeight.coerceAtLeast(1)
    val viewportWidth = screenWidth
        .takeIf { it > 0 }
        ?.coerceAtMost(safeBitmapWidth)
        ?: safeBitmapWidth
    val viewportHeight = screenHeight
        .takeIf { it > 0 }
        ?.coerceAtMost(safeBitmapHeight)
        ?: safeBitmapHeight

    val left = ((safeBitmapWidth - viewportWidth) / 2f).toInt()
    val top = ((safeBitmapHeight - viewportHeight) / 2f).toInt()

    return ScreenSourceRect(
        left,
        top,
        left + viewportWidth,
        top + viewportHeight
    )
}

internal data class ScreenSourceRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

private fun ScreenSourceRect.toAndroidRect(): Rect = Rect(left, top, right, bottom)
