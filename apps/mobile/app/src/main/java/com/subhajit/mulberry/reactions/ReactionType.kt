package com.subhajit.mulberry.reactions

enum class ReactionType(
    val apiValue: String,
    val emoji: String,
    val shortcutLabel: String
) {
    HEART(apiValue = "HEART", emoji = "❤️", shortcutLabel = "Send love"),
    KISS(apiValue = "KISS", emoji = "💋", shortcutLabel = "Send a kiss"),
    LAUGH(apiValue = "LAUGH", emoji = "😂", shortcutLabel = "Send laughs"),
    SPARKLE(apiValue = "SPARKLE", emoji = "✨", shortcutLabel = "Send sparkles");

    companion object {
        fun fromApiValue(value: String?): ReactionType? {
            val normalized = value?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { it.apiValue == normalized }
        }
    }
}

