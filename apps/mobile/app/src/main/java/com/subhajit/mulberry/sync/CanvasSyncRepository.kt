package com.subhajit.mulberry.sync

import android.util.Log
import com.subhajit.mulberry.canvas.CanvasRuntime
import com.subhajit.mulberry.canvas.CanvasRuntimeEvent
import com.subhajit.mulberry.canvas.FlowControlMode
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
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
    private val syncOutboxStore: SyncOutboxStore,
    private val canvasRuntime: CanvasRuntime,
    private val apiService: MulberryApiService,
    private val recoveryPolicy: CanvasRecoveryPolicy,
    @com.subhajit.mulberry.app.di.ApplicationScope private val applicationScope: CoroutineScope
) : CanvasSyncRepository {
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Disconnected)
    override val syncState: StateFlow<SyncState> = _syncState

    private val syncEnabled = MutableStateFlow(false)
    private val messageMutex = Mutex()
    private val sendMutex = Mutex()
    private val revisionBuffer = TreeMap<Long, ServerCanvasOperation>()
    private val inFlightBatchIds = mutableSetOf<String>()
    private var isRecoveringGap = false
    private var sendScheduled = false
    private var started = false
    private var currentUserId: String? = null
    private var activeAccessToken: String? = null
    private var activePairSessionId: String? = null
    private var lastForegroundStoppedAt: Long? = null
    private var lastAppliedRevisionCache = 0L

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
            syncOutboxStore.migrateLegacyPendingOperations()
            syncOutboxStore.resetInFlightToPending()
            lastAppliedRevisionCache =
                syncMetadataRepository.metadata.first().lastAppliedServerRevision
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
                    val lastAppliedRevision = preparePairSessionScope(bootstrap.pairSessionId)
                    canvasRuntime.start(
                        pairSessionId = bootstrap.pairSessionId,
                        userId = session.userId
                    )
                    _syncState.value = SyncState.Connecting
                    canvasRuntime.setSyncState(SyncState.Connecting)
                    lastAppliedRevisionCache = lastAppliedRevision
                    syncOutboxStore.resetInFlightToPending()
                    client.connect(
                        accessToken = session.accessToken,
                        pairSessionId = bootstrap.pairSessionId,
                        lastAppliedServerRevision = lastAppliedRevision
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
        syncOutboxStore.enqueue(operation)
        if (syncState.value is SyncState.Connected) {
            schedulePendingSend()
        }
    }

    override suspend fun reset() {
        syncEnabled.value = false
        revisionBuffer.clear()
        inFlightBatchIds.clear()
        isRecoveringGap = false
        sendScheduled = false
        syncMetadataRepository.reset()
        syncOutboxStore.clear()
        canvasRuntime.reset()
        disconnectActiveSocket()
    }

    private suspend fun handleMessage(message: CanvasSyncMessage) {
        when (message) {
            is CanvasSyncMessage.Ready -> handleReady(message)
            is CanvasSyncMessage.Ack -> {
                syncOutboxStore.acknowledge(listOf(message.clientOperationId))
                message.operation?.let { enqueueAndDrain(listOf(it)) }
                schedulePendingSend()
            }
            is CanvasSyncMessage.AckBatch -> {
                inFlightBatchIds.remove(message.batchId)
                syncOutboxStore.acknowledge(message.ackedClientOperationIds)
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
        val lastApplied = lastAppliedRevisionCache
        val input = CanvasRecoveryInput(
            lastAppliedRevision = lastApplied,
            latestRevision = message.latestRevision,
            missedOperationCount = message.missedOperations.size,
            idleDurationMs = foregroundIdleDurationMs(),
            hasPendingLocalOperations = syncOutboxStore.hasPendingOperations(),
            reason = if (
                message.missedOperations.isEmpty() &&
                message.latestRevision > lastApplied
            ) {
                CanvasRecoveryReason.EMPTY_TAIL_GAP
            } else {
                CanvasRecoveryReason.READY
            }
        )

        val shouldValidateCurrentSnapshot =
            message.latestRevision > 0 &&
                message.latestRevision == lastApplied &&
                canvasRuntime.renderState.value.isEmpty

        if (
            (recoveryPolicy.shouldUseSnapshot(input) || shouldValidateCurrentSnapshot) &&
            canReplaceFromSnapshot()
        ) {
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
            val lastAppliedRevision = preparePairSessionScope(bootstrap.pairSessionId)
            lastAppliedRevisionCache = lastAppliedRevision
            syncOutboxStore.resetInFlightToPending()
            currentUserId = session.userId
            activeAccessToken = session.accessToken
            activePairSessionId = bootstrap.pairSessionId
            client.connect(
                accessToken = session.accessToken,
                pairSessionId = bootstrap.pairSessionId,
                lastAppliedServerRevision = lastAppliedRevision
            )
        }
    }

    private suspend fun recoverFromServer(reason: CanvasRecoveryReason) {
        if (isRecoveringGap) return
        isRecoveringGap = true
        _syncState.value = SyncState.Recovering
        canvasRuntime.setSyncState(SyncState.Recovering)
        runCatching {
            val afterRevision = lastAppliedRevisionCache
            apiService.getCanvasOperations(afterRevision)
                .operations
                .map { it.toDomainOperation() }
        }.onSuccess { operations ->
            val lastApplied = lastAppliedRevisionCache
            val latestRevision = operations.lastOrNull()?.serverRevision ?: lastApplied
            val input = CanvasRecoveryInput(
                lastAppliedRevision = lastApplied,
                latestRevision = latestRevision,
                missedOperationCount = operations.size,
                idleDurationMs = foregroundIdleDurationMs(),
                hasPendingLocalOperations = syncOutboxStore.hasPendingOperations(),
                reason = if (operations.isEmpty() && latestRevision > lastApplied) {
                    CanvasRecoveryReason.EMPTY_TAIL_GAP
                } else {
                    reason
                }
            )
            if (recoveryPolicy.shouldUseSnapshot(input) && canReplaceFromSnapshot()) {
                recoverFromSnapshotAndTail()
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

    private fun scheduleAckTimeoutCheck() {
        applicationScope.launch {
            delay(ACK_TIMEOUT_MS)
            sendPendingBatch()
        }
    }

    private suspend fun sendPendingBatch() {
        sendMutex.withLock {
            sendScheduled = false
            if (syncState.value !is SyncState.Connected) return@withLock
            val outboxSummary = syncOutboxStore.summary()
            if (outboxSummary.inFlight > 0 || inFlightBatchIds.size >= MAX_IN_FLIGHT_BATCHES) {
                val resetCount = syncOutboxStore.resetStaleInFlightToPending(
                    staleBefore = System.currentTimeMillis() - ACK_TIMEOUT_MS
                )
                if (resetCount > 0) {
                    Log.w(TAG, "Reset stale in-flight canvas sync operations count=$resetCount")
                    inFlightBatchIds.clear()
                    reconnectIfStillPaired()
                } else if (inFlightBatchIds.isEmpty()) {
                    Log.w(
                        TAG,
                        "Room outbox has in-flight canvas sync operations without active batch " +
                            "count=${outboxSummary.inFlight}"
                    )
                    scheduleAckTimeoutCheck()
                }
                return@withLock
            }
            val unsent = syncOutboxStore.nextBatch(
                maxOperations = MAX_OPERATIONS_PER_BATCH,
                maxPayloadBytes = MAX_BATCH_PAYLOAD_BYTES
            )
            if (unsent.isEmpty()) return@withLock
            val batchId = UUID.randomUUID().toString()
            when (val result = client.sendBatch(batchId, unsent)) {
                is CanvasSendResult.Accepted -> {
                    syncOutboxStore.markInFlight(unsent, batchId)
                    inFlightBatchIds.add(batchId)
                    scheduleAckTimeoutCheck()
                    if (
                        result.queueSizeBytes < MAX_SOCKET_QUEUE_BYTES &&
                        inFlightBatchIds.size < MAX_IN_FLIGHT_BATCHES &&
                        syncOutboxStore.hasPendingOperations()
                    ) {
                        schedulePendingSend()
                    }
                }
                CanvasSendResult.Rejected -> {
                    delay(REJECTED_SEND_RETRY_DELAY_MS)
                    schedulePendingSend()
                }
            }
        }
    }

    private suspend fun persistLastAppliedRevision(revision: Long) {
        if (revision <= lastAppliedRevisionCache) return
        lastAppliedRevisionCache = revision
        syncMetadataRepository.setLastAppliedServerRevision(revision)
    }

    private suspend fun preparePairSessionScope(pairSessionId: String): Long {
        val metadata = syncMetadataRepository.metadata.first()
        if (metadata.pairSessionId == pairSessionId) {
            return metadata.lastAppliedServerRevision
        }

        Log.i(
            TAG,
            "Switching canvas sync scope " +
                "from=${metadata.pairSessionId ?: "none"} to=$pairSessionId"
        )
        revisionBuffer.clear()
        inFlightBatchIds.clear()
        isRecoveringGap = false
        sendScheduled = false
        syncOutboxStore.clear()
        syncMetadataRepository.resetForPairSession(pairSessionId)
        canvasRuntime.submitAndAwait(
            CanvasRuntimeEvent.RecoverySnapshot(
                strokes = emptyList(),
                serverRevision = 0L
            )
        )
        return 0L
    }

    private suspend fun applyServerBatchBeforeAdvancing(operations: List<ServerCanvasOperation>) {
        val userId = currentUserId
        val remoteOperations = operations.filterNot { it.actorUserId == userId }
        if (remoteOperations.isNotEmpty()) {
            canvasRuntime.submitAndAwait(CanvasRuntimeEvent.RemoteBatch(remoteOperations))
        }
        persistLastAppliedRevision(operations.last().serverRevision)
    }

    private suspend fun fetchAndApplyTailFrom(revision: Long) {
        val tail = apiService.getCanvasOperations(revision)
            .operations
            .map { it.toDomainOperation() }
            .filter { it.serverRevision > revision }
            .sortedBy { it.serverRevision }
        if (tail.isNotEmpty()) {
            applyRecoveryOperationsAtomically(tail)
        }
    }

    private suspend fun recoverFromSnapshotAndTail() {
        val snapshot = apiService.getCanvasSnapshot()
        if (!canReplaceFromSnapshot()) return
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
                serverRevision = snapshot.snapshotRevision
            )
        )
        persistLastAppliedRevision(snapshot.snapshotRevision)
        revisionBuffer.clear()
        if (snapshot.latestRevision > snapshot.snapshotRevision) {
            fetchAndApplyTailFrom(snapshot.snapshotRevision)
        }
    }

    private suspend fun enqueueAndDrain(
        operations: List<ServerCanvasOperation>,
        allowRecovery: Boolean = true
    ) {
        val lastApplied = lastAppliedRevisionCache
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
        val readyOperations = mutableListOf<ServerCanvasOperation>()
        while (true) {
            val expectedNext = lastAppliedRevisionCache + readyOperations.size + 1
            val operation = revisionBuffer.remove(expectedNext) ?: break
            readyOperations += operation
        }
        if (readyOperations.isNotEmpty()) {
            applyServerBatchBeforeAdvancing(readyOperations)
        }
    }

    private suspend fun recoverIfGapRemains(allowRecovery: Boolean) {
        if (!allowRecovery || revisionBuffer.isEmpty()) return
        val expectedNext = lastAppliedRevisionCache + 1
        if (revisionBuffer.firstKey() > expectedNext) {
            recoverFromServer(CanvasRecoveryReason.REVISION_GAP)
        }
    }

    private suspend fun recoverFromSnapshot() {
        if (!canReplaceFromSnapshot()) return
        recoverFromSnapshotAndTail()
    }

    private suspend fun canReplaceFromSnapshot(): Boolean =
        !syncOutboxStore.hasPendingOperations() &&
            canvasRuntime.renderState.value.localActiveStroke == null

    private suspend fun applyRecoveryOperationsAtomically(operations: List<ServerCanvasOperation>) {
        val ordered = operations
            .filter { it.serverRevision > lastAppliedRevisionCache }
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
        persistLastAppliedRevision(ordered.last().serverRevision)
    }

    private fun foregroundIdleDurationMs(): Long =
        lastForegroundStoppedAt?.let { stoppedAt ->
            (System.currentTimeMillis() - stoppedAt).coerceAtLeast(0L)
        } ?: 0L

    private fun disconnectActiveSocket() {
        currentUserId = null
        activeAccessToken = null
        activePairSessionId = null
        revisionBuffer.clear()
        inFlightBatchIds.clear()
        isRecoveringGap = false
        sendScheduled = false
        client.disconnect()
        _syncState.value = SyncState.Disconnected
        canvasRuntime.stop()
    }

    private companion object {
        const val BATCH_DELAY_MS = 16L
        const val MAX_OPERATIONS_PER_BATCH = 32
        const val MAX_BATCH_PAYLOAD_BYTES = 64 * 1024
        const val MAX_IN_FLIGHT_BATCHES = 1
        const val MAX_SOCKET_QUEUE_BYTES = 512L * 1024L
        const val REJECTED_SEND_RETRY_DELAY_MS = 250L
        const val ACK_TIMEOUT_MS = 8_000L
        const val TAG = "MulberrySync"
    }
}
