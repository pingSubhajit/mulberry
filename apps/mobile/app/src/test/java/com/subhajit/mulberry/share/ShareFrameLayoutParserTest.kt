package com.subhajit.mulberry.share

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ShareFrameLayoutParserTest {
    private fun bundledLayoutJson(key: String): String =
        File("src/main/assets/share_frames/$key/layout.json").readText()

    private fun bundledLayoutObject(key: String): JsonObject =
        JsonParser.parseString(bundledLayoutJson(key)).asJsonObject

    @Test
    fun `parses the bundled plain layout`() {
        val parsed = ShareFrameLayoutParser.parse(bundledLayoutJson("plain"))
        assertNotNull(parsed)
        val layout = parsed!!
        assertEquals(1, layout.layoutVersion)
        assertEquals("plain", layout.key)
        assertEquals(1080, layout.canvasWidth)
        assertEquals(1920, layout.canvasHeight)
        assertEquals(ShareContentKind.PHONE, layout.content.kind)
        assertEquals(1179.0 / 2556.0, layout.content.aspectRatio, 1e-12)
        assertEquals(SharePoint(300.94236, 329.60267), layout.content.quad.topLeft)
        assertEquals(SharePoint(861.56165, 368.80499), layout.content.quad.topRight)
        assertEquals(SharePoint(776.57342, 1584.19338), layout.content.quad.bottomRight)
        assertEquals(SharePoint(215.95412, 1544.99106), layout.content.quad.bottomLeft)

        val caption = layout.caption
        assertEquals(SharePoint(1040.0, 1503.072), caption.position)
        assertEquals(ShareBlockAnchor.BOTTOM_RIGHT, caption.anchor)
        assertEquals(-7.0, caption.rotationDegrees, 0.0)
        assertEquals(580.0, caption.maxWidth, 0.0)
        assertEquals(3, caption.maxLines)
        assertEquals(64, caption.maxFontSize)
        assertEquals(40, caption.minFontSize)
        assertEquals(1.05, caption.lineHeight, 0.0)
        assertEquals(ShareCaptionAlign.RIGHT, caption.align)
        assertEquals(0xFFB31329.toInt(), caption.inkColorArgb)
        assertEquals(ShareBacking(0xFFFFF4DF.toInt(), 12.0, 7.0), caption.backing)

        val brand = layout.brand
        assertEquals(SharePoint(163.0, 1535.5), brand.position)
        assertEquals(ShareBlockAnchor.CENTER, brand.anchor)
        assertEquals(0.0, brand.rotationDegrees, 0.0)
        assertEquals(37.0, brand.markHeight, 0.0)
        assertEquals(24.0, brand.fontSize, 0.0)
        assertEquals(0xFF8C1729.toInt(), brand.colorArgb)
        assertEquals(ShareBacking(0xFFFFF9EB.toInt(), 13.0, 8.0, 15.0), brand.backing)
    }

    @Test
    fun `parses the bundled polaroid layout`() {
        val layout = ShareFrameLayoutParser.parse(bundledLayoutJson("polaroid"))!!
        assertEquals("polaroid", layout.key)
        assertEquals(ShareContentKind.POLAROID, layout.content.kind)
        assertEquals(ShareBlockAnchor.BOTTOM_LEFT, layout.caption.anchor)
        assertEquals(ShareCaptionAlign.LEFT, layout.caption.align)
        assertEquals(7.0, layout.caption.rotationDegrees, 0.0)
    }

    @Test
    fun `skips layout versions it does not support`() {
        val newer = bundledLayoutObject("plain").apply { addProperty("layoutVersion", 2) }
        assertNull(ShareFrameLayoutParser.parse(newer))
        val older = bundledLayoutObject("plain").apply { addProperty("layoutVersion", 0) }
        assertNull(ShareFrameLayoutParser.parse(older))
        val missing = bundledLayoutObject("plain").apply { remove("layoutVersion") }
        assertNull(ShareFrameLayoutParser.parse(missing))
    }

    @Test
    fun `allows a null backing for the caption and the brand`() {
        val layout = bundledLayoutObject("plain").apply {
            getAsJsonObject("caption").add("backing", com.google.gson.JsonNull.INSTANCE)
            getAsJsonObject("brand").add("backing", com.google.gson.JsonNull.INSTANCE)
        }
        val parsed = ShareFrameLayoutParser.parse(layout)!!
        assertNull(parsed.caption.backing)
        assertNull(parsed.brand.backing)
    }

    @Test
    fun `rejects malformed layouts`() {
        assertNull(ShareFrameLayoutParser.parse("not json"))
        assertNull(ShareFrameLayoutParser.parse("[]"))
        assertNull(ShareFrameLayoutParser.parse(bundledLayoutObject("plain").apply { remove("caption") }))
        assertNull(
            ShareFrameLayoutParser.parse(
                bundledLayoutObject("plain").apply { getAsJsonObject("caption").addProperty("anchor", "middle") }
            )
        )
        assertNull(
            ShareFrameLayoutParser.parse(
                bundledLayoutObject("plain").apply { getAsJsonObject("caption").addProperty("inkColor", "red") }
            )
        )
        assertNull(
            ShareFrameLayoutParser.parse(
                bundledLayoutObject("plain").apply { getAsJsonObject("content").addProperty("kind", "tablet") }
            )
        )
        assertNull(
            ShareFrameLayoutParser.parse(
                bundledLayoutObject("plain").apply { getAsJsonObject("content").getAsJsonObject("quad").remove("bottomLeft") }
            )
        )
    }

    @Test
    fun `reads hex colours`() {
        assertEquals(0xFFB31329.toInt(), parseHexColor("#B31329"))
        assertEquals(0xFFFFF4DF.toInt(), parseHexColor("#fff4df"))
        assertNull(parseHexColor("#FFF"))
        assertNull(parseHexColor("#GG0000"))
    }
}
