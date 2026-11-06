package com.subhajit.mulberry.share

import java.util.Calendar

/**
 * A local calendar date. The app's minimum API has no java.time, and frame windows only need
 * comparison, so this parses `YYYY-MM-DD` itself.
 */
data class CalendarDate(val year: Int, val month: Int, val day: Int) : Comparable<CalendarDate> {
    override fun compareTo(other: CalendarDate): Int =
        compareValuesBy(this, other, CalendarDate::year, CalendarDate::month, CalendarDate::day)

    companion object {
        private val ISO_DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

        fun parse(value: String?): CalendarDate? {
            val match = value?.trim()?.let(ISO_DATE::matchEntire) ?: return null
            val (year, month, day) = match.destructured
            val date = CalendarDate(year.toInt(), month.toInt(), day.toInt())
            return date.takeIf { it.month in 1..12 && it.day in 1..31 }
        }

        /** Today on this device, in its time zone. */
        fun today(calendar: Calendar = Calendar.getInstance()): CalendarDate = CalendarDate(
            year = calendar.get(Calendar.YEAR),
            month = calendar.get(Calendar.MONTH) + 1,
            day = calendar.get(Calendar.DAY_OF_MONTH)
        )
    }
}

/** Where a frame's image comes from: the catalog, or the copy bundled in the app. */
sealed interface ShareFrameImageSource {
    data class Remote(val frameUrl: String, val thumbnailUrl: String?) : ShareFrameImageSource
    data class Bundled(val assetPath: String) : ShareFrameImageSource
}

data class ShareFrame(
    val key: String,
    val version: Int,
    val title: String,
    val sortOrder: Int,
    val availableFrom: CalendarDate?,
    val availableUntil: CalendarDate?,
    val image: ShareFrameImageSource,
    val layout: ShareFrameLayout
) {
    /** Seasonal frames have a date window; the rest are always available. */
    val isSeasonal: Boolean
        get() = availableFrom != null || availableUntil != null

    /** Both ends are inclusive local dates; a missing end leaves that side open. */
    fun isAvailableOn(date: CalendarDate): Boolean =
        (availableFrom == null || date >= availableFrom) &&
            (availableUntil == null || date <= availableUntil)
}

data class ShareFrameArrangement(
    val frames: List<ShareFrame>,
    val preselectedKey: String?
)

object ShareFrameSelector {
    const val POLAROID_KEY = "polaroid"

    /**
     * The client rules in docs/share-frames.md: keep the highest version of each key, show only
     * frames available today, put in-window seasonal frames first, each group by `sortOrder`, and
     * preselect the first seasonal frame, else Polaroid, else the first frame.
     */
    fun arrange(frames: List<ShareFrame>, today: CalendarDate): ShareFrameArrangement {
        val available = highestVersions(frames).filter { it.isAvailableOn(today) }
        val (seasonal, always) = available.partition { it.isSeasonal }
        val order = compareBy<ShareFrame>({ it.sortOrder }, { it.key })
        val ordered = seasonal.sortedWith(order) + always.sortedWith(order)
        val preselected = seasonal.minWithOrNull(order)
            ?: ordered.firstOrNull { it.key == POLAROID_KEY }
            ?: ordered.firstOrNull()
        return ShareFrameArrangement(frames = ordered, preselectedKey = preselected?.key)
    }

    fun highestVersions(frames: List<ShareFrame>): List<ShareFrame> =
        frames.groupBy { it.key }.values.map { versions -> versions.maxBy { it.version } }
}
