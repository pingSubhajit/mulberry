package com.subhajit.mulberry.stickers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository

@Singleton
class StickerAssetStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val stickerCatalogRepository: StickerCatalogRepository,
    private val stickerCatalogCacheStore: StickerCatalogCacheStore,
    private val sessionBootstrapRepository: SessionBootstrapRepository
) {
    private val downloadMutexes = ConcurrentHashMap<String, Mutex>()
    private val maxStickerAssetBytes: Long = 250L * 1024L * 1024L

    // Decoded artwork, kept in memory so a canvas that opens again draws its stickers on the
    // first frame instead of decoding them anew.
    private val bitmapCache = object : LruCache<String, Bitmap>(bitmapCacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val bitmapLoads = ConcurrentHashMap<String, Deferred<Bitmap?>>()
    // Loads outlive the screen that asked for them, so a canvas that recomposes or closes mid-load
    // still leaves the artwork decoded for next time.
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The decoded artwork, if it is already in memory. Safe to call while drawing. */
    fun cachedStickerBitmap(
        packKey: String,
        packVersion: Int,
        stickerId: String,
        variant: StickerAssetVariant
    ): Bitmap? = bitmapCache.get(bitmapKey(packKey, packVersion, stickerId, variant))

    /** Whether the artwork is already on this device, so showing it needs only a decode. */
    suspend fun hasStickerAsset(
        packKey: String,
        packVersion: Int,
        stickerId: String,
        variant: StickerAssetVariant,
        userId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val resolvedUserId = userId ?: sessionBootstrapRepository.state.first().userId
        if (resolvedUserId.isNullOrBlank() || packVersion <= 0) return@withContext false
        val file = destinationFile(resolvedUserId, packKey, packVersion, stickerId, variant)
        file.exists() && file.length() > 0
    }

    /**
     * The decoded artwork: from memory, else from this device, else downloaded first. Callers
     * asking for the same sticker at once share one load.
     */
    suspend fun loadStickerBitmap(
        packKey: String,
        packVersion: Int,
        stickerId: String,
        variant: StickerAssetVariant,
        urlHint: String? = null,
        userId: String? = null
    ): Bitmap? {
        val key = bitmapKey(packKey, packVersion, stickerId, variant)
        bitmapCache.get(key)?.let { return it }
        // A finished load that nobody collected (its caller went away) is retried, not reused.
        val load = bitmapLoads.compute(key) { _, running ->
            running?.takeUnless { it.isCompleted } ?: loadScope.async {
                val file = getOrDownloadStickerAsset(packKey, packVersion, stickerId, variant, urlHint, userId)
                    ?: return@async null
                BitmapFactory.decodeFile(file.absolutePath)?.also { bitmap ->
                    // Uploads the pixels ahead of the first draw.
                    bitmap.prepareToDraw()
                    bitmapCache.put(key, bitmap)
                }
            }
        }!!
        return try {
            load.await()
        } finally {
            if (load.isCompleted) bitmapLoads.remove(key, load)
        }
    }

    suspend fun getOrDownloadStickerAsset(
        packKey: String,
        packVersion: Int,
        stickerId: String,
        variant: StickerAssetVariant,
        urlHint: String? = null,
        userId: String? = null
    ): File? = withContext(Dispatchers.IO) {
        val normalizedPackKey = packKey.trim().lowercase()
        val normalizedStickerId = stickerId.trim()
        val resolvedUserId = userId ?: sessionBootstrapRepository.state.first().userId
        if (
            resolvedUserId.isNullOrBlank() ||
            normalizedPackKey.isBlank() ||
            packVersion <= 0 ||
            normalizedStickerId.isBlank()
        ) return@withContext null

        val destination = destinationFile(
            userId = resolvedUserId,
            packKey = normalizedPackKey,
            packVersion = packVersion,
            stickerId = normalizedStickerId,
            variant = variant
        )
        val destinationKey = destination.absolutePath
        val mutex = downloadMutexes.getOrPut(destinationKey) { Mutex() }
        mutex.withLock {
            stickerCatalogCacheStore.markPackAccessed(resolvedUserId, normalizedPackKey, packVersion)

            if (destination.exists() && destination.length() > 0) return@withLock destination

            destination.parentFile?.mkdirs()
            val temp = File.createTempFile("sticker-", ".download", destination.parentFile ?: context.cacheDir)
            try {
                suspend fun fetchFreshUrl(): String? {
                    return if (normalizedStickerId == COVER_STICKER_ID) {
                        runCatching {
                            val refreshed = stickerCatalogRepository.fetchPackDetail(
                                packKey = normalizedPackKey,
                                version = packVersion
                            )
                            when (variant) {
                                StickerAssetVariant.THUMBNAIL -> refreshed.coverThumbnailUrl
                                StickerAssetVariant.FULL -> refreshed.coverFullUrl
                            }
                        }.getOrNull()
                    } else {
                        runCatching {
                            stickerCatalogRepository.fetchStickerAssetUrl(
                                packKey = normalizedPackKey,
                                version = packVersion,
                                stickerId = normalizedStickerId,
                                variant = variant
                            ).url
                        }.getOrNull()
                    }?.takeIf { it.isNotBlank() }
                }

                fun tryDownload(url: String): Pair<Boolean, Int> {
                    val result = downloadStickerAssetToTempFile(
                        okHttpClient = okHttpClient,
                        url = url,
                        tempFile = temp
                    )
                    return result.ok to result.httpCode
                }

                val primary = urlHint?.takeIf { it.isNotBlank() } ?: fetchFreshUrl()
                if (primary.isNullOrBlank()) return@withLock null

                fun commitTempToDestination() {
                    if (!temp.renameTo(destination)) {
                        temp.copyTo(destination, overwrite = true)
                        temp.delete()
                    }
                }

                val (primaryOk, primaryCode) = tryDownload(primary)
                if (primaryOk) {
                    commitTempToDestination()
                    enforceBudget(resolvedUserId)
                    return@withLock destination
                }

                val retryable = primaryCode == 401 || primaryCode == 403
                if (!urlHint.isNullOrBlank() && retryable) {
                    val refreshed = fetchFreshUrl()
                    if (!refreshed.isNullOrBlank() && refreshed != primary) {
                        val (refreshedOk, _) = tryDownload(refreshed)
                        if (refreshedOk) {
                            commitTempToDestination()
                            enforceBudget(resolvedUserId)
                            return@withLock destination
                        }
                    }
                }

                null
            } finally {
                if (temp.exists() && temp != destination) {
                    temp.delete()
                }
                downloadMutexes.remove(destinationKey, mutex)
            }
        }
    }

    fun destinationFile(
        userId: String,
        packKey: String,
        packVersion: Int,
        stickerId: String,
        variant: StickerAssetVariant
    ): File {
        val base = File(context.filesDir, "stickers/${userId.trim()}")
        val folder = File(base, "${packKey.trim().lowercase()}/${packVersion}")
        val name = "${stickerId.trim()}-${variant.name.lowercase()}.png"
        return File(folder, name)
    }

    suspend fun clearAllStickerAssets(userId: String? = null) = withContext(Dispatchers.IO) {
        val resolvedUserId = userId ?: sessionBootstrapRepository.state.first().userId
        if (resolvedUserId.isNullOrBlank()) return@withContext
        File(context.filesDir, "stickers/${resolvedUserId.trim()}").deleteRecursively()
        bitmapCache.evictAll()
    }

    suspend fun enforceBudgetNow(userId: String? = null) = withContext(Dispatchers.IO) {
        val resolvedUserId = userId ?: sessionBootstrapRepository.state.first().userId
        if (resolvedUserId.isNullOrBlank()) return@withContext
        enforceBudget(resolvedUserId)
    }

    private suspend fun enforceBudget(userId: String) {
        val root = File(context.filesDir, "stickers/${userId.trim()}")
        if (!root.exists()) return

        val access = stickerCatalogCacheStore.getPackAccessMap(userId)
        val packVersionDirs = root.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { packKeyDir ->
                packKeyDir.listFiles()?.filter { it.isDirectory }?.map { versionDir ->
                    PackVersionDir(packKey = packKeyDir.name, packVersion = versionDir.name.toIntOrNull() ?: 0, dir = versionDir)
                }.orEmpty()
            }
            .orEmpty()
            .filter { it.packVersion > 0 }

        val sizes = packVersionDirs.associateWith { it.dir.safeSizeBytes() }
        var totalBytes = sizes.values.sum()
        if (totalBytes <= maxStickerAssetBytes) return

        val sorted = packVersionDirs.sortedBy { p ->
            access["${p.packKey.trim().lowercase()}:${p.packVersion}"] ?: 0L
        }
        for (pack in sorted) {
            if (totalBytes <= maxStickerAssetBytes) break
            val size = sizes[pack] ?: 0L
            pack.dir.deleteRecursively()
            stickerCatalogCacheStore.evictPackVersion(userId, pack.packKey, pack.packVersion)
            totalBytes -= size
        }
    }

    private fun bitmapKey(
        packKey: String,
        packVersion: Int,
        stickerId: String,
        variant: StickerAssetVariant
    ): String = "${packKey.trim().lowercase()}:$packVersion:${stickerId.trim()}:${variant.name.lowercase()}"

    private fun File.safeSizeBytes(): Long {
        if (!exists()) return 0L
        if (isFile) return length().coerceAtLeast(0L)
        return listFiles()?.sumOf { it.safeSizeBytes() } ?: 0L
    }

    private data class PackVersionDir(
        val packKey: String,
        val packVersion: Int,
        val dir: File
    )

    companion object {
        const val COVER_STICKER_ID = "__cover"

        // An eighth of the app's heap, at most 64 MB.
        private fun bitmapCacheBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(64L * 1024L * 1024L).toInt()
    }
}
