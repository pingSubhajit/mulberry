package com.subhajit.mulberry.streak

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.subhajit.mulberry.core.data.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * The lost streak whose bolt this device has shown breaking, per account. The server keeps the
 * same across devices; this copy stops a replay before the server has heard.
 */
interface LostStreakSeenRepository {
    /** The last activity day of the lost streak this account last watched break here. */
    suspend fun seenDay(userId: String): String?

    suspend fun markSeen(userId: String, day: String)
}

@Singleton
class DataStoreLostStreakSeenRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : LostStreakSeenRepository {
    override suspend fun seenDay(userId: String): String? {
        val stored = dataStore.data.first()[PreferenceStorage.lostStreakSeen] ?: return null
        val (owner, day) = stored.split('|', limit = 2).takeIf { it.size == 2 } ?: return null
        return day.takeIf { owner == userId }
    }

    override suspend fun markSeen(userId: String, day: String) {
        dataStore.edit { preferences -> preferences[PreferenceStorage.lostStreakSeen] = "$userId|$day" }
    }
}
