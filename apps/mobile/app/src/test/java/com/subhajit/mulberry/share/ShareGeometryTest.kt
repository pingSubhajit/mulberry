package com.subhajit.mulberry.share

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareGeometryTest {
    private fun layout(key: String) = ShareFrameLayoutParser.parse(
        File("src/main/assets/share_frames/$key/layout.json").readText()
    )!!

    private fun assertPoint(expected: SharePoint, actual: SharePoint, tolerance: Double = 1e-6) {
        assertEquals("x", expected.x, actual.x, tolerance)
        assertEquals("y", expected.y, actual.y, tolerance)
    }

    @Test
    fun `maps the content corners onto the quad`() {
        for (key in listOf("plain", "polaroid")) {
            val quad = layout(key).content.quad
            val affine = ShareGeometry.contentToQuad(quad, SHARE_CONTENT_WIDTH.toDouble(), SHARE_CONTENT_HEIGHT.toDouble())
            assertPoint(quad.topLeft, affine.map(0.0, 0.0))
            assertPoint(quad.topRight, affine.map(1179.0, 0.0))
            assertPoint(quad.bottomLeft, affine.map(0.0, 2556.0))
            // The quad is a parallelogram, so the fourth corner follows to within its rounding.
            assertPoint(quad.bottomRight, affine.map(1179.0, 2556.0), tolerance = 1e-3)
        }
    }

    @Test
    fun `matches the reference renderer's inverse affine coefficients`() {
        // render_preview.py's map_content: the output-to-content map Pillow's AFFINE transform takes.
        val quad = layout("plain").content.quad
        val a = quad.topLeft
        val ux = quad.topRight.x - a.x
        val uy = quad.topRight.y - a.y
        val vx = quad.bottomLeft.x - a.x
        val vy = quad.bottomLeft.y - a.y
        val det = ux * vy - uy * vx
        val w = 1179.0
        val h = 2556.0
        val inverse = doubleArrayOf(
            vy * w / det, -vx * w / det, (vx * a.y - vy * a.x) * w / det,
            -uy * h / det, ux * h / det, (uy * a.x - ux * a.y) * h / det
        )
        val forward = ShareGeometry.contentToQuad(quad, w, h)
        // Output pixel → content (reference) → output (ours) is the identity.
        for ((x, y) in listOf(400.0 to 500.0, 700.0 to 1400.0, 300.0 to 1500.0)) {
            val u = inverse[0] * x + inverse[1] * y + inverse[2]
            val v = inverse[3] * x + inverse[4] * y + inverse[5]
            assertPoint(SharePoint(x, y), forward.map(u, v), tolerance = 1e-6)
        }
    }

    @Test
    fun `gives setPolyToPoly the three corners`() {
        val quad = layout("polaroid").content.quad
        val (source, destination) = ShareGeometry.polyToPolyPoints(quad, 590f, 1278f)
        assertArrayEquals(floatArrayOf(0f, 0f, 590f, 0f, 0f, 1278f), source, 0f)
        assertArrayEquals(
            floatArrayOf(176.20314f, 288.79452f, 782.02422f, 235.79204f, 291.10928f, 1602.17761f),
            destination,
            1e-3f
        )
    }

    @Test
    fun `places the caption block as the reference renderer does`() {
        // temp/share-frames/v1/plain/source/render-bounds.json: the sample caption's 582×199 block,
        // anchored bottom right and turned 7° anticlockwise.
        val caption = layout("plain").caption
        val placement = ShareGeometry.blockPlacement(caption.position, caption.anchor, caption.rotationDegrees, 582.0, 199.0)
        val corners = ShareGeometry.blockCorners(placement, 582.0, 199.0)
        val expected = listOf(
            SharePoint(438.08614040712627, 1376.4832736851727),
            SharePoint(1015.7480006623756, 1305.5553158233768),
            SharePoint(1040.0, 1503.072),
            SharePoint(462.3381397447506, 1573.9999578617958)
        )
        expected.zip(corners).forEach { (want, got) -> assertPoint(want, got) }
    }

    @Test
    fun `places the brand block by its centre`() {
        val brand = layout("plain").brand
        val placement = ShareGeometry.blockPlacement(brand.position, brand.anchor, brand.rotationDegrees, 217.0, 53.0)
        val corners = ShareGeometry.blockCorners(placement, 217.0, 53.0)
        listOf(
            SharePoint(54.5, 1509.0),
            SharePoint(271.5, 1509.0),
            SharePoint(271.5, 1562.0),
            SharePoint(54.5, 1562.0)
        ).zip(corners).forEach { (want, got) -> assertPoint(want, got) }
    }

    @Test
    fun `positive rotation turns clockwise about the anchor`() {
        val placement = ShareGeometry.blockPlacement(SharePoint(100.0, 100.0), ShareBlockAnchor.TOP_LEFT, 90.0, 10.0, 4.0)
        // With y down, a clockwise quarter turn sends the block's right edge downward.
        assertPoint(SharePoint(100.0, 100.0), placement.map(0.0, 0.0))
        assertPoint(SharePoint(100.0, 110.0), placement.map(10.0, 0.0))
        assertPoint(SharePoint(96.0, 100.0), placement.map(0.0, 4.0))
        assertArrayEquals(
            floatArrayOf(0f, -1f, 100f, 1f, 0f, 100f, 0f, 0f, 1f),
            placement.toMatrixValues(),
            1e-6f
        )
    }

    @Test
    fun `centre-crops the lock screen to the content's shape`() {
        // A 1080×2400 screen is taller than 1179:2556, so the top and bottom are cropped.
        val crop = ShareGeometry.centerCropSource(1080, 2400, SHARE_CONTENT_WIDTH, SHARE_CONTENT_HEIGHT)
        assertEquals(0.0, crop.left, 1e-9)
        assertEquals(1080.0, crop.right, 1e-9)
        val height = 1080.0 * 2556.0 / 1179.0
        assertEquals((2400.0 - height) / 2.0, crop.top, 1e-9)
        assertEquals((2400.0 + height) / 2.0, crop.bottom, 1e-9)
    }
}
