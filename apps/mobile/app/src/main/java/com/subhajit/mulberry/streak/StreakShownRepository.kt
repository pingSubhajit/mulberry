package com.subhajit.mulberry.streak

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.subhajit.mulberry.core.data.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * The streak count the streak screen last showed, per account and pair, as on iPhone. A rise
 * since then ticks up and fills today in when the screen next opens; the first count ever shown
 * is the baseline and doesn't celebrate.
 */
interface StreakShownRepository {
    suspend fun shownDays(userId: String, pairSessionId: String): Int?

    suspend fun markShown(userId: String, pairSessionId: String, days: Int)
}

@Singleton
class DataStoreStreakShownRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : StreakShownRepository {
    override suspend fun shownDays(userId: String, pairSessionId: String): Int? {
        val stored = dataStore.data.first()[PreferenceStorage.streakShownDays] ?: return null
        val parts = stored.split('|')
        if (parts.size != 3 || parts[0] != userId || parts[1] != pairSessionId) return null
        return parts[2].toIntOrNull()
    }

    override suspend fun markShown(userId: String, pairSessionId: String, days: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceStorage.streakShownDays] = "$userId|$pairSessionId|$days"
        }
    }
}

/**
 * The count to tick up from as the streak screen opens: today's drawing has raised the streak
 * since the screen last showed it. Null when there is nothing to celebrate, or no baseline yet.
 */
fun celebrationStart(
    currentStreakDays: Int,
    hasActivityToday: Boolean,
    lostRecently: Boolean,
    shownDays: Int?
): Int? {
    if (!hasActivityToday || lostRecently || shownDays == null) return null
    return shownDays.takeIf { it < currentStreakDays }
}
