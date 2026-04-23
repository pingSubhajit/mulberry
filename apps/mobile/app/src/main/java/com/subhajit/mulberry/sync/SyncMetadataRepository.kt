package com.subhajit.mulberry.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.subhajit.mulberry.core.data.PreferenceStorage
import com.subhajit.mulberry.drawing.model.DrawingOperationType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class SyncMetadata(
    val pairSessionId: String? = null,
    val lastAppliedServerRevision: Long = 0L,
    val lastError: String? = null,
    val pendingOperations: List<CanvasSyncOperation> = emptyList()
)

interface SyncMetadataRepository {
    val metadata: Flow<SyncMetadata>

    suspend fun setLastAppliedServerRevision(revision: Long)
    suspend fun resetForPairSession(pairSessionId: String)
    suspend fun setLastError(message: String?)
    suspend fun setPendingOperations(operations: List<CanvasSyncOperation>)
    suspend fun reset()
}

@Singleton
class DataStoreSyncMetadataRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : SyncMetadataRepository {
    private val gson = Gson()
    private val pendingType = object : TypeToken<List<PersistedCanvasSyncOperation>>() {}.type

    override val metadata: Flow<SyncMetadata> = dataStore.data.map { preferences ->
        SyncMetadata(
            pairSessionId = preferences[PreferenceStorage.syncPairSessionId],
            lastAppliedServerRevision = preferences[PreferenceStorage.syncLastAppliedServerRevision]
                ?.toLongOrNull() ?: 0L,
            lastError = preferences[PreferenceStorage.syncLastError],
            pendingOperations = preferences[PreferenceStorage.syncPendingOperationsJson]
                ?.let { raw ->
                    runCatching {
                        gson.fromJson<List<PersistedCanvasSyncOperation>>(raw, pendingType)
                            .map { it.toDomain() }
                    }.getOrNull()
                }
                .orEmpty()
        )
    }

    override suspend fun setLastAppliedServerRevision(revision: Long) {
        dataStore.edit { preferences ->
            preferences[PreferenceStorage.syncLastAppliedServerRevision] = revision.toString()
        }
    }

    override suspend fun resetForPairSession(pairSessionId: String) {
        dataStore.edit { preferences ->
            preferences[PreferenceStorage.syncPairSessionId] = pairSessionId
            preferences[PreferenceStorage.syncLastAppliedServerRevision] = "0"
            preferences.remove(PreferenceStorage.syncLastError)
            preferences.remove(PreferenceStorage.syncPendingOperationsJson)
        }
    }

    override suspend fun setLastError(message: String?) {
        dataStore.edit { preferences ->
            if (message == null) {
                preferences.remove(PreferenceStorage.syncLastError)
            } else {
                preferences[PreferenceStorage.syncLastError] = message
            }
        }
    }

    override suspend fun setPendingOperations(operations: List<CanvasSyncOperation>) {
        dataStore.edit { preferences ->
            if (operations.isEmpty()) {
                preferences.remove(PreferenceStorage.syncPendingOperationsJson)
            } else {
                preferences[PreferenceStorage.syncPendingOperationsJson] =
                    gson.toJson(operations.map { PersistedCanvasSyncOperation.fromDomain(it) })
            }
        }
    }

    override suspend fun reset() {
        dataStore.edit { preferences ->
            preferences.remove(PreferenceStorage.syncPairSessionId)
            preferences.remove(PreferenceStorage.syncLastAppliedServerRevision)
            preferences.remove(PreferenceStorage.syncLastError)
            preferences.remove(PreferenceStorage.syncPendingOperationsJson)
        }
    }
}

private data class PersistedCanvasSyncOperation(
    val clientOperationId: String,
    val type: String,
    val strokeId: String?,
    val payloadJson: String,
    val clientCreatedAt: String
) {
    fun toDomain(): CanvasSyncOperation {
        val operationType = DrawingOperationType.valueOf(type)
        return CanvasSyncOperation(
            clientOperationId = clientOperationId,
            type = operationType,
            strokeId = strokeId,
            payload = JsonParser.parseString(payloadJson).asJsonObject.toSyncPayload(operationType),
            clientCreatedAt = clientCreatedAt
        )
    }

    companion object {
        fun fromDomain(operation: CanvasSyncOperation): PersistedCanvasSyncOperation =
            PersistedCanvasSyncOperation(
                clientOperationId = operation.clientOperationId,
                type = operation.type.name,
                strokeId = operation.strokeId,
                payloadJson = operation.payload.toJsonObject().toString(),
                clientCreatedAt = operation.clientCreatedAt
            )
    }
}
