package com.subhajit.mulberry.reactions

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.subhajit.mulberry.core.data.PreferenceStorage
import com.subhajit.mulberry.sync.ReactionPushPayload
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class PendingReactionBatch(
    val pairSessionId: String?,
    val generation: Long,
    val heartCount: Int,
    val hugCount: Int,
    val kissCount: Int,
    val smileCount: Int,
    val laughCount: Int,
    val sparkleCount: Int,
    val receivedAtMs: Long
) {
    val totalCount: Int
        get() = heartCount + hugCount + kissCount + smileCount + laughCount + sparkleCount

    val uniqueTypeCount: Int
        get() = listOf(heartCount, hugCount, kissCount, smileCount, laughCount, sparkleCount).count { it > 0 }
}

@Singleton
class PendingReactionStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val pending: Flow<PendingReactionBatch?> = dataStore.data.map { prefs ->
        val generation = prefs[PreferenceStorage.pendingReactionGeneration] ?: 0L
        if (generation <= 0L) return@map null
        val heart = prefs[PreferenceStorage.pendingReactionHeartCount] ?: 0
        val hug = prefs[PreferenceStorage.pendingReactionHugCount] ?: 0
        val kiss = prefs[PreferenceStorage.pendingReactionKissCount] ?: 0
        val smile = prefs[PreferenceStorage.pendingReactionSmileCount] ?: 0
        val laugh = prefs[PreferenceStorage.pendingReactionLaughCount] ?: 0
        val sparkle = prefs[PreferenceStorage.pendingReactionSparkleCount] ?: 0
        val total = heart + hug + kiss + smile + laugh + sparkle
        if (total <= 0) return@map null
        PendingReactionBatch(
            pairSessionId = prefs[PreferenceStorage.pendingReactionPairSessionId],
            generation = generation,
            heartCount = heart,
            hugCount = hug,
            kissCount = kiss,
            smileCount = smile,
            laughCount = laugh,
            sparkleCount = sparkle,
            receivedAtMs = prefs[PreferenceStorage.pendingReactionReceivedAtMs] ?: 0L
        )
    }.distinctUntilChanged()

    suspend fun setFromPush(payload: ReactionPushPayload) {
        setPending(
            pairSessionId = payload.pairSessionId,
            generation = payload.generation,
            heartCount = payload.heartCount,
            hugCount = payload.hugCount,
            kissCount = payload.kissCount,
            smileCount = payload.smileCount,
            laughCount = payload.laughCount,
            sparkleCount = payload.sparkleCount
        )
    }

    private suspend fun setPending(
        pairSessionId: String?,
        generation: Long,
        heartCount: Int,
        hugCount: Int,
        kissCount: Int,
        smileCount: Int,
        laughCount: Int,
        sparkleCount: Int
    ) {
        dataStore.edit { prefs ->
            val existing = prefs[PreferenceStorage.pendingReactionGeneration] ?: 0L
            val existingTotal =
                (prefs[PreferenceStorage.pendingReactionHeartCount] ?: 0) +
                    (prefs[PreferenceStorage.pendingReactionHugCount] ?: 0) +
                    (prefs[PreferenceStorage.pendingReactionKissCount] ?: 0) +
                    (prefs[PreferenceStorage.pendingReactionSmileCount] ?: 0) +
                    (prefs[PreferenceStorage.pendingReactionLaughCount] ?: 0) +
                    (prefs[PreferenceStorage.pendingReactionSparkleCount] ?: 0)
            val incomingTotal =
                heartCount + hugCount + kissCount + smileCount + laughCount + sparkleCount
            val shouldUpdate = when {
                generation > existing -> true
                generation < existing -> false
                else -> incomingTotal > existingTotal
            }
            if (!shouldUpdate) return@edit
            prefs[PreferenceStorage.pendingReactionGeneration] = generation
            prefs[PreferenceStorage.pendingReactionPairSessionId] = pairSessionId ?: ""
            prefs[PreferenceStorage.pendingReactionHeartCount] = heartCount
            prefs[PreferenceStorage.pendingReactionHugCount] = hugCount
            prefs[PreferenceStorage.pendingReactionKissCount] = kissCount
            prefs[PreferenceStorage.pendingReactionSmileCount] = smileCount
            prefs[PreferenceStorage.pendingReactionLaughCount] = laughCount
            prefs[PreferenceStorage.pendingReactionSparkleCount] = sparkleCount
            prefs[PreferenceStorage.pendingReactionReceivedAtMs] = System.currentTimeMillis()
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(PreferenceStorage.pendingReactionGeneration)
            prefs.remove(PreferenceStorage.pendingReactionPairSessionId)
            prefs.remove(PreferenceStorage.pendingReactionHeartCount)
            prefs.remove(PreferenceStorage.pendingReactionHugCount)
            prefs.remove(PreferenceStorage.pendingReactionKissCount)
            prefs.remove(PreferenceStorage.pendingReactionSmileCount)
            prefs.remove(PreferenceStorage.pendingReactionLaughCount)
            prefs.remove(PreferenceStorage.pendingReactionSparkleCount)
            prefs.remove(PreferenceStorage.pendingReactionReceivedAtMs)
        }
    }

    suspend fun clearIfGeneration(generation: Long) {
        dataStore.edit { prefs ->
            val existing = prefs[PreferenceStorage.pendingReactionGeneration] ?: 0L
            if (existing != generation) return@edit
            prefs.remove(PreferenceStorage.pendingReactionGeneration)
            prefs.remove(PreferenceStorage.pendingReactionPairSessionId)
            prefs.remove(PreferenceStorage.pendingReactionHeartCount)
            prefs.remove(PreferenceStorage.pendingReactionHugCount)
            prefs.remove(PreferenceStorage.pendingReactionKissCount)
            prefs.remove(PreferenceStorage.pendingReactionSmileCount)
            prefs.remove(PreferenceStorage.pendingReactionLaughCount)
            prefs.remove(PreferenceStorage.pendingReactionSparkleCount)
            prefs.remove(PreferenceStorage.pendingReactionReceivedAtMs)
        }
    }
}
