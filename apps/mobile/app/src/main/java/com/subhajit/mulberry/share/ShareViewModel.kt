package com.subhajit.mulberry.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.text.format.DateFormat
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subhajit.mulberry.R
import com.subhajit.mulberry.analytics.AnalyticsEvent
import com.subhajit.mulberry.analytics.AppAnalytics
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A frame in the row of thumbnails. [thumbnail] is a URL Coil can load. */
data class ShareFrameOption(
    val key: String,
    val title: String,
    val thumbnail: String
)

data class ShareUiState(
    val frames: List<ShareFrameOption> = emptyList(),
    val selectedKey: String? = null,
    val caption: String = "",
    /** The caption the screen starts with, shown faintly when the field is emptied. */
    val defaultCaption: String = "",
    val useRealWallpaper: Boolean = true,
    /** False when this device's lock screen can't be drawn; Mulberry's stands in. */
    val realWallpaperAvailable: Boolean = true,
    val preview: Bitmap? = null,
    val isSharing: Boolean = false
)

sealed interface ShareEffect {
    data class OpenShareSheet(val imageUri: Uri) : ShareEffect
}

@HiltViewModel
class ShareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val frameRepository: ShareFrameRepository,
    private val wallpaperSource: ShareWallpaperSource,
    private val renderer: ShareImageRenderer,
    private val renderCache: ShareRenderCache,
    private val sessionBootstrapRepository: SessionBootstrapRepository,
    private val analytics: AppAnalytics
) : ViewModel() {
    private val _uiState = MutableStateFlow(ShareUiState())
    val uiState: StateFlow<ShareUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<ShareEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<ShareEffect> = _effects.asSharedFlow()

    // Read by the render coroutine; replaced whole, never mutated.
    @Volatile private var frames: List<ShareFrame> = emptyList()
    @Volatile private var bundledFrames: List<ShareFrame> = emptyList()
    private var defaultCaption: String = ""
    @Volatile private var realWallpaperAvailable = true
    private var sessionActive = false
    /** The frame the sheet opens on, whose preview is kept for the next opening. */
    @Volatile private var openingKey: String? = null
    private var loadJob: Job? = null

    // The renderer and these caches belong to whoever holds the mutex.
    private val renderMutex = Mutex()
    private val wallpaperLayers = mutableMapOf<Boolean, Bitmap>()
    private var cachedContent: CachedContent? = null

    private val renderRequests = MutableStateFlow<RenderRequest?>(null)

    init {
        viewModelScope.launch(Dispatchers.Default) {
            renderRequests.filterNotNull().collectLatest { request ->
                // Typing settles before the preview redraws.
                if (request.debounce) delay(CAPTION_DEBOUNCE_MS)
                val preview = try {
                    renderMutex.withLock { renderImage(request) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Share preview failed", error)
                    null
                }
                if (preview != null) {
                    _uiState.update { it.copy(preview = preview) }
                    savedPreviewKey(request)?.let { key -> renderCache.savePreview(preview, key) }
                }
            }
        }
    }

    /** The sheet appeared. Survives configuration changes without starting over. */
    fun start() {
        if (sessionActive) return
        sessionActive = true
        analytics.track(AnalyticsEvent.ShareOpened)
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val partnerName = sessionBootstrapRepository.state.first().partnerDisplayName
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: context.getString(R.string.share_partner_name_fallback)
            defaultCaption = limitCaptionLength(context.getString(R.string.share_caption_default, partnerName))
            realWallpaperAvailable = true
            _uiState.value = ShareUiState(caption = defaultCaption, defaultCaption = defaultCaption)
            frames = emptyList()

            bundledFrames = frameRepository.bundledFrames()
            // Opens on the catalog as it last answered, so the frames don't swap a moment later,
            // and on the preview drawn last time, which the one being drawn replaces.
            val cached = frameRepository.cachedCatalogFrames()
            if (cached != null) {
                applyFrames(cached)
                restoreSavedPreview()
            }
            val fresh = async { frameRepository.catalogFrames() }
            if (frames.isEmpty()) {
                // The first time, the catalog gets a moment so the preview starts on the right
                // frame; when it's slow or unreachable, the bundled frames stand in.
                applyFrames(chooseShareFrames(withTimeoutOrNull(FIRST_CATALOG_WAIT_MS) { fresh.await() }, bundledFrames))
                restoreSavedPreview()
            }
            fresh.await()?.let { catalog -> applyFrames(chooseShareFrames(catalog, bundledFrames)) }
        }
    }

    /** The sheet closed: free the large bitmaps and start fresh next time. */
    fun stop() {
        sessionActive = false
        loadJob?.cancel()
        renderRequests.value = null
        viewModelScope.launch(Dispatchers.Default) {
            renderMutex.withLock {
                wallpaperLayers.values.forEach(Bitmap::recycle)
                wallpaperLayers.clear()
                cachedContent?.content?.recycle()
                cachedContent = null
            }
        }
        _uiState.value = ShareUiState()
    }

    fun onFrameSelected(key: String) {
        if (frames.none { it.key == key }) return
        analytics.track(AnalyticsEvent.ShareFrameSelected(frameKey = key))
        if (_uiState.value.selectedKey == key) return
        _uiState.update { it.copy(selectedKey = key) }
        requestRender(debounce = false)
    }

    fun onCaptionChanged(caption: String) {
        val limited = limitCaptionLength(caption)
        if (limited == _uiState.value.caption) return
        _uiState.update { it.copy(caption = limited) }
        requestRender(debounce = true)
    }

    fun onUseRealWallpaperChanged(useRealWallpaper: Boolean) {
        val next = useRealWallpaper && realWallpaperAvailable
        if (next == _uiState.value.useRealWallpaper) return
        _uiState.update { it.copy(useRealWallpaper = next) }
        requestRender(debounce = false)
    }

    fun onShareClicked() {
        val state = _uiState.value
        val frameKey = state.selectedKey ?: return
        if (state.isSharing) return
        _uiState.update { it.copy(isSharing = true) }
        viewModelScope.launch {
            try {
                val request = RenderRequest(frameKey, state.caption, state.useRealWallpaper, debounce = false)
                // Drawn again so the clock shows the time the image is made.
                val image = withContext(Dispatchers.Default) { renderMutex.withLock { renderImage(request) } }
                    ?: return@launch
                val uri = withContext(Dispatchers.IO) { writeShareImage(image) }
                _effects.emit(ShareEffect.OpenShareSheet(uri))
                analytics.track(
                    AnalyticsEvent.ShareCompleted(
                        frameKey = frameKey,
                        // The real lock screen may have turned out not to be drawable.
                        usedRealWallpaper = state.useRealWallpaper && realWallpaperAvailable,
                        captionEdited = state.caption != defaultCaption
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Share image failed", error)
            } finally {
                _uiState.update { it.copy(isSharing = false) }
            }
        }
    }

    /**
     * Shows a set of frames. The frame on screen stays while the set still has it, so a catalog
     * that answers late adds frames rather than swapping the preview out.
     */
    private fun applyFrames(available: List<ShareFrame>) {
        val arrangement = ShareFrameSelector.arrange(available, CalendarDate.today())
        if (arrangement.frames.isEmpty() || arrangement.frames == frames) return
        frames = arrangement.frames
        openingKey = arrangement.preselectedKey
        val current = _uiState.value.selectedKey
        val selected = current?.takeIf { key -> frames.any { it.key == key } }
            ?: arrangement.preselectedKey
        _uiState.update { state ->
            state.copy(
                frames = frames.map { it.toOption() },
                selectedKey = selected
            )
        }
        requestRender(debounce = false)
    }

    /** Shows the preview drawn last time while the sheet is as it opens and nothing is drawn yet. */
    private suspend fun restoreSavedPreview() {
        val state = _uiState.value
        if (state.preview != null) return
        val key = state.selectedKey ?: return
        val savedKey = savedPreviewKey(RenderRequest(key, state.caption, state.useRealWallpaper, debounce = false))
            ?: return
        val saved = renderCache.preview(savedKey) ?: return
        _uiState.update { current ->
            if (current.preview == null && current.selectedKey == key) current.copy(preview = saved) else current
        }
    }

    /**
     * The key the preview for [request] is kept under, or null unless the sheet is as it opens:
     * the opening frame, the starting caption and the switch on.
     */
    private fun savedPreviewKey(request: RenderRequest): String? {
        if (request.frameKey != openingKey || request.caption != defaultCaption) return null
        if (!request.useRealWallpaper && realWallpaperAvailable) return null
        val frame = frames.firstOrNull { it.key == request.frameKey } ?: return null
        return "${frame.key}/${frame.version}\n${request.caption}"
    }

    private fun requestRender(debounce: Boolean) {
        val state = _uiState.value
        val key = state.selectedKey ?: return
        renderRequests.value = RenderRequest(key, state.caption, state.useRealWallpaper, debounce)
    }

    /** The finished 1080×1920 image, or null when the frame's image can't be had. */
    private suspend fun renderImage(request: RenderRequest): Bitmap? {
        val frame = frames.firstOrNull { it.key == request.frameKey } ?: return null
        val (frameBitmap, layout) = frameRepository.loadFrameBitmap(frame)?.let { it to frame.layout }
            ?: bundledFrames.firstOrNull { it.key == frame.key }
                ?.let { bundled -> frameRepository.loadFrameBitmap(bundled)?.let { it to bundled.layout } }
            ?: return null
        val content = contentFor(request.useRealWallpaper)
        val prepared = renderer.prepareContentForQuad(content, layout.content.quad)
        return try {
            renderer.render(prepared, frameBitmap, layout, request.caption)
        } finally {
            if (prepared !== content) prepared.recycle()
        }
    }

    /** The wallpaper layer with the clock drawn over it, redrawn when the minute turns. */
    private suspend fun contentFor(useRealWallpaper: Boolean): Bitmap {
        val clock = currentClockText()
        val (real, layer) = wallpaperLayer(useRealWallpaper)
        cachedContent?.takeIf { it.useRealWallpaper == real && it.clock == clock }
            ?.let { return it.content }
        val content = layer.copy(Bitmap.Config.ARGB_8888, true)
        renderer.drawClock(Canvas(content), clock)
        cachedContent?.content?.recycle()
        cachedContent = CachedContent(real, clock, content)
        return content
    }

    /**
     * The wallpaper layer, drawn once per choice, and whether it is the real one: when this
     * device's lock screen can't be drawn, Mulberry's stands in and the real choice turns off.
     */
    private suspend fun wallpaperLayer(useRealWallpaper: Boolean): Pair<Boolean, Bitmap> {
        val real = useRealWallpaper && realWallpaperAvailable
        wallpaperLayers[real]?.let { return real to it }
        if (!real) {
            return false to wallpaperSource.renderWallpaperLayer(false).also { wallpaperLayers[false] = it }
        }
        return try {
            true to wallpaperSource.renderWallpaperLayer(true).also { wallpaperLayers[true] = it }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Unable to draw the lock screen", error)
            realWallpaperAvailable = false
            _uiState.update { it.copy(realWallpaperAvailable = false, useRealWallpaper = false) }
            wallpaperLayer(false)
        }
    }

    private fun currentClockText(): ShareClockText {
        val now = Date()
        val locale = Locale.getDefault()
        val timePattern = if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
        return ShareClockText(
            date = SimpleDateFormat(DATE_PATTERN, locale).format(now),
            time = SimpleDateFormat(timePattern, locale).format(now)
        )
    }

    private fun writeShareImage(image: Bitmap): Uri {
        val directory = File(context.cacheDir, SHARE_IMAGE_DIR).apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val file = File(directory, "mulberry-${System.currentTimeMillis()}.png")
        val wrote = file.outputStream().use { output -> image.compress(Bitmap.CompressFormat.PNG, 100, output) }
        check(wrote) { "Unable to write the share image" }
        return FileProvider.getUriForFile(context, "${context.packageName}$FILE_PROVIDER_SUFFIX", file)
    }

    private fun ShareFrame.toOption(): ShareFrameOption = ShareFrameOption(
        key = key,
        title = title,
        thumbnail = when (val source = image) {
            is ShareFrameImageSource.Remote -> source.thumbnailUrl ?: source.frameUrl
            is ShareFrameImageSource.Bundled -> "file:///android_asset/${source.assetPath}"
        }
    )

    private data class RenderRequest(
        val frameKey: String,
        val caption: String,
        val useRealWallpaper: Boolean,
        val debounce: Boolean
    )

    private data class CachedContent(
        val useRealWallpaper: Boolean,
        val clock: ShareClockText,
        val content: Bitmap
    )

    companion object {
        const val FILE_PROVIDER_SUFFIX = ".shareimages"
        private const val TAG = "ShareViewModel"
        private const val SHARE_IMAGE_DIR = "share_images"
        private const val DATE_PATTERN = "EEEE, d MMMM"
        private const val CAPTION_DEBOUNCE_MS = 150L
        /** How long a first opening waits for the catalog before the bundled frames stand in. */
        private const val FIRST_CATALOG_WAIT_MS = 1_500L
    }
}
