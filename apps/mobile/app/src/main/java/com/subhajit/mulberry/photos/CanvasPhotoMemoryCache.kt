package com.subhajit.mulberry.photos

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Decoded canvas photos in memory, keyed by photo id. The least recently used go first once the
 * photos pass [maxBytes].
 */
internal class CanvasPhotoMemoryCache<V : Any>(
    private val maxBytes: Long,
    private val sizeOf: (V) -> Int
) {
    private val entries = LinkedHashMap<String, V>(16, 0.75f, true)
    private var totalBytes = 0L

    private val _loadedIds = MutableStateFlow<Set<String>>(emptySet())

    /** The photos put in so far, so a canvas can redraw as each one arrives. */
    val loadedIds: StateFlow<Set<String>> = _loadedIds.asStateFlow()

    /** Changes on every [clear], so a load that began before it can't put its photo back. */
    @Volatile
    var generation: Long = 0L
        private set

    @Synchronized
    fun get(key: String): V? = entries[key]

    /** Keeps [value], unless the cache was cleared since [generation] was read. */
    @Synchronized
    fun put(key: String, value: V, generation: Long): Boolean {
        if (generation != this.generation) return false
        entries.put(key, value)?.let { previous -> totalBytes -= sizeOf(previous) }
        totalBytes += sizeOf(value)
        val iterator = entries.entries.iterator()
        while (totalBytes > maxBytes && iterator.hasNext()) {
            totalBytes -= sizeOf(iterator.next().value)
            iterator.remove()
        }
        _loadedIds.update { it + key }
        return true
    }

    /** Forgets every photo. */
    @Synchronized
    fun clear() {
        generation += 1
        entries.clear()
        totalBytes = 0L
        _loadedIds.value = emptySet()
    }
}
