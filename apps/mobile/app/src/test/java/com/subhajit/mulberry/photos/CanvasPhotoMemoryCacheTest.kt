package com.subhajit.mulberry.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasPhotoMemoryCacheTest {

    @Test
    fun `clearing forgets every decoded photo`() {
        val cache = CanvasPhotoMemoryCache<String>(maxBytes = 100) { it.length }
        cache.put("a", "photo-a", cache.generation)
        cache.put("b", "photo-b", cache.generation)

        cache.clear()

        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals(emptySet<String>(), cache.loadedIds.value)
    }

    @Test
    fun `a load that began before clearing keeps nothing`() {
        val cache = CanvasPhotoMemoryCache<String>(maxBytes = 100) { it.length }
        val loadStartedAt = cache.generation

        cache.clear()

        assertFalse(cache.put("a", "photo-a", loadStartedAt))
        assertNull(cache.get("a"))
        assertEquals(emptySet<String>(), cache.loadedIds.value)
        assertTrue(cache.put("a", "photo-a", cache.generation))
        assertEquals("photo-a", cache.get("a"))
    }

    @Test
    fun `over its budget the least recently used photo goes first`() {
        val cache = CanvasPhotoMemoryCache<String>(maxBytes = 10) { it.length }
        cache.put("a", "aaaa", cache.generation)
        cache.put("b", "bbbb", cache.generation)
        cache.get("a")

        cache.put("c", "cccc", cache.generation)

        assertEquals("aaaa", cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals("cccc", cache.get("c"))
    }
}
