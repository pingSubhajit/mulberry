package com.subhajit.mulberry.whatsnew

import java.time.LocalDate

object WhatsNewParser {
    fun parse(raw: String): WhatsNewEntry {
        val (frontmatter, body) = splitFrontmatter(raw)
        val version = frontmatter["version"]?.trim()?.takeIf { it.isNotBlank() }
        val releasedAt = frontmatter["released_at"]?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val title = frontmatter["title"]?.trim()?.takeIf { it.isNotBlank() }
        val hero = frontmatter["hero_image"]?.trim()?.takeIf { it.isNotBlank() }
        val cta = frontmatter["cta_label"]?.trim()?.takeIf { it.isNotBlank() } ?: "Sounds awesome!"

        return WhatsNewEntry(
            versionName = version,
            releasedAt = releasedAt,
            title = title,
            heroImagePathOrUrl = hero,
            ctaLabel = cta,
            markdownBody = body.trim()
        )
    }

    private fun splitFrontmatter(raw: String): Pair<Map<String, String>, String> {
        val lines = raw.split("\n")
        if (lines.firstOrNull()?.trim() != "---") return emptyMap<String, String>() to raw
        val frontmatter = mutableMapOf<String, String>()
        var endIndex = -1
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.trim() == "---") {
                endIndex = i
                break
            }
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim().trim('"')
            if (key.isNotBlank() && value.isNotBlank()) {
                frontmatter[key] = value
            }
        }
        if (endIndex == -1) return emptyMap<String, String>() to raw
        val body = lines.drop(endIndex + 1).joinToString("\n")
        return frontmatter to body
    }
}
