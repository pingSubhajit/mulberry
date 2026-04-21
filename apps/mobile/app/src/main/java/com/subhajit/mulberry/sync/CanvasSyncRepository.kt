package com.subhajit.mulberry.sync

import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.network.MulberryApiService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.TreeMap

interface CanvasSyncRepository {
    val syncState: StateFlow<SyncState>

    fun start()
    fun stop()
    suspend fun queueLocalOperation(operation: CanvasSyncOperation)
    suspend fun reset()
}

@Singleton
class DefaultCanvasSyncRepository @Inject constructor(
    private val client: CanvasSyncClient,
    private val sessionBootstrapRepository: SessionBootstrapRepository,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val remoteOperationApplier: RemoteOperationApplier,
    private val drawingRepository: DrawingRepository,
    private val apiService: MulberryApiService,
    @com.subhajit.mulberry.app.di.ApplicationScope private val applicationScope: CoroutineScope
) : CanvasSyncRepository {
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Disconnected)
    override val syncState: StateFlow<SyncState> = _syncState

    private val syncEnabled = MutableStateFlow(false)
    private val pendingOperations = MutableStateFlow<List<CanvasSyncOperation>>(emptyList())
    private val messageMutex = Mutex()
    private val revisionBuffer = TreeMap<Long, ServerCanvasOperation>()
    private var isRecoveringGap = false
    private var started = false
    private var currentUserId: String? = null
    private var activeAccessToken: String? = null
    private var activePairSessionId: String? = null

    override fun start() {
        if (!started) {
            started = true
            startCollectors()
        }
        syncEnabled.value = true
    }

    override fun stop() {
        syncEnabled.value = false
        disconnectActiveSocket()
    }

    private fun startCollectors() {

        applicationScope.launch {
            pendingOperations.value = syncMetadataRepository.metadata.first().pendingOperations
        }

        applicationScope.launch {
            combine(
                syncEnabled,
                sessionBootstrapRepository.state,
                sessionBootstrapRepository.session
            ) { enabled, bootstrap, session ->
                Triple(enabled, bootstrap, session)
            }.collect { (enabled, bootstrap, session) ->
                if (
                    enabled &&
                    session != null &&
                    bootstrap.pairingStatus == PairingStatus.PAIRED &&
                    bootstrap.pairSessionId != null
                ) {
                    currentUserId = session.userId
                    if (
                        activeAccessToken == session.accessToken &&
                        activePairSessionId == bootstrap.pairSessionId &&
                        syncState.value !is SyncState.Disconnected &&
                        syncState.value !is SyncState.Error
                    ) {
                        return@collect
                    }
                    activeAccessToken = session.accessToken
                    activePairSessionId = bootstrap.pairSessionId
                    _syncState.value = SyncState.Connecting
                    val metadata = syncMetadataRepository.metadata.first()
                    client.connect(
                        accessToken = session.accessToken,
                        pairSessionId = bootstrap.pairSessionId,
                        lastAppliedServerRevision = metadata.lastAppliedServerRevision
                    )
                } else {
                    disconnectActiveSocket()
                }
            }
        }

        applicationScope.launch {
            client.messages.collect { message ->
                messageMutex.withLock {
                    handleMessage(message)
                }
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
        syncEnabled.value = false
        revisionBuffer.clear()
        isRecoveringGap = false
        syncMetadataRepository.reset()
        disconnectActiveSocket()
    }

    private suspend fun handleMessage(message: CanvasSyncMessage) {
        when (message) {
            is CanvasSyncMessage.Ready -> {
                _syncState.value = SyncState.Recovering
                enqueueAndDrain(message.missedOperations)
                recoverFromSnapshotIfCleanConnect(message.latestRevision)
                _syncState.value = SyncState.Connected
                pendingOperations.value.forEach(client::send)
                syncMetadataRepository.setLastError(null)
            }
            is CanvasSyncMessage.Ack -> {
                val next = pendingOperations.value
                    .filterNot { it.clientOperationId == message.clientOperationId }
                pendingOperations.value = next
                syncMetadataRepository.setPendingOperations(next)
                message.operation?.let { enqueueAndDrain(listOf(it)) }
            }
            is CanvasSyncMessage.ServerOperation -> {
                enqueueAndDrain(listOf(message.operation))
            }
            is CanvasSyncMessage.Error -> {
                syncMetadataRepository.setLastError(message.message)
                _syncState.value = SyncState.Error(message.message)
            }
            CanvasSyncMessage.Closed -> {
                if (_syncState.value !is SyncState.Disconnected) {
                    disconnectActiveSocket()
                    if (syncEnabled.value) {
                        reconnectIfStillPaired()
                    }
                }
            }
        }
    }

    private suspend fun reconnectIfStillPaired() {
        if (!syncEnabled.value) return
        val bootstrap = sessionBootstrapRepository.state.first()
        val session = sessionBootstrapRepository.session.first()
        if (
            session != null &&
            bootstrap.pairingStatus == PairingStatus.PAIRED &&
            bootstrap.pairSessionId != null
        ) {
            _syncState.value = SyncState.Connecting
            val metadata = syncMetadataRepository.metadata.first()
            activeAccessToken = session.accessToken
            activePairSessionId = bootstrap.pairSessionId
            client.connect(
                accessToken = session.accessToken,
                pairSessionId = bootstrap.pairSessionId,
                lastAppliedServerRevision = metadata.lastAppliedServerRevision
            )
        }
    }

    private suspend fun recoverFromServer() {
        if (isRecoveringGap) return
        isRecoveringGap = true
        _syncState.value = SyncState.Recovering
        runCatching {
            val afterRevision = syncMetadataRepository.metadata.first().lastAppliedServerRevision
            apiService.getCanvasOperations(afterRevision)
                .operations
                .map { it.toDomainOperation() }
        }.onSuccess { operations ->
            enqueueAndDrain(operations, allowRecovery = false)
            recoverFromSnapshotIfGapRemains()
            _syncState.value = SyncState.Connected
            syncMetadataRepository.setLastError(null)
        }.onFailure { error ->
            val message = error.message ?: "Unable to recover canvas sync"
            syncMetadataRepository.setLastError(message)
            _syncState.value = SyncState.Error(message)
        }
        isRecoveringGap = false
    }

    private suspend fun enqueueAndDrain(
        operations: List<ServerCanvasOperation>,
        allowRecovery: Boolean = true
    ) {
        val lastApplied = syncMetadataRepository.metadata.first().lastAppliedServerRevision
        operations
            .filter { it.serverRevision > lastApplied }
            .sortedBy { it.serverRevision }
            .forEach { operation ->
                revisionBuffer.putIfAbsent(operation.serverRevision, operation)
            }
        drainReadyRevisions()
        recoverIfGapRemains(allowRecovery)
    }

    private suspend fun drainReadyRevisions() {
        val userId = currentUserId
        while (true) {
            val expectedNext = syncMetadataRepository.metadata.first().lastAppliedServerRevision + 1
            val operation = revisionBuffer.remove(expectedNext) ?: return
            if (operation.actorUserId == userId) {
                syncMetadataRepository.setLastAppliedServerRevision(operation.serverRevision)
            } else {
                remoteOperationApplier.apply(operation)
            }
        }
    }

    private suspend fun recoverIfGapRemains(allowRecovery: Boolean) {
        if (!allowRecovery || revisionBuffer.isEmpty()) return
        val expectedNext = syncMetadataRepository.metadata.first().lastAppliedServerRevision + 1
        if (revisionBuffer.firstKey() > expectedNext) {
            recoverFromServer()
        }
    }

    private suspend fun recoverFromSnapshotIfGapRemains() {
        if (revisionBuffer.isEmpty()) return
        val expectedNext = syncMetadataRepository.metadata.first().lastAppliedServerRevision + 1
        if (revisionBuffer.firstKey() > expectedNext) {
            recoverFromSnapshot()
        }
    }

    private suspend fun recoverFromSnapshotIfCleanConnect(latestRevision: Long) {
        if (latestRevision <= 0 || pendingOperations.value.isNotEmpty() || revisionBuffer.isNotEmpty()) {
            return
        }
        val lastApplied = syncMetadataRepository.metadata.first().lastAppliedServerRevision
        if (lastApplied >= latestRevision) {
            recoverFromSnapshot()
        }
    }

    private suspend fun recoverFromSnapshot() {
        val snapshot = apiService.getCanvasSnapshot()
        drawingRepository.replaceWithRemoteSnapshot(
            strokes = snapshot.snapshot.strokes.map { stroke ->
                Stroke(
                    id = stroke.id,
                    colorArgb = stroke.colorArgb,
                    width = stroke.width,
                    createdAt = stroke.createdAt,
                    points = stroke.points.map { point ->
                        StrokePoint(x = point.x, y = point.y)
                    }
                )
            },
            serverRevision = snapshot.revision
        )
        syncMetadataRepository.setLastAppliedServerRevision(snapshot.revision)
        revisionBuffer.clear()
    }

    private fun disconnectActiveSocket() {
        currentUserId = null
        activeAccessToken = null
        activePairSessionId = null
        revisionBuffer.clear()
        isRecoveringGap = false
        client.disconnect()
        _syncState.value = SyncState.Disconnected
    }
}
