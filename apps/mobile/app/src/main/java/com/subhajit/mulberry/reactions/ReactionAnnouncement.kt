package com.subhajit.mulberry.reactions

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

/** How many of [type] arrived in this batch. */
fun PendingReactionBatch.count(type: ReactionType): Int = when (type) {
    ReactionType.HEART -> heartCount
    ReactionType.HUG -> hugCount
    ReactionType.KISS -> kissCount
    ReactionType.SMILE -> smileCount
    ReactionType.LAUGH -> laughCount
    ReactionType.SPARKLE -> sparkleCount
}

/** Each kind that arrived with its count, as "2 ❤️, 1 💋", in the rail's order. */
fun reactionArrivalSummary(batch: PendingReactionBatch): String =
    ReactionType.entries
        .filter { batch.count(it) > 0 }
        .joinToString(separator = ", ") { "${batch.count(it)} ${it.emoji}" }

/** What TalkBack says as reactions arrive, in the iPhone's words: "Sam sent 2 ❤️, 1 💋". */
fun reactionArrivalAnnouncement(batch: PendingReactionBatch, partnerName: String?): String {
    val name = partnerName?.trim().orEmpty().ifEmpty { "Your partner" }
    return "$name sent ${reactionArrivalSummary(batch)}"
}

/**
 * Speaks [text] through TalkBack (or any screen reader) from a place with no view hierarchy, such
 * as the live wallpaper, as iOS posts its announcement. Does nothing when no screen reader is on.
 */
fun Context.announceToScreenReader(text: String) {
    val manager = getSystemService(AccessibilityManager::class.java) ?: return
    if (!manager.isEnabled) return
    val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        AccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
    } else {
        @Suppress("DEPRECATION")
        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
    }
    event.className = javaClass.name
    event.packageName = packageName
    event.text.add(text)
    runCatching { manager.sendAccessibilityEvent(event) }
}
