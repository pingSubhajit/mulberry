package com.subhajit.mulberry.stickers

import com.subhajit.mulberry.canvas.CanvasRuntime
import com.subhajit.mulberry.drawing.model.CanvasStickerElement
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Decodes the canvas's sticker artwork as soon as the saved drawing loads, before the canvas is
 * on screen, so it opens with its stickers already in place.
 */
@Singleton
class StickerArtworkPrewarmer @Inject constructor(
    private val canvasRuntime: CanvasRuntime,
    private val stickerAssetStore: StickerAssetStore
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            canvasRuntime.renderState
                .map { state ->
                    state.committedElements
                        .filterIsInstance<CanvasStickerElement>()
                        .map { StickerArtworkRef(it.packKey, it.packVersion, it.stickerId) }
                        .distinct()
                }
                .distinctUntilChanged()
                .collectLatest { refs ->
                    coroutineScope {
                        refs.forEach { ref ->
                            launch {
                                runCatching {
                                    stickerAssetStore.loadStickerBitmap(
                                        packKey = ref.packKey,
                                        packVersion = ref.packVersion,
                                        stickerId = ref.stickerId,
                                        variant = StickerAssetVariant.FULL
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }

    private data class StickerArtworkRef(val packKey: String, val packVersion: Int, val stickerId: String)
}
