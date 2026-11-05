package com.subhajit.mulberry.drawing.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasPaintOrderTest {

    @Test
    fun `strokes go over text and text over stickers`() {
        val order = canvasPaintOrder(
            elements = listOf(text("t1"), sticker("s1"), text("t2"), sticker("s2")),
            committedStrokes = listOf(stroke("k1"), stroke("k2"))
        )

        assertEquals(listOf("s1", "s2", "t1", "t2", "k1", "k2"), order.map(::idOf))
        assertEquals(
            listOf(
                CanvasPaintLayer.MEDIA,
                CanvasPaintLayer.MEDIA,
                CanvasPaintLayer.TEXT,
                CanvasPaintLayer.TEXT,
                CanvasPaintLayer.STROKES,
                CanvasPaintLayer.STROKES
            ),
            order.map { it.layer }
        )
    }

    @Test
    fun `within each layer the element changed last is on top`() {
        // The list keeps the order elements were last added or changed in: s1 was moved last.
        val elements = listOf(sticker("s2"), text("t2"), text("t1"), sticker("s1"))

        assertEquals(listOf("s2", "s1", "t2", "t1"), elements.inPaintOrder().map { it.id })
    }

    @Test
    fun `strokes still being drawn go over the finished ones, this device's own last`() {
        val order = canvasPaintOrder(
            elements = listOf(text("t1")),
            committedStrokes = listOf(stroke("done")),
            remoteActiveStrokes = listOf(stroke("partner-live")),
            localActiveStroke = stroke("my-live")
        )

        assertEquals(listOf("t1", "done", "partner-live", "my-live"), order.map(::idOf))
    }

    @Test
    fun `a finger finds text before stickers when both are under it`() {
        val elements = listOf(text("t1"), sticker("s1"), sticker("s2"))

        assertEquals("t1", elements.topmostElement { true }?.id)
        assertEquals(listOf("t1", "s2", "s1"), elements.inHitTestOrder().map { it.id })
    }

    @Test
    fun `a finger finds the element changed last within a layer`() {
        val elements = listOf(sticker("s1"), sticker("s2"), text("t1"), text("t2"))
        val underFinger = setOf("s1", "s2")

        assertEquals("s2", elements.topmostElement { it.id in underFinger }?.id)
        assertEquals("t2", elements.topmostElement { true }?.id)
    }

    @Test
    fun `each element tool picks up only its own kind`() {
        val elements = listOf(sticker("s1"), text("t1"))

        val stickersOnly = elements.filterIsInstance<CanvasStickerElement>()
        assertEquals("s1", stickersOnly.topmostElement { true }?.id)
        val textOnly = elements.filterIsInstance<CanvasTextElement>()
        assertEquals("t1", textOnly.topmostElement { true }?.id)
        assertNull(textOnly.topmostElement { it.id == "s1" })
    }

    @Test
    fun `photos and stickers share one stack under the text`() {
        val elements = listOf(photo("p1"), text("t1"), sticker("s1"), photo("p2"))

        assertEquals(listOf("p1", "s1", "p2", "t1"), elements.inPaintOrder().map { it.id })
        assertEquals("t1", elements.topmostElement { true }?.id)
        assertEquals("p2", elements.topmostElement { it !is CanvasTextElement }?.id)
    }

    private fun idOf(item: CanvasPaintItem): String = when (item) {
        is CanvasPaintItem.Element -> item.element.id
        is CanvasPaintItem.StrokeItem -> item.stroke.id
    }

    private fun text(id: String) = CanvasTextElement(
        id = id,
        text = id,
        createdAt = 0L,
        center = StrokePoint(x = 0.5f, y = 0.5f),
        boxWidth = 0.7f,
        colorArgb = 0xFF000000
    )

    private fun sticker(id: String) = CanvasStickerElement(
        id = id,
        createdAt = 0L,
        center = StrokePoint(x = 0.5f, y = 0.5f),
        packKey = "pack",
        packVersion = 1,
        stickerId = id
    )

    private fun photo(id: String) = CanvasPhotoElement(
        id = id,
        createdAt = 0L,
        center = StrokePoint(x = 0.5f, y = 0.5f),
        photoId = "upload-$id",
        aspectRatio = 1f
    )

    private fun stroke(id: String) = Stroke(
        id = id,
        colorArgb = 0xFF000000,
        width = 0.01f,
        points = listOf(StrokePoint(x = 0.5f, y = 0.5f)),
        createdAt = 0L
    )
}
