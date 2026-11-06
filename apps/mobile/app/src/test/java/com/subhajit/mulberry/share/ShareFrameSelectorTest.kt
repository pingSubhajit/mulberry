package com.subhajit.mulberry.share

import com.google.gson.JsonParser
import com.subhajit.mulberry.network.ShareFrameResponse
import java.io.File
import java.util.Calendar
import java.util.GregorianCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareFrameSelectorTest {
    private val layout = ShareFrameLayoutParser.parse(
        File("src/main/assets/share_frames/plain/layout.json").readText()
    )!!

    private fun frame(
        key: String,
        sortOrder: Int,
        version: Int = 1,
        from: String? = null,
        until: String? = null
    ) = ShareFrame(
        key = key,
        version = version,
        title = key,
        sortOrder = sortOrder,
        availableFrom = CalendarDate.parse(from),
        availableUntil = CalendarDate.parse(until),
        image = ShareFrameImageSource.Remote("https://cdn.example/$key/$version.webp", null),
        layout = layout.copy(key = key)
    )

    private val plain = frame("plain", sortOrder = 10)
    private val polaroid = frame("polaroid", sortOrder = 20)
    private val lavender = frame("lavender-black", sortOrder = 5)
    private val valentines = frame("valentines", sortOrder = 30, from = "2027-02-01", until = "2027-02-15")
    private val christmas = frame("christmas", sortOrder = 40, from = "2026-12-01", until = "2026-12-26")
    private val diwali = frame("diwali", sortOrder = 35, from = "2026-11-01", until = "2026-11-10")

    private fun date(value: String) = CalendarDate.parse(value)!!

    @Test
    fun `windows include both ends by local calendar date`() {
        assertFalse(valentines.isAvailableOn(date("2027-01-31")))
        assertTrue(valentines.isAvailableOn(date("2027-02-01")))
        assertTrue(valentines.isAvailableOn(date("2027-02-15")))
        assertFalse(valentines.isAvailableOn(date("2027-02-16")))
        assertTrue(plain.isAvailableOn(date("2027-02-16")))
        // An open end leaves that side unbounded.
        assertTrue(frame("open", 1, from = "2026-01-01").isAvailableOn(date("2030-06-01")))
        assertFalse(frame("open", 1, until = "2026-01-01").isAvailableOn(date("2026-01-02")))
    }

    @Test
    fun `outside every window, shows the always-available frames by sort order and picks Polaroid`() {
        val arrangement = ShareFrameSelector.arrange(
            listOf(polaroid, valentines, plain, christmas, lavender),
            today = date("2026-06-01")
        )
        assertEquals(listOf("lavender-black", "plain", "polaroid"), arrangement.frames.map { it.key })
        assertEquals("polaroid", arrangement.preselectedKey)
    }

    @Test
    fun `in-window seasonal frames come first by sort order and the first is preselected`() {
        // Diwali and an overlapping test window are both in season on 2026-11-06.
        val autumn = frame("autumn", sortOrder = 50, from = "2026-11-05", until = "2026-11-30")
        val arrangement = ShareFrameSelector.arrange(
            listOf(plain, autumn, polaroid, christmas, diwali, lavender),
            today = date("2026-11-06")
        )
        assertEquals(
            listOf("diwali", "autumn", "lavender-black", "plain", "polaroid"),
            arrangement.frames.map { it.key }
        )
        assertEquals("diwali", arrangement.preselectedKey)
    }

    @Test
    fun `without Polaroid or a seasonal frame, preselects the first frame`() {
        val arrangement = ShareFrameSelector.arrange(listOf(plain, lavender), today = date("2026-06-01"))
        assertEquals("lavender-black", arrangement.preselectedKey)
        assertNull(ShareFrameSelector.arrange(emptyList(), today = date("2026-06-01")).preselectedKey)
    }

    @Test
    fun `keeps only the highest version of each key`() {
        val arrangement = ShareFrameSelector.arrange(
            listOf(frame("plain", 10, version = 1), frame("plain", 10, version = 3), frame("plain", 10, version = 2), polaroid),
            today = date("2026-06-01")
        )
        assertEquals(listOf("plain" to 3, "polaroid" to 1), arrangement.frames.map { it.key to it.version })
    }

    @Test
    fun `the highest version decides the window even when it is out of season`() {
        val oldInSeason = frame("valentines", 30, version = 1, from = "2026-06-01", until = "2026-06-30")
        val newOutOfSeason = frame("valentines", 30, version = 2, from = "2027-02-01", until = "2027-02-15")
        val arrangement = ShareFrameSelector.arrange(listOf(oldInSeason, newOutOfSeason, plain), today = date("2026-06-10"))
        assertEquals(listOf("plain"), arrangement.frames.map { it.key })
    }

    @Test
    fun `catalog items with an unsupported layout version are skipped`() {
        val supported = JsonParser.parseString(File("src/main/assets/share_frames/plain/layout.json").readText())
        val unsupported = supported.deepCopy().asJsonObject.apply { addProperty("layoutVersion", 2) }
        fun item(key: String, layoutJson: com.google.gson.JsonElement?) = ShareFrameResponse(
            key = key, version = 1, title = key, sortOrder = 1, availableFrom = null, availableUntil = "2027-02-15",
            frameUrl = "/dev/share-frame-assets/$key/full.webp", thumbnailUrl = "https://cdn.example/$key/thumb.webp",
            width = 1080, height = 1920, layout = layoutJson
        )

        val mapped = item("plain", supported).toShareFrame { resolveShareFrameUrl(it, "http://10.0.2.2:8080/") }!!
        assertEquals("plain", mapped.key)
        assertNull(mapped.availableFrom)
        assertEquals(CalendarDate(2027, 2, 15), mapped.availableUntil)
        assertEquals(
            ShareFrameImageSource.Remote(
                frameUrl = "http://10.0.2.2:8080/dev/share-frame-assets/plain/full.webp",
                thumbnailUrl = "https://cdn.example/plain/thumb.webp"
            ),
            mapped.image
        )
        assertNull(item("plain", unsupported).toShareFrame())
        assertNull(item("plain", null).toShareFrame())
        assertNull(item("plain", supported).copy(version = 0).toShareFrame())
        assertNull(item("plain", supported).copy(frameUrl = "").toShareFrame())
    }

    @Test
    fun `bundled frames show only when the catalog can't be loaded or is empty`() {
        val bundled = listOf(polaroid, plain)
        val catalog = listOf(frame("plain", 10, version = 2), lavender)
        assertEquals(bundled, chooseShareFrames(null, bundled))
        assertEquals(bundled, chooseShareFrames(emptyList(), bundled))
        assertEquals(catalog, chooseShareFrames(catalog, bundled))
    }

    @Test
    fun `parses calendar dates and today`() {
        assertEquals(CalendarDate(2027, 2, 1), CalendarDate.parse("2027-02-01"))
        assertNull(CalendarDate.parse("2027-2-1"))
        assertNull(CalendarDate.parse("2027-13-01"))
        assertNull(CalendarDate.parse(null))
        assertTrue(CalendarDate(2026, 12, 31) < CalendarDate(2027, 1, 1))
        assertEquals(CalendarDate(2026, 11, 6), CalendarDate.today(GregorianCalendar(2026, Calendar.NOVEMBER, 6)))
    }

    @Test
    fun `cache file names are per key and version`() {
        assertEquals("valentines-v2.webp", shareFrameCacheFileName("valentines", 2))
        assertEquals("spring-kittens-v1.webp", shareFrameCacheFileName("spring-kittens", 1))
        assertEquals("____x-v1.webp", shareFrameCacheFileName("/../x", 1))
    }
}
