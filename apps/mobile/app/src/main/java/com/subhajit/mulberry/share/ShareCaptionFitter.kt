package com.subhajit.mulberry.share

/** Measures a line's advance width in Permanent Marker at a font size, in output pixels. */
fun interface ShareTextMeasurer {
    fun advanceWidth(text: String, fontSize: Int): Double
}

data class ShareCaptionFit(
    val fontSize: Int,
    val lines: List<String>
)

/**
 * Fits a caption the way the reference renderer (temp/share-frames/v1/src/render_preview.py)
 * does. Wraps on spaces and tries each integer size from `maxFontSize` down to `minFontSize`. At a
 * size, the first line takes as many words as fit; the remaining words go on the fewest lines that
 * fit, split to give the smallest sum of squared unused widths. If nothing fits even at
 * `minFontSize`, it wraps greedily at `minFontSize`.
 */
object ShareCaptionFitter {
    fun words(text: String): List<String> = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }

    fun fit(
        text: String,
        maxWidth: Double,
        maxLines: Int,
        maxFontSize: Int,
        minFontSize: Int,
        measurer: ShareTextMeasurer
    ): ShareCaptionFit {
        val words = words(text)
        if (words.isEmpty()) return ShareCaptionFit(fontSize = maxFontSize, lines = emptyList())
        for (size in maxFontSize downTo minFontSize) {
            val lines = fitAtSize(words, maxWidth, maxLines, size, measurer)
            if (lines != null) return ShareCaptionFit(fontSize = size, lines = lines)
        }
        return ShareCaptionFit(
            fontSize = minFontSize,
            lines = greedyLines(words, maxWidth, minFontSize, measurer)
        )
    }

    fun fit(text: String, caption: ShareFrameCaption, measurer: ShareTextMeasurer): ShareCaptionFit = fit(
        text = text,
        maxWidth = caption.maxWidth,
        maxLines = caption.maxLines,
        maxFontSize = caption.maxFontSize,
        minFontSize = caption.minFontSize,
        measurer = measurer
    )

    /** The lines at [size], or null when the words don't fit in [maxLines]. */
    internal fun fitAtSize(
        words: List<String>,
        maxWidth: Double,
        maxLines: Int,
        size: Int,
        measurer: ShareTextMeasurer
    ): List<String>? {
        val widths = HashMap<Pair<Int, Int>, Double>()
        fun width(start: Int, end: Int): Double = widths.getOrPut(start to end) {
            measurer.advanceWidth(words.subList(start, end).joinToString(" "), size)
        }

        // The first line takes as many words as fit.
        if (width(0, 1) > maxWidth) return null
        var firstEnd = 1
        for (end in 2..words.size) {
            if (width(0, end) <= maxWidth) firstEnd = end
        }

        val memo = HashMap<Pair<Int, Int>, Solution?>()
        // The best split of words[start..] into exactly [count] lines, or null if there's none.
        fun wrap(start: Int, count: Int): Solution? {
            if (start == words.size) return if (count == 0) Solution(0.0, emptyList()) else null
            if (count == 0) return null
            val memoKey = start to count
            if (memo.containsKey(memoKey)) return memo[memoKey]
            var best: Solution? = null
            val ends = if (start == 0) listOf(firstEnd) else (start + 1)..words.size
            for (end in ends) {
                val lineWidth = width(start, end)
                if (lineWidth > maxWidth) break
                val rest = wrap(end, count - 1) ?: continue
                val unused = maxWidth - lineWidth
                val score = unused * unused + rest.score
                if (best == null || score < best.score) {
                    best = Solution(score, listOf(words.subList(start, end).joinToString(" ")) + rest.lines)
                }
            }
            memo[memoKey] = best
            return best
        }

        for (count in 1..maxLines) {
            wrap(0, count)?.let { return it.lines }
        }
        return null
    }

    internal fun greedyLines(
        words: List<String>,
        maxWidth: Double,
        size: Int,
        measurer: ShareTextMeasurer
    ): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isEmpty() || measurer.advanceWidth(candidate, size) <= maxWidth) {
                current = candidate
            } else {
                lines += current
                current = word
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    private data class Solution(val score: Double, val lines: List<String>)

    private val WHITESPACE = Regex("\\s+")
}

/** Keeps a caption to [limit] characters without splitting an emoji or other surrogate pair. */
fun limitCaptionLength(text: String, limit: Int = SHARE_CAPTION_MAX_LENGTH): String {
    if (text.codePointCount(0, text.length) <= limit) return text
    return text.substring(0, text.offsetByCodePoints(0, limit))
}

const val SHARE_CAPTION_MAX_LENGTH = 60
