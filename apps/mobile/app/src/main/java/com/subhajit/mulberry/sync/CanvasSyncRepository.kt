package com.subhajit.mulberry.sync

import com.subhajit.mulberry.canvas.CanvasRuntime
import com.subhajit.mulberry.canvas.CanvasRuntimeEvent
import com.subhajit.mulberry.canvas.FlowControlMode
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.drawing.model.DrawingOperationType
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.network.MulberryApiService
import java.util.TreeMap
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val canvasRuntime: CanvasRuntime,
    private val apiService: MulberryApiService,
    private val recoveryPolicy: CanvasRecoveryPolicy,
    @com.subhajit.mulberry.app.di.ApplicationScope private val applicationScope: CoroutineScope
) : CanvasSyncRepository {
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Disconnected)
    override val syncState: StateFlow<SyncState> = _syncState

    private val syncEnabled = MutableStateFlow(false)
    private val pendingOperations = MutableStateFlow<List<CanvasSyncOperation>>(emptyList())
    private val messageMutex = Mutex()
    private val sendMutex = Mutex()
    private val revisionBuffer = TreeMap<Long, ServerCanvasOperation>()
    private val sentOperationIds = mutableSetOf<String>()
    private var isRecoveringGap = false
    private var sendScheduled = false
    private var pendingPersistenceScheduled = false
    private var started = false
    private var currentUserId: String? = null
    private var activeAccessToken: String? = null
    private var activePairSessionId: String? = null
    private var lastForegroundStoppedAt: Long? = null

    override fun start() {
        if (!started) {
            started = true
            startCollectors()
        }
        syncEnabled.value = true
    }

    override fun stop() {
        syncEnabled.value = false
        lastForegroundStoppedAt = System.currentTimeMillis()
        disconnectActiveSocket()
    }

    private fun startCollectors() {
        applicationScope.launch {
            pendingOperations.value = syncMetadataRepository.metadata.first().pendingOperations
        }

        applicationScope.launch {
            canvasRuntime.outboundOperations.collect { operation ->
                queueLocalOperation(operation)
            }
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
                    canvasRuntime.start(
                        pairSessionId = bootstrap.pairSessionId,
                        userId = session.userId
                    )
                    _syncState.value = SyncState.Connecting
                    canvasRuntime.setSyncState(SyncState.Connecting)
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
        if (syncState.value is SyncState.Connected) {
            schedulePendingSend()
        }
        if (operation.type != DrawingOperationType.APPEND_POINTS) {
            syncMetadataRepository.setPendingOperations(next)
        } else {
            schedulePendingPersistence()
        }
    }

    override suspend fun reset() {
        pendingOperations.value = emptyList()
        syncEnabled.value = false
        revisionBuffer.clear()
        sentOperationIds.clear()
        isRecoveringGap = false
        sendScheduled = false
        pendingPersistenceScheduled = false
        syncMetadataRepository.reset()
        disconnectActiveSocket()
    }

    private suspend fun handleMessage(message: CanvasSyncMessage) {
        when (message) {
            is CanvasSyncMessage.Ready -> handleReady(message)
            is CanvasSyncMessage.Ack -> {
                sentOperationIds.remove(message.clientOperationId)
                val next = pendingOperations.value
                    .filterNot { it.clientOperationId == message.clientOperationId }
                pendingOperations.value = next
                syncMetadataRepository.setPendingOperations(next)
                message.operation?.let { enqueueAndDrain(listOf(it)) }
            }
            is CanvasSyncMessage.AckBatch -> {
                sentOperationIds.removeAll(message.ackedClientOperationIds.toSet())
                val ackedIds = message.ackedClientOperationIds.toSet()
                val next = pendingOperations.value.filterNot { it.clientOperationId in ackedIds }
                pendingOperations.value = next
                syncMetadataRepository.setPendingOperations(next)
                enqueueAndDrain(message.operations)
                schedulePendingSend()
            }
            is CanvasSyncMessage.ServerOperation -> {
                enqueueAndDrain(listOf(message.operation))
            }
            is CanvasSyncMessage.ServerOperationBatch -> {
                enqueueAndDrain(message.operations)
            }
            is CanvasSyncMessage.FlowControl -> {
                canvasRuntime.submit(
                    CanvasRuntimeEvent.FlowControl(
                        mode = if (message.mode == FlowControlMode.SLOW_DOWN.name) {
                            FlowControlMode.SLOW_DOWN
                        } else {
                            FlowControlMode.NORMAL
                        },
                        maxAppendHz = message.maxAppendHz,
                        reason = message.reason
                    )
                )
            }
            CanvasSyncMessage.ResyncRequired -> {
                recoverFromServer(CanvasRecoveryReason.RESYNC_REQUIRED)
            }
            is CanvasSyncMessage.Error -> {
                syncMetadataRepository.setLastError(message.message)
                _syncState.value = SyncState.Error(message.message)
                canvasRuntime.setSyncState(SyncState.Error(message.message))
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

    private suspend fun handleReady(message: CanvasSyncMessage.Ready) {
        _syncState.value = SyncState.Recovering
        canvasRuntime.setSyncState(SyncState.Recovering)
        val lastApplied = syncMetadataRepository.metadata.first().lastAppliedServerRevision
        val input = CanvasRecoveryInput(
            lastAppliedRevision = lastApplied,
            latestRevision = message.latestRevision,
            missedOperationCount = message.missedOperations.size,
            idleDurationMs = foregroundIdleDurationMs(),
            hasPendingLocalOperations = pendingOperations.value.isNotEmpty(),
            reason = if (
                message.missedOperations.isEmpty() &&
                message.latestRevision > lastApplied
            ) {
                CanvasRecoveryReason.EMPTY_TAIL_GAP
            } else {
                CanvasRecoveryReason.READY
            }
        )

        if (recoveryPolicy.shouldUseSnapshot(input)) {
            recoverFromSnapshot()
        } else {
            applyRecoveryOperationsAtomically(message.missedOperations)
        }

        lastForegroundStoppedAt = null
        _syncState.value = SyncState.Connected
        canvasRuntime.setSyncState(SyncState.Connected)
        schedulePendingSend()
        syncMetadataRepository.setLastError(null)
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
            canvasRuntime.start(
                pairSessionId = bootstrap.pairSessionId,
                userId = session.userId
            )
            canvasRuntime.setSyncState(SyncState.Connecting)
            val metadata = syncMetadataRepository.metadata.first()
            currentUserId = session.userId
            activeAccessToken = session.accessToken
            activePairSessionId = bootstrap.pairSessionId
            client.connect(
                accessToken = session.accessToken,
                pairSessionId = bootstrap.pairSessionId,
                lastAppliedServerRevision = metadata.lastAppliedServerRevision
            )
        }
    }

    private suspend fun recoverFromServer(reason: CanvasRecoveryReason) {
        if (isRecoveringGap) return
        isRecoveringGap = true
        _syncState.value = SyncState.Recovering
        canvasRuntime.setSyncState(SyncState.Recovering)
        runCatching {
            val afterRevision = syncMetadataRepository.metadata.first().lastAppliedServerRevision
            apiService.getCanvasOperations(afterRevision)
                .operations
                .map { it.toDomainOperation() }
        }.onSuccess { operations ->
            val lastApplied = syncMetadataRepository.metadata.first().lastAppliedServerRevision
            val latestRevision = operations.lastOrNull()?.serverRevision ?: lastApplied
            val input = CanvasRecoveryInput(
                lastAppliedRevision = lastApplied,
                latestRevision = latestRevision,
                missedOperationCount = operations.size,
                idleDurationMs = foregroundIdleDurationMs(),
                hasPendingLocalOperations = pendingOperations.value.isNotEmpty(),
                reason = if (operations.isEmpty() && latestRevision > lastApplied) {
                    CanvasRecoveryReason.EMPTY_TAIL_GAP
                } else {
                    reason
                }
            )
            if (recoveryPolicy.shouldUseSnapshot(input)) {
                recoverFromSnapshot()
            } else {
                applyRecoveryOperationsAtomically(operations)
            }
            _syncState.value = SyncState.Connected
            canvasRuntime.setSyncState(SyncState.Connected)
            syncMetadataRepository.setLastError(null)
        }.onFailure { error ->
            val message = error.message ?: "Unable to recover canvas sync"
            syncMetadataRepository.setLastError(message)
            _syncState.value = SyncState.Error(message)
            canvasRuntime.setSyncState(SyncState.Error(message))
        }
        isRecoveringGap = false
    }

    private fun schedulePendingSend() {
        if (syncState.value !is SyncState.Connected) return
        applicationScope.launch {
            sendMutex.withLock {
                if (sendScheduled) return@withLock
                sendScheduled = true
            }
            delay(BATCH_DELAY_MS)
            sendPendingBatch()
        }
    }

    private suspend fun sendPendingBatch() {
        sendMutex.withLock {
            sendScheduled = false
            if (syncState.value !is SyncState.Connected) return@withLock
            val unsent = pendingOperations.value
                .filterNot { it.clientOperationId in sentOperationIds }
                .take(MAX_OPERATIONS_PER_BATCH)
            if (unsent.isEmpty()) return@withLock
            val batchId = UUID.randomUUID().toString()
            unsent.forEach { sentOperationIds.add(it.clientOperationId) }
            client.sendBatch(batchId, unsent)
            if (pendingOperations.value.size > unsent.size) {
                schedulePendingSend()
            }
        }
    }

    private fun schedulePendingPersistence() {
        if (pendingPersistenceScheduled) return
        pendingPersistenceScheduled = true
        applicationScope.launch {
            delay(PENDING_PERSISTENCE_DELAY_MS)
            pendingPersistenceScheduled = false
            syncMetadataRepository.setPendingOperations(pendingOperations.value)
        }
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
                canvasRuntime.submit(CanvasRuntimeEvent.RemoteOperation(operation))
                syncMetadataRepository.setLastAppliedServerRevision(operation.serverRevision)
            }
        }
    }

    private suspend fun recoverIfGapRemains(allowRecovery: Boolean) {
        if (!allowRecovery || revisionBuffer.isEmpty()) return
        val expectedNext = syncMetadataRepository.metadata.first().lastAppliedServerRevision + 1
        if (revisionBuffer.firstKey() > expectedNext) {
            recoverFromServer(CanvasRecoveryReason.REVISION_GAP)
        }
    }

    private suspend fun recoverFromSnapshot() {
        val snapshot = apiService.getCanvasSnapshot()
        canvasRuntime.submitAndAwait(
            CanvasRuntimeEvent.RecoverySnapshot(
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
        )
        syncMetadataRepository.setLastAppliedServerRevision(snapshot.revision)
        revisionBuffer.clear()
    }

    private suspend fun applyRecoveryOperationsAtomically(operations: List<ServerCanvasOperation>) {
        val lastApplied = syncMetadataRepository.metadata.first().lastAppliedServerRevision
        val ordered = operations
            .filter { it.serverRevision > lastApplied }
            .sortedBy { it.serverRevision }
        if (ordered.isEmpty()) return

        val compacted = recoveryPolicy.compactTailOperations(ordered)
        val userId = currentUserId
        val remoteOperations = compacted.filterNot { it.actorUserId == userId }
        if (remoteOperations.isNotEmpty()) {
            canvasRuntime.submitAndAwait(
                CanvasRuntimeEvent.RecoveryOperations(
                    operations = remoteOperations,
                    publishAtomically = true
                )
            )
        }
        syncMetadataRepository.setLastAppliedServerRevision(ordered.last().serverRevision)
    }

    private fun foregroundIdleDurationMs(): Long =
        lastForegroundStoppedAt?.let { stoppedAt ->
            (System.currentTimeMillis() - stoppedAt).coerceAtLeast(0L)
        } ?: 0L

    private fun disconnectActiveSocket() {
        val pending = pendingOperations.value
        if (pending.isNotEmpty()) {
            applicationScope.launch {
                syncMetadataRepository.setPendingOperations(pending)
            }
        }
        currentUserId = null
        activeAccessToken = null
        activePairSessionId = null
        revisionBuffer.clear()
        sentOperationIds.clear()
        isRecoveringGap = false
        sendScheduled = false
        pendingPersistenceScheduled = false
        client.disconnect()
        _syncState.value = SyncState.Disconnected
        canvasRuntime.stop()
    }

    private companion object {
        const val BATCH_DELAY_MS = 16L
        const val PENDING_PERSISTENCE_DELAY_MS = 250L
        const val MAX_OPERATIONS_PER_BATCH = 64
    }
}
