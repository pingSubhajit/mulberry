package com.subhajit.mulberry.share

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareCaptionFitterTest {
    private val caption = ShareFrameLayoutParser.parse(
        File("src/main/assets/share_frames/plain/layout.json").readText()
    )!!.caption

    /**
     * Permanent Marker measured by the JVM's own font engine, standing in for Android's Paint.
     * Pillow/FreeType, Android and AWT differ by fractions of a pixel, not by whole words.
     */
    private val permanentMarker: ShareTextMeasurer =
        AwtTextMeasurer(File("src/main/res/font/permanent_marker_regular.ttf"))

    /** Every character is 10px wide at size 10, scaling with the size. */
    private val monospace = ShareTextMeasurer { text, fontSize -> text.length * fontSize.toDouble() }

    @Test
    fun `fits the sample caption at the largest size, as the reference renderer does`() {
        val fit = ShareCaptionFitter.fit("Look what Aarav drew on my lock screen", caption, permanentMarker)
        assertEquals(64, fit.fontSize)
        assertEquals(listOf("Look what Aarav", "drew on my", "lock screen"), fit.lines)
    }

    @Test
    fun `fits the 60-character caption at 48px, as the reference renderer does`() {
        val text = "Look what my favourite person drew on my lock screen today!!"
        assertEquals(60, text.length)
        val fit = ShareCaptionFitter.fit(text, caption, permanentMarker)
        assertEquals(48, fit.fontSize)
        assertEquals(listOf("Look what my favourite", "person drew on my", "lock screen today!!"), fit.lines)
    }

    @Test
    fun `the first line takes as many words as fit, then the rest are balanced`() {
        // At size 10 each character is 10px: "aaaa bbbb" is 90px.
        val lines = ShareCaptionFitter.fitAtSize(
            words = listOf("aaaa", "bbbb", "cc", "dd", "eeeeee"),
            maxWidth = 100.0,
            maxLines = 3,
            size = 10,
            measurer = monospace
        )
        // Greedy would give "cc dd" (50) and "eeeeee" (60); balancing can't beat that split
        // because "cc dd eeeeee" doesn't fit on one line.
        assertEquals(listOf("aaaa bbbb", "cc dd", "eeeeee"), lines)
    }

    @Test
    fun `picks the split with the smallest sum of squared unused widths`() {
        // After the full first line, "aaaaaaa b cc" (120px) needs two lines of at most 100px.
        val lines = ShareCaptionFitter.fitAtSize(
            words = listOf("xxxxxxxxx", "aaaaaaa", "b", "cc"),
            maxWidth = 100.0,
            maxLines = 3,
            size = 10,
            measurer = monospace
        )
        // Greedy would give "aaaaaaa b" (90) and "cc" (20): 10² + 80² = 6500. Balanced gives
        // "aaaaaaa" (70) and "b cc" (40): 30² + 60² = 4500.
        assertEquals(listOf("xxxxxxxxx", "aaaaaaa", "b cc"), lines)
    }

    @Test
    fun `keeps the first split found when scores tie, as the reference does`() {
        val lines = ShareCaptionFitter.fitAtSize(
            words = listOf("xxxxxxxxx", "a", "bb", "ccc", "dddd"),
            maxWidth = 100.0,
            maxLines = 3,
            size = 10,
            measurer = monospace
        )
        // "a bb" | "ccc dddd" and "a bb ccc" | "dddd" both score 60² + 20².
        assertEquals(listOf("xxxxxxxxx", "a bb", "ccc dddd"), lines)
    }

    @Test
    fun `uses the fewest lines before balancing`() {
        val lines = ShareCaptionFitter.fitAtSize(
            words = listOf("aaaa", "bbbb", "cccc"),
            maxWidth = 100.0,
            maxLines = 3,
            size = 10,
            measurer = monospace
        )
        assertEquals(listOf("aaaa bbbb", "cccc"), lines)
    }

    @Test
    fun `returns null when the text needs more lines than allowed or a word is too wide`() {
        assertNull(ShareCaptionFitter.fitAtSize(listOf("aaaaaaaaaaaa"), 100.0, 3, 10, monospace))
        assertNull(ShareCaptionFitter.fitAtSize(List(4) { "aaaaaaaa" }, 100.0, 3, 10, monospace))
    }

    @Test
    fun `steps down a size at a time until the text fits`() {
        // At size s each character is s px. From 12 down to 10 the first line holds one word and
        // the rest need two more lines; at 9 "aaaaa bbbbb" and "ccccc ddddd" are 99px each.
        val fit = ShareCaptionFitter.fit("aaaaa bbbbb ccccc ddddd", 100.0, 2, 12, 5, monospace)
        assertEquals(9, fit.fontSize)
        assertEquals(listOf("aaaaa bbbbb", "ccccc ddddd"), fit.lines)
    }

    @Test
    fun `falls back to greedy wrapping at the minimum size when nothing fits`() {
        val fit = ShareCaptionFitter.fit("aaaaaaaaaaaaaaa bb cc dd ee ff", 100.0, 2, 12, 10, monospace)
        assertEquals(10, fit.fontSize)
        assertEquals(listOf("aaaaaaaaaaaaaaa", "bb cc dd", "ee ff"), fit.lines)
    }

    @Test
    fun `wraps on any whitespace and ignores an empty caption`() {
        assertEquals(listOf("Look", "what"), ShareCaptionFitter.words("  Look \n what  "))
        assertEquals(emptyList<String>(), ShareCaptionFitter.fit("   ", caption, monospace).lines)
    }

    @Test
    fun `limits captions to 60 characters without splitting an emoji`() {
        val sixty = "Look what my favourite person drew on my lock screen today!!"
        assertEquals(sixty, limitCaptionLength(sixty + " and more"))
        assertEquals("short", limitCaptionLength("short"))
        val hearts = "💖".repeat(61)
        val limited = limitCaptionLength(hearts)
        assertEquals(60, limited.codePointCount(0, limited.length))
        assertEquals(120, limited.length)
    }
}
