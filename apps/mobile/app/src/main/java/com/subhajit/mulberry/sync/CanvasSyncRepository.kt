package com.subhajit.mulberry.sync

import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.network.MulberryApiService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

interface CanvasSyncRepository {
    val syncState: StateFlow<SyncState>

    fun start()
    suspend fun queueLocalOperation(operation: CanvasSyncOperation)
    suspend fun reset()
}

@Singleton
class DefaultCanvasSyncRepository @Inject constructor(
    private val client: CanvasSyncClient,
    private val sessionBootstrapRepository: SessionBootstrapRepository,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val remoteOperationApplier: RemoteOperationApplier,
    private val apiService: MulberryApiService,
    @com.subhajit.mulberry.app.di.ApplicationScope private val applicationScope: CoroutineScope
) : CanvasSyncRepository {
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Disconnected)
    override val syncState: StateFlow<SyncState> = _syncState

    private val pendingOperations = MutableStateFlow<List<CanvasSyncOperation>>(emptyList())
    private var started = false
    private var currentUserId: String? = null

    override fun start() {
        if (started) return
        started = true

        applicationScope.launch {
            pendingOperations.value = syncMetadataRepository.metadata.first().pendingOperations
        }

        applicationScope.launch {
            combine(
                sessionBootstrapRepository.state,
                sessionBootstrapRepository.session
            ) { bootstrap, session ->
                bootstrap to session
            }.collect { (bootstrap, session) ->
                if (
                    session != null &&
                    bootstrap.pairingStatus == PairingStatus.PAIRED &&
                    bootstrap.pairSessionId != null
                ) {
                    currentUserId = session.userId
                    _syncState.value = SyncState.Connecting
                    val metadata = syncMetadataRepository.metadata.first()
                    client.connect(
                        accessToken = session.accessToken,
                        pairSessionId = bootstrap.pairSessionId,
                        lastAppliedServerRevision = metadata.lastAppliedServerRevision
                    )
                } else {
                    currentUserId = null
                    client.disconnect()
                    _syncState.value = SyncState.Disconnected
                }
            }
        }

        applicationScope.launch {
            client.messages.collect { message ->
                handleMessage(message)
            }
        }
    }

    override suspend fun queueLocalOperation(operation: CanvasSyncOperation) {
        val next = pendingOperations.value + operation
        pendingOperations.value = next
        syncMetadataRepository.setPendingOperations(next)
        if (syncState.value is SyncState.Connected) {
            client.send(operation)
        }
    }

    override suspend fun reset() {
        pendingOperations.value = emptyList()
        syncMetadataRepository.reset()
        client.disconnect()
        _syncState.value = SyncState.Disconnected
    }

    private suspend fun handleMessage(message: CanvasSyncMessage) {
        when (message) {
            is CanvasSyncMessage.Ready -> {
                _syncState.value = SyncState.Recovering
                applyMissedOperations(message.missedOperations)
                _syncState.value = SyncState.Connected
                pendingOperations.value.forEach(client::send)
                syncMetadataRepository.setLastError(null)
            }
            is CanvasSyncMessage.Ack -> {
                val next = pendingOperations.value
                    .filterNot { it.clientOperationId == message.clientOperationId }
                pendingOperations.value = next
                syncMetadataRepository.setPendingOperations(next)
                syncMetadataRepository.setLastAppliedServerRevision(message.serverRevision)
            }
            is CanvasSyncMessage.ServerOperation -> {
                val userId = currentUserId
                if (message.operation.actorUserId == userId) {
                    syncMetadataRepository.setLastAppliedServerRevision(message.operation.serverRevision)
                    return
                }
                val expectedNext = syncMetadataRepository.metadata.first().lastAppliedServerRevision + 1
                if (message.operation.serverRevision != expectedNext) {
                    recoverFromServer()
                } else {
                    remoteOperationApplier.apply(message.operation)
                }
            }
            is CanvasSyncMessage.Error -> {
                syncMetadataRepository.setLastError(message.message)
                _syncState.value = SyncState.Error(message.message)
            }
            CanvasSyncMessage.Closed -> {
                if (_syncState.value !is SyncState.Disconnected) {
                    _syncState.value = SyncState.Disconnected
                    reconnectIfStillPaired()
                }
            }
        }
    }

    private suspend fun reconnectIfStillPaired() {
        val bootstrap = sessionBootstrapRepository.state.first()
        val session = sessionBootstrapRepository.session.first()
        if (
            session != null &&
            bootstrap.pairingStatus == PairingStatus.PAIRED &&
            bootstrap.pairSessionId != null
        ) {
            _syncState.value = SyncState.Connecting
            val metadata = syncMetadataRepository.metadata.first()
            client.connect(
                accessToken = session.accessToken,
                pairSessionId = bootstrap.pairSessionId,
                lastAppliedServerRevision = metadata.lastAppliedServerRevision
            )
        }
    }

    private suspend fun recoverFromServer() {
        _syncState.value = SyncState.Recovering
        val afterRevision = syncMetadataRepository.metadata.first().lastAppliedServerRevision
        val operations = apiService.getCanvasOperations(afterRevision)
            .operations
            .map { it.toDomainOperation() }
        applyMissedOperations(operations)
        _syncState.value = SyncState.Connected
    }

    private suspend fun applyMissedOperations(operations: List<ServerCanvasOperation>) {
        val userId = currentUserId
        operations.forEach { operation ->
            if (operation.actorUserId == userId) {
                syncMetadataRepository.setLastAppliedServerRevision(operation.serverRevision)
            } else {
                remoteOperationApplier.apply(operation)
            }
        }
    }
}
