package com.subhajit.mulberry.analytics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import com.subhajit.mulberry.app.di.AnalyticsQueueDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** One queued event, in the shape `POST /analytics/events` takes. */
data class AnalyticsEventRecord(
    val id: String,
    val event: String,
    val occurredAt: String,
    val properties: Map<String, String>
)

interface AnalyticsQueueStore {
    /** Adds [record], then drops the oldest events beyond [capacity]. */
    suspend fun append(record: AnalyticsEventRecord, capacity: Int)

    /** The oldest [limit] events, oldest first. */
    suspend fun peek(limit: Int): List<AnalyticsEventRecord>

    suspend fun remove(ids: Set<String>)
}

/** Keeps unsent events in their own DataStore file, so the queue never bloats app preferences. */
@Singleton
class DataStoreAnalyticsQueueStore @Inject constructor(
    @AnalyticsQueueDataStore private val dataStore: DataStore<Preferences>
) : AnalyticsQueueStore {
    private val gson = Gson()
    private val listType = object : TypeToken<List<AnalyticsEventRecord>>() {}.type

    override suspend fun append(record: AnalyticsEventRecord, capacity: Int) {
        dataStore.edit { preferences ->
            val events = decode(preferences[EVENTS_KEY]) + record
            preferences[EVENTS_KEY] = gson.toJson(events.takeLast(capacity))
        }
    }

    override suspend fun peek(limit: Int): List<AnalyticsEventRecord> =
        decode(dataStore.data.first()[EVENTS_KEY]).take(limit)

    override suspend fun remove(ids: Set<String>) {
        if (ids.isEmpty()) return
        dataStore.edit { preferences ->
            val remaining = decode(preferences[EVENTS_KEY]).filterNot { it.id in ids }
            preferences[EVENTS_KEY] = gson.toJson(remaining)
        }
    }

    private fun decode(raw: String?): List<AnalyticsEventRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        val parsed: List<AnalyticsEventRecord?> = try {
            gson.fromJson(raw, listType) ?: emptyList()
        } catch (_: JsonParseException) {
            emptyList()
        }
        // Gson skips Kotlin's null checks, so a damaged entry could carry nulls.
        return parsed.filterNotNull().filter(::isComplete)
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun isComplete(record: AnalyticsEventRecord): Boolean =
        record.id != null && record.event != null && record.occurredAt != null && record.properties != null

    private companion object {
        val EVENTS_KEY = stringPreferencesKey("analytics_events_json")
    }
}
