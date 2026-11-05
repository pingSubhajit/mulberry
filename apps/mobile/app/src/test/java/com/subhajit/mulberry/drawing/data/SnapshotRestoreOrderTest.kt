package com.subhajit.mulberry.drawing.data

import com.subhajit.mulberry.drawing.data.local.toEntity
import com.subhajit.mulberry.drawing.model.CanvasElement
import com.subhajit.mulberry.drawing.model.CanvasPhotoElement
import com.subhajit.mulberry.drawing.model.CanvasStickerElement
import com.subhajit.mulberry.drawing.model.CanvasTextElement
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.drawing.model.inPaintOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotRestoreOrderTest {

    @Test
    fun `a restored snapshot keeps the server's order within each layer`() {
        // The server's order, bottom to top, mixing both kinds.
        val serverElements: List<CanvasElement> = listOf(
            sticker("s1"),
            text("t1"),
            sticker("s2"),
            text("t2"),
            sticker("s3")
        )

        val restored = readBack(serverElements.withSnapshotZIndices(serverRevision = 42L))

        // Stored and read back, the elements keep the server's order across both kinds...
        assertEquals(serverElements.map { it.id }, restored.map { it.id })
        // ...so each layer keeps the server's order within it.
        assertEquals(listOf("s1", "s2", "s3", "t1", "t2"), restored.inPaintOrder().map { it.id })
    }

    @Test
    fun `photos keep their place among the stickers after a restore`() {
        val serverElements: List<CanvasElement> = listOf(sticker("s1"), photo("p1"), text("t1"), sticker("s2"), photo("p2"))

        val restored = readBack(serverElements.withSnapshotZIndices(serverRevision = 9L))

        assertEquals(serverElements.map { it.id }, restored.map { it.id })
        assertEquals(listOf("s1", "p1", "s2", "p2", "t1"), restored.inPaintOrder().map { it.id })
    }

    @Test
    fun `an element changed after the restore goes on top of its layer`() {
        val serverRevision = 42L
        val restored = serverElements().withSnapshotZIndices(serverRevision)
        // As upsertLocalStickerElement stores it: the next revision, scaled.
        val moved = sticker("s1") to (serverRevision + 1) * ELEMENT_Z_INDEX_SCALE

        val elements = readBack(restored.filterNot { it.first.id == "s1" } + moved)

        assertEquals(listOf("s2", "s1", "t1"), elements.inPaintOrder().map { it.id })
    }

    /** Stores each element as its own table would, and reads both back as the repository does. */
    private fun readBack(rows: List<Pair<CanvasElement, Long>>): List<CanvasElement> {
        val textRows = rows.mapNotNull { (element, zIndex) ->
            (element as? CanvasTextElement)?.toEntity(zIndex = zIndex)
        }.sortedBy { it.zIndex }
        val stickerRows = rows.mapNotNull { (element, zIndex) ->
            (element as? CanvasStickerElement)?.toEntity(zIndex = zIndex)
        }.sortedBy { it.zIndex }
        val photoRows = rows.mapNotNull { (element, zIndex) ->
            (element as? CanvasPhotoElement)?.toEntity(zIndex = zIndex)
        }.sortedBy { it.zIndex }
        return mergeElementsByZIndex(textRows, stickerRows, photoRows)
    }

    private fun serverElements(): List<CanvasElement> = listOf(sticker("s1"), text("t1"), sticker("s2"))

    private fun text(id: String) = CanvasTextElement(
        id = id,
        text = id,
        createdAt = 0L,
        center = StrokePoint(x = 0.5f, y = 0.5f),
        boxWidth = 0.7f,
        colorArgb = 0xFF000000
    )

    private fun photo(id: String) = CanvasPhotoElement(
        id = id,
        createdAt = 0L,
        center = StrokePoint(x = 0.5f, y = 0.5f),
        photoId = "upload-$id",
        aspectRatio = 1f
    )

    private fun sticker(id: String) = CanvasStickerElement(
        id = id,
        createdAt = 0L,
        center = StrokePoint(x = 0.5f, y = 0.5f),
        packKey = "pack",
        packVersion = 1,
        stickerId = id
    )
}
