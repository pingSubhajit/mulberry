package com.subhajit.mulberry.reactions

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.subhajit.mulberry.core.data.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class ReactionLocalStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val lastUsedReaction: Flow<ReactionType> =
        dataStore.data.map { prefs ->
            ReactionType.fromApiValue(prefs[PreferenceStorage.lastUsedReactionType])
                ?: ReactionType.HEART
        }.distinctUntilChanged()

    suspend fun getLastUsedReaction(): ReactionType {
        val value = dataStore.data.first()[PreferenceStorage.lastUsedReactionType]
        return ReactionType.fromApiValue(value) ?: ReactionType.HEART
    }

    suspend fun setLastUsedReaction(type: ReactionType) {
        dataStore.edit { prefs ->
            prefs[PreferenceStorage.lastUsedReactionType] = type.apiValue
        }
    }

    suspend fun getOrCreateDeviceId(): String {
        val existing = dataStore.data.first()[PreferenceStorage.installationDeviceId]
        if (!existing.isNullOrBlank()) return existing
        val next = UUID.randomUUID().toString()
        dataStore.edit { prefs ->
            prefs[PreferenceStorage.installationDeviceId] = next
        }
        return next
    }
}
