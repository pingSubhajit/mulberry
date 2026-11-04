package com.subhajit.mulberry.analytics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.subhajit.mulberry.core.data.PreferenceStorage
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The install's anonymous analytics ID: a lowercase UUID made on first use and kept across sign-outs. */
@Singleton
class AnalyticsInstallId @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    @Volatile private var cached: String? = null
    private val mutex = Mutex()

    suspend fun get(): String {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: readOrCreate().also { cached = it }
        }
    }

    private suspend fun readOrCreate(): String {
        var id = ""
        dataStore.edit { preferences ->
            id = preferences[PreferenceStorage.analyticsAnonymousId]
                ?.takeIf(String::isNotBlank)
                ?: UUID.randomUUID().toString().lowercase(Locale.US).also {
                    preferences[PreferenceStorage.analyticsAnonymousId] = it
                }
        }
        return id
    }
}
