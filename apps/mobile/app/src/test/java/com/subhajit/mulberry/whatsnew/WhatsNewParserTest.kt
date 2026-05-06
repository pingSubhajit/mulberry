package com.subhajit.mulberry.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhatsNewParserTest {

    @Test
    fun `parses frontmatter and body`() {
        val raw = """
            ---
            version: 1.1.5
            released_at: 2026-05-06
            title: Reactions on your wallpaper
            hero_image: /whats-new/assets/1.1.5/hero.webp
            cta_label: Sounds awesome!
            ---
            
            Hello **world**
        """.trimIndent()

        val parsed = WhatsNewParser.parse(raw)
        assertEquals("1.1.5", parsed.versionName)
        assertEquals("Reactions on your wallpaper", parsed.title)
        assertEquals("/whats-new/assets/1.1.5/hero.webp", parsed.heroImagePathOrUrl)
        assertEquals("Sounds awesome!", parsed.ctaLabel)
        assertEquals("Hello **world**", parsed.markdownBody)
    }

    @Test
    fun `treats missing frontmatter as plain markdown`() {
        val raw = "Hello"
        val parsed = WhatsNewParser.parse(raw)
        assertNull(parsed.versionName)
        assertEquals("Hello", parsed.markdownBody)
    }
}

