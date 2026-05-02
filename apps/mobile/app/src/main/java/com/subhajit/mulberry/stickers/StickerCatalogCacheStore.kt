package com.subhajit.mulberry.stickers

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.subhajit.mulberry.app.di.StickerCacheDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

data class CachedStickerPacks(
    val packs: List<StickerPackSummary>,
    val fetchedAtMs: Long?
)

interface StickerCatalogCacheStore {
    suspend fun getCachedPacks(userId: String): CachedStickerPacks?
    suspend fun putPacks(userId: String, packs: List<StickerPackSummary>, fetchedAtMs: Long = System.currentTimeMillis())

    suspend fun getCachedPackDetail(userId: String, packKey: String, packVersion: Int): StickerPackDetail?
    suspend fun putPackDetail(userId: String, detail: StickerPackDetail)

    suspend fun markPackAccessed(userId: String, packKey: String, packVersion: Int, accessedAtMs: Long = System.currentTimeMillis())
    suspend fun getPackAccessMap(userId: String): Map<String, Long>
    suspend fun evictPackVersion(userId: String, packKey: String, packVersion: Int)

    suspend fun clearUser(userId: String)
    suspend fun clearAll()
}

@Singleton
class DataStoreStickerCatalogCacheStore @Inject constructor(
    @StickerCacheDataStore private val dataStore: DataStore<Preferences>
) : StickerCatalogCacheStore {
    private val gson = Gson()
    private val cacheType = object : TypeToken<PersistedStickerCatalogCache>() {}.type

    override suspend fun getCachedPacks(userId: String): CachedStickerPacks? {
        val cache = readCache()
        val user = cache.users[userId] ?: return null
        return CachedStickerPacks(
            packs = user.packs,
            fetchedAtMs = user.packsFetchedAtMs
        )
    }

    override suspend fun putPacks(userId: String, packs: List<StickerPackSummary>, fetchedAtMs: Long) {
        updateCache { cache ->
            cache.copy(
                users = cache.users + (userId to cache.users[userId].orEmpty().copy(
                    packs = packs,
                    packsFetchedAtMs = fetchedAtMs
                ))
            )
        }
    }

    override suspend fun getCachedPackDetail(userId: String, packKey: String, packVersion: Int): StickerPackDetail? {
        val cache = readCache()
        val user = cache.users[userId] ?: return null
        return user.packDetails[packVersionKey(packKey, packVersion)]?.detail
    }

    override suspend fun putPackDetail(userId: String, detail: StickerPackDetail) {
        val key = packVersionKey(detail.packKey, detail.packVersion)
        updateCache { cache ->
            val user = cache.users[userId].orEmpty()
            cache.copy(
                users = cache.users + (userId to user.copy(
                    packDetails = user.packDetails + (key to PersistedPackDetailEntry(detail = detail))
                ))
            )
        }
    }

    override suspend fun markPackAccessed(userId: String, packKey: String, packVersion: Int, accessedAtMs: Long) {
        val key = packVersionKey(packKey, packVersion)
        updateCache { cache ->
            val user = cache.users[userId].orEmpty()
            cache.copy(
                users = cache.users + (userId to user.copy(
                    packAccessAtMs = user.packAccessAtMs + (key to accessedAtMs)
                ))
            )
        }
    }

    override suspend fun getPackAccessMap(userId: String): Map<String, Long> =
        readCache().users[userId]?.packAccessAtMs.orEmpty()

    override suspend fun evictPackVersion(userId: String, packKey: String, packVersion: Int) {
        val key = packVersionKey(packKey, packVersion)
        updateCache { cache ->
            val user = cache.users[userId] ?: return@updateCache cache
            cache.copy(
                users = cache.users + (userId to user.copy(
                    packDetails = user.packDetails - key,
                    packAccessAtMs = user.packAccessAtMs - key
                ))
            )
        }
    }

    override suspend fun clearUser(userId: String) {
        updateCache { cache ->
            cache.copy(users = cache.users - userId)
        }
    }

    override suspend fun clearAll() {
        dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    private suspend fun readCache(): PersistedStickerCatalogCache {
        val raw = dataStore.data.first()[STICKER_CATALOG_CACHE_JSON].orEmpty()
        if (raw.isBlank()) return PersistedStickerCatalogCache()
        return runCatching { gson.fromJson<PersistedStickerCatalogCache>(raw, cacheType) }.getOrNull()
            ?: PersistedStickerCatalogCache()
    }

    private suspend fun updateCache(transform: (PersistedStickerCatalogCache) -> PersistedStickerCatalogCache) {
        dataStore.edit { preferences ->
            val current = preferences[STICKER_CATALOG_CACHE_JSON]
                ?.let { raw -> runCatching { gson.fromJson<PersistedStickerCatalogCache>(raw, cacheType) }.getOrNull() }
                ?: PersistedStickerCatalogCache()
            preferences[STICKER_CATALOG_CACHE_JSON] = gson.toJson(transform(current))
        }
    }

    private fun packVersionKey(packKey: String, packVersion: Int): String =
        "${packKey.trim().lowercase()}:$packVersion"

    private fun PersistedUserStickerCache?.orEmpty(): PersistedUserStickerCache =
        this ?: PersistedUserStickerCache()

    private data class PersistedStickerCatalogCache(
        val version: Int = 1,
        val users: Map<String, PersistedUserStickerCache> = emptyMap()
    )

    private data class PersistedUserStickerCache(
        val packs: List<StickerPackSummary> = emptyList(),
        val packsFetchedAtMs: Long? = null,
        val packDetails: Map<String, PersistedPackDetailEntry> = emptyMap(),
        val packAccessAtMs: Map<String, Long> = emptyMap()
    )

    private data class PersistedPackDetailEntry(
        val detail: StickerPackDetail
    )

    private companion object {
        val STICKER_CATALOG_CACHE_JSON = stringPreferencesKey("sticker_catalog_cache_json")
    }
}

