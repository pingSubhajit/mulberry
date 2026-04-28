package com.subhajit.mulberry.pairing.inbound

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.subhajit.mulberry.core.data.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class InboundInviteSource(val wireValue: String) {
    AppLink("applink"),
    InstallReferrer("install_referrer");

    companion object {
        fun fromWireValue(raw: String?): InboundInviteSource =
            entries.firstOrNull { it.wireValue == raw } ?: AppLink
    }
}

data class PendingInboundInvite(
    val code: String,
    val receivedAtMs: Long,
    val source: InboundInviteSource,
    val dismissedAtMs: Long? = null
)

interface InboundInviteRepository {
    val pendingInvite: Flow<PendingInboundInvite?>

    suspend fun setPendingInvite(code: String, source: InboundInviteSource)

    suspend fun dismissPendingInvite()

    suspend fun clearPendingInvite()

    suspend fun wasInstallReferrerChecked(): Boolean

    suspend fun markInstallReferrerChecked()
}

@Singleton
class DataStoreInboundInviteRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : InboundInviteRepository {

    override val pendingInvite: Flow<PendingInboundInvite?> = dataStore.data.map { preferences ->
        val code = preferences[PreferenceStorage.pendingInboundInviteCode] ?: return@map null
        val receivedAtMs =
            preferences[PreferenceStorage.pendingInboundInviteReceivedAtMs] ?: return@map null
        val source = InboundInviteSource.fromWireValue(
            preferences[PreferenceStorage.pendingInboundInviteSource]
        )
        val dismissedAtMs = preferences[PreferenceStorage.pendingInboundInviteDismissedAtMs]
        PendingInboundInvite(
            code = code,
            receivedAtMs = receivedAtMs,
            source = source,
            dismissedAtMs = dismissedAtMs
        )
    }

    override suspend fun setPendingInvite(code: String, source: InboundInviteSource) {
        val normalized = normalizeInviteCode(code) ?: return
        val now = System.currentTimeMillis()
        dataStore.edit { preferences ->
            preferences[PreferenceStorage.pendingInboundInviteCode] = normalized
            preferences[PreferenceStorage.pendingInboundInviteReceivedAtMs] = now
            preferences[PreferenceStorage.pendingInboundInviteSource] = source.wireValue
            preferences.remove(PreferenceStorage.pendingInboundInviteDismissedAtMs)
        }
    }

    override suspend fun dismissPendingInvite() {
        val now = System.currentTimeMillis()
        dataStore.edit { preferences ->
            if (preferences[PreferenceStorage.pendingInboundInviteCode] != null) {
                preferences[PreferenceStorage.pendingInboundInviteDismissedAtMs] = now
            }
        }
    }

    override suspend fun clearPendingInvite() {
        dataStore.edit { preferences ->
            preferences.remove(PreferenceStorage.pendingInboundInviteCode)
            preferences.remove(PreferenceStorage.pendingInboundInviteReceivedAtMs)
            preferences.remove(PreferenceStorage.pendingInboundInviteSource)
            preferences.remove(PreferenceStorage.pendingInboundInviteDismissedAtMs)
        }
    }

    override suspend fun wasInstallReferrerChecked(): Boolean {
        val preferences = dataStore.data.first()
        return preferences[PreferenceStorage.installReferrerChecked] ?: false
    }

    override suspend fun markInstallReferrerChecked() {
        dataStore.edit { preferences ->
            preferences[PreferenceStorage.installReferrerChecked] = true
        }
    }
}

fun normalizeInviteCode(raw: String?): String? {
    val digits = raw.orEmpty().filter(Char::isDigit).take(6)
    return digits.takeIf { it.length == 6 }
}
