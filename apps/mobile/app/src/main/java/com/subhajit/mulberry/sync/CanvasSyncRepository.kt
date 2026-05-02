package com.subhajit.mulberry.sync

import android.util.Log
import com.subhajit.mulberry.canvas.CanvasRuntime
import com.subhajit.mulberry.canvas.CanvasRuntimeEvent
import com.subhajit.mulberry.canvas.FlowControlMode
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.drawing.geometry.containsLegacyGeometry
import com.subhajit.mulberry.drawing.geometry.hasLegacyGeometry
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.drawing.model.DrawingOperationType
import com.subhajit.mulberry.drawing.model.CanvasElement
import com.subhajit.mulberry.drawing.model.CanvasStickerElement
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.drawing.model.CanvasTextAlign
import com.subhajit.mulberry.drawing.model.CanvasTextElement
import com.subhajit.mulberry.drawing.model.CanvasTextFont
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
    private val syncOutboxStore: CanvasSyncOutboxStore,
    private val canvasRuntime: CanvasRuntime,
    private val apiService: MulberryApiService,
    private val recoveryPolicy: CanvasRecoveryPolicy,
    @com.subhajit.mulberry.app.di.ApplicationScope private val applicationScope: CoroutineScope
) : CanvasSyncRepository {
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Disconnected)
    override val syncState: StateFlow<SyncState> = _syncState

    private val syncEnabled = MutableStateFlow(false)
    private val connectionMutex = Mutex()
    private val messageMutex = Mutex()
    private val sendMutex = Mutex()
    private val revisionBuffer = TreeMap<Long, ServerCanvasOperation>()
    private val inFlightBatchIds = mutableSetOf<String>()
    private var isRecoveringGap = false
    private var sendScheduled = false
    private var syncStorageInitialized = false
    private var started = false
    private var activeConnectionGeneration: Long? = null
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
        applicationScope.launch {
            connectionMutex.withLock {
                disconnectActiveSocket(reason = "app_stop")
            }
        }
    }

    private fun startCollectors() {
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
                handleSyncDemand(enabled, bootstrap, session)
            }
        }

        applicationScope.launch {
            client.messages.collect { scopedMessage ->
                messageMutex.withLock {
                    handleConnectionScopedMessage(scopedMessage)
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
        syncMetadataRepository.reset()
        syncOutboxStore.clear()
        canvasRuntime.reset()
        connectionMutex.withLock {
            disconnectActiveSocket(reason = "reset")
        }
    }

    private suspend fun handleSyncDemand(
        enabled: Boolean,
        bootstrap: com.subhajit.mulberry.data.bootstrap.SessionBootstrapState,
        session: com.subhajit.mulberry.data.bootstrap.AppSession?
    ) {
        if (
            enabled &&
            session != null &&
            bootstrap.pairingStatus == PairingStatus.PAIRED &&
            bootstrap.pairSessionId != null
        ) {
            establishConnection(
                session = session,
                pairSessionId = bootstrap.pairSessionId,
                reason = "state_change",
                forceReconnect = false,
                restartRuntime = activeConnectionGeneration == null ||
                    activeAccessToken != session.accessToken ||
                    activePairSessionId != bootstrap.pairSessionId ||
                    syncState.value is SyncState.Disconnected
            )
        } else {
            connectionMutex.withLock {
                disconnectActiveSocket(reason = "state_change")
            }
        }
    }

    private suspend fun handleConnectionScopedMessage(scopedMessage: ConnectionScopedSyncMessage) {
        val activeGeneration = activeConnectionGeneration
        if (activeGeneration == null || scopedMessage.generation != activeGeneration) {
            Log.i(
                TAG,
                "Ignoring stale canvas sync message " +
                    "type=${scopedMessage.message.logLabel()} " +
                    "generation=${scopedMessage.generation} activeGeneration=$activeGeneration"
            )
            return
        }
        handleMessage(scopedMessage.message, scopedMessage.generation)
    }

    private suspend fun handleMessage(message: CanvasSyncMessage, generation: Long) {
        when (message) {
            is CanvasSyncMessage.Ready -> handleReady(message)
            is CanvasSyncMessage.Ack -> {
                Log.i(
                    TAG,
                    "Received canvas sync ack generation=$generation " +
                        "clientOperationId=${message.clientOperationId} serverRevision=${message.serverRevision}"
                )
                syncOutboxStore.acknowledge(listOf(message.clientOperationId))
                message.operation?.let { enqueueAndDrain(listOf(it)) }
                schedulePendingSend()
            }
            is CanvasSyncMessage.AckBatch -> {
                handleAckBatch(message, generation)
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
                reconnectFromCurrentState(
                    reason = "socket_error",
                    errorMessage = message.message,
                    restartRuntime = false
                )
            }
            CanvasSyncMessage.Closed -> {
                reconnectFromCurrentState(
                    reason = "socket_closed",
                    errorMessage = null,
                    restartRuntime = false
                )
            }
        }
    }

    private suspend fun handleAckBatch(
        message: CanvasSyncMessage.AckBatch,
        generation: Long
    ) {
        Log.i(
            TAG,
            "Received canvas sync batch ack generation=$generation " +
                "batchId=${message.batchId} opCount=${message.ackedClientOperationIds.size} " +
                "ackedThroughRevision=${message.ackedThroughRevision}"
        )
        sendMutex.withLock {
            inFlightBatchIds.remove(message.batchId)
            syncOutboxStore.acknowledge(message.ackedClientOperationIds)
        }
        enqueueAndDrain(message.operations)
        schedulePendingSend()
    }

    private suspend fun handleReady(message: CanvasSyncMessage.Ready) {
        _syncState.value = SyncState.Recovering
        canvasRuntime.setSyncState(SyncState.Recovering)
        if (message.missedOperations.any { operation -> operation.hasLegacyGeometry() }) {
            clearLegacyCanvasAndQueueReset(reason = "ready_legacy_geometry")
            _syncState.value = SyncState.Connected
            canvasRuntime.setSyncState(SyncState.Connected)
            schedulePendingSend()
            syncMetadataRepository.setLastError(null)
            return
        }
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

    private suspend fun reconnectIfStillPaired(reason: String, restartRuntime: Boolean) {
        if (!syncEnabled.value) return
        val bootstrap = sessionBootstrapRepository.state.first()
        val session = sessionBootstrapRepository.session.first()
        if (
            session != null &&
            bootstrap.pairingStatus == PairingStatus.PAIRED &&
            bootstrap.pairSessionId != null
        ) {
            establishConnection(
                session = session,
                pairSessionId = bootstrap.pairSessionId,
                reason = reason,
                forceReconnect = true,
                restartRuntime = restartRuntime
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
            if (operations.any { operation -> operation.hasLegacyGeometry() }) {
                clearLegacyCanvasAndQueueReset(reason = "recovery_legacy_geometry")
                _syncState.value = SyncState.Connected
                canvasRuntime.setSyncState(SyncState.Connected)
                syncMetadataRepository.setLastError(null)
                return@onSuccess
            }
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
        var reconnectReason: String? = null
        var shouldRetryRejectedSend = false
        sendMutex.withLock {
            sendScheduled = false
            if (syncState.value !is SyncState.Connected) return@withLock
            val outboxSummary = syncOutboxStore.summary()
            if (outboxSummary.inFlight > 0 || inFlightBatchIds.size >= MAX_IN_FLIGHT_BATCHES) {
                val resetCount = syncOutboxStore.resetStaleInFlightToPending(
                    staleBefore = System.currentTimeMillis() - ACK_TIMEOUT_MS
                )
                if (resetCount > 0) {
                    Log.w(
                        TAG,
                        "Repairing canvas sync state reason=stale_inflight_timeout count=$resetCount"
                    )
                    inFlightBatchIds.clear()
                    reconnectReason = "stale_inflight_timeout"
                } else if (inFlightBatchIds.isEmpty()) {
                    Log.w(
                        TAG,
                        "Repairing canvas sync state reason=inflight_without_batch " +
                            "count=${outboxSummary.inFlight}"
                    )
                    syncOutboxStore.resetInFlightToPending()
                    reconnectReason = "inflight_without_batch"
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
                    Log.i(
                        TAG,
                        "Sent canvas sync batch generation=$activeConnectionGeneration " +
                            "batchId=$batchId opCount=${unsent.size} queueSizeBytes=${result.queueSizeBytes}"
                    )
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
                    shouldRetryRejectedSend = true
                }
            }
        }
        reconnectReason?.let { reason ->
            reconnectIfStillPaired(
                reason = reason,
                restartRuntime = false
            )
            return
        }
        if (shouldRetryRejectedSend) {
            delay(REJECTED_SEND_RETRY_DELAY_MS)
            schedulePendingSend()
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
        isRecoveringGap = false
        resetSendTracking()
        syncOutboxStore.clear()
        syncMetadataRepository.resetForPairSession(pairSessionId)
        canvasRuntime.submitAndAwait(
            CanvasRuntimeEvent.RecoverySnapshot(
                strokes = emptyList(),
                elements = emptyList(),
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
        if (tail.any { operation -> operation.hasLegacyGeometry() }) {
            clearLegacyCanvasAndQueueReset(reason = "tail_legacy_geometry")
            return
        }
        if (tail.isNotEmpty()) {
            applyRecoveryOperationsAtomically(tail)
        }
    }

    private suspend fun recoverFromSnapshotAndTail() {
        val snapshot = apiService.getCanvasSnapshot()
        if (!canReplaceFromSnapshot()) return
        val strokes = snapshot.snapshot.toDomainStrokes()
        val elements = snapshot.snapshot.toDomainElements()
        if (strokes.containsLegacyGeometry()) {
            clearLegacyCanvasAndQueueReset(reason = "snapshot_legacy_geometry")
            return
        }
        canvasRuntime.submitAndAwait(
            CanvasRuntimeEvent.RecoverySnapshot(
                strokes = strokes,
                elements = elements,
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
        if (operations.any { operation -> operation.hasLegacyGeometry() }) {
            clearLegacyCanvasAndQueueReset(reason = "stream_legacy_geometry")
            return
        }
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

    private suspend fun clearLegacyCanvasAndQueueReset(reason: String) {
        Log.w(TAG, "Clearing legacy pixel-space canvas data reason=$reason")
        revisionBuffer.clear()
        syncOutboxStore.clear()
        resetSendTracking()
        canvasRuntime.submitAndAwait(
            CanvasRuntimeEvent.RecoverySnapshot(
                strokes = emptyList(),
                elements = emptyList(),
                serverRevision = lastAppliedRevisionCache
            )
        )
        queueLocalOperation(
            newClientOperation(
                type = DrawingOperationType.CLEAR_CANVAS,
                strokeId = null,
                payload = SyncOperationPayload.ClearCanvas
            )
        )
    }

    private suspend fun ensureSyncStorageInitializedLocked() {
        if (syncStorageInitialized) return
        syncOutboxStore.migrateLegacyPendingOperations()
        syncOutboxStore.resetInFlightToPending()
        lastAppliedRevisionCache =
            syncMetadataRepository.metadata.first().lastAppliedServerRevision
        syncStorageInitialized = true
    }

    private suspend fun establishConnection(
        session: com.subhajit.mulberry.data.bootstrap.AppSession,
        pairSessionId: String,
        reason: String,
        forceReconnect: Boolean,
        restartRuntime: Boolean
    ) {
        connectionMutex.withLock {
            ensureSyncStorageInitializedLocked()
            val sameConnection =
                !forceReconnect &&
                    activeAccessToken == session.accessToken &&
                    activePairSessionId == pairSessionId &&
                    activeConnectionGeneration != null &&
                    syncState.value !is SyncState.Disconnected &&
                    syncState.value !is SyncState.Error
            if (sameConnection) return

            if (forceReconnect || activeConnectionGeneration != null) {
                clearConnectionStateLocked(
                    reason = reason,
                    disconnectClient = true,
                    stopRuntime = false,
                    nextState = SyncState.Connecting
                )
            }

            syncOutboxStore.resetInFlightToPending()
            val lastAppliedRevision = preparePairSessionScope(pairSessionId)
            currentUserId = session.userId
            activeAccessToken = session.accessToken
            activePairSessionId = pairSessionId
            lastAppliedRevisionCache = lastAppliedRevision
            if (restartRuntime) {
                canvasRuntime.start(
                    pairSessionId = pairSessionId,
                    userId = session.userId
                )
            }
            _syncState.value = SyncState.Connecting
            canvasRuntime.setSyncState(SyncState.Connecting)
            val generation = client.connect(
                accessToken = session.accessToken,
                pairSessionId = pairSessionId,
                lastAppliedServerRevision = lastAppliedRevision
            )
            activeConnectionGeneration = generation
            Log.i(
                TAG,
                "Connecting canvas sync generation=$generation reason=$reason " +
                    "pairSessionId=$pairSessionId lastAppliedRevision=$lastAppliedRevision " +
                    "restartRuntime=$restartRuntime"
            )
        }
    }

    private suspend fun reconnectFromCurrentState(
        reason: String,
        errorMessage: String?,
        restartRuntime: Boolean
    ) {
        errorMessage?.let { syncMetadataRepository.setLastError(it) }
        if (!syncEnabled.value) {
            if (errorMessage != null) {
                _syncState.value = SyncState.Error(errorMessage)
                canvasRuntime.setSyncState(SyncState.Error(errorMessage))
            }
            return
        }

        val bootstrap = sessionBootstrapRepository.state.first()
        val session = sessionBootstrapRepository.session.first()
        if (
            session == null ||
            bootstrap.pairingStatus != PairingStatus.PAIRED ||
            bootstrap.pairSessionId == null
        ) {
            if (errorMessage != null) {
                _syncState.value = SyncState.Error(errorMessage)
                canvasRuntime.setSyncState(SyncState.Error(errorMessage))
            } else {
                disconnectActiveSocket(reason = reason)
            }
            return
        }

        if (errorMessage != null) {
            Log.w(TAG, "Canvas sync reconnect requested reason=$reason message=$errorMessage")
        } else {
            Log.w(TAG, "Canvas sync reconnect requested reason=$reason")
        }
        establishConnection(
            session = session,
            pairSessionId = bootstrap.pairSessionId,
            reason = reason,
            forceReconnect = true,
            restartRuntime = restartRuntime
        )
    }

    private suspend fun disconnectActiveSocket(reason: String = "disconnect") {
        clearConnectionStateLocked(
            reason = reason,
            disconnectClient = true,
            stopRuntime = true,
            nextState = SyncState.Disconnected
        )
    }

    private suspend fun clearConnectionStateLocked(
        reason: String,
        disconnectClient: Boolean,
        stopRuntime: Boolean,
        nextState: SyncState
    ) {
        val generation = activeConnectionGeneration
        if (generation != null || disconnectClient || stopRuntime) {
            Log.i(
                TAG,
                "Disconnecting canvas sync generation=$generation reason=$reason " +
                    "disconnectClient=$disconnectClient stopRuntime=$stopRuntime"
            )
        }
        activeConnectionGeneration = null
        currentUserId = null
        activeAccessToken = null
        activePairSessionId = null
        revisionBuffer.clear()
        isRecoveringGap = false
        resetSendTracking()
        if (disconnectClient) {
            client.disconnect()
        }
        _syncState.value = nextState
        canvasRuntime.setSyncState(nextState)
        if (stopRuntime) {
            canvasRuntime.stop()
        }
    }

    private suspend fun resetSendTracking() {
        sendMutex.withLock {
            inFlightBatchIds.clear()
            sendScheduled = false
        }
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

private fun CanvasSyncMessage.logLabel(): String = when (this) {
    is CanvasSyncMessage.Ready -> "READY"
    is CanvasSyncMessage.Ack -> "ACK"
    is CanvasSyncMessage.AckBatch -> "ACK_BATCH"
    is CanvasSyncMessage.ServerOperation -> "SERVER_OP"
    is CanvasSyncMessage.ServerOperationBatch -> "SERVER_OP_BATCH"
    is CanvasSyncMessage.FlowControl -> "FLOW_CONTROL"
    CanvasSyncMessage.ResyncRequired -> "RESYNC_REQUIRED"
    is CanvasSyncMessage.Error -> "ERROR"
    CanvasSyncMessage.Closed -> "CLOSED"
}

private fun com.subhajit.mulberry.network.CanvasSnapshotPayload.toDomainStrokes(): List<Stroke> =
    strokes.map { stroke ->
        Stroke(
            id = stroke.id,
            colorArgb = stroke.colorArgb,
            width = stroke.width,
            createdAt = stroke.createdAt,
            points = stroke.points.map { point ->
                StrokePoint(x = point.x, y = point.y)
            }
        )
    }

private fun com.subhajit.mulberry.network.CanvasSnapshotPayload.toDomainElements(): List<CanvasElement> {
    val unified = elements.orEmpty()
    if (unified.isNotEmpty()) {
        return unified.mapNotNull { element ->
            when (element.kind) {
                "TEXT" -> {
                    CanvasTextElement(
                        id = element.id,
                        text = element.text.orEmpty(),
                        createdAt = element.createdAt,
                        center = StrokePoint(x = element.center.x, y = element.center.y),
                        rotationRad = element.rotationRad,
                        scale = element.scale,
                        boxWidth = element.boxWidth ?: 0.7f,
                        colorArgb = element.colorArgb ?: 0xff111111,
                        backgroundPillEnabled = element.backgroundPillEnabled ?: false,
                        font = runCatching { CanvasTextFont.valueOf(element.font ?: "POPPINS") }
                            .getOrElse { CanvasTextFont.POPPINS },
                        alignment = runCatching { CanvasTextAlign.valueOf(element.alignment ?: "CENTER") }
                            .getOrElse { CanvasTextAlign.CENTER }
                    )
                }
                "STICKER" -> {
                    val packKey = element.packKey?.trim().orEmpty()
                    val stickerId = element.stickerId?.trim().orEmpty()
                    val packVersion = element.packVersion ?: 0
                    if (packKey.isBlank() || stickerId.isBlank() || packVersion <= 0) return@mapNotNull null
                    CanvasStickerElement(
                        id = element.id,
                        createdAt = element.createdAt,
                        center = StrokePoint(x = element.center.x, y = element.center.y),
                        rotationRad = element.rotationRad,
                        scale = element.scale,
                        packKey = packKey,
                        packVersion = packVersion,
                        stickerId = stickerId
                    )
                }
                else -> null
            }
        }
    }

    return textElements.map { element ->
        CanvasTextElement(
            id = element.id,
            text = element.text,
            createdAt = element.createdAt,
            center = StrokePoint(x = element.center.x, y = element.center.y),
            rotationRad = element.rotationRad,
            scale = element.scale,
            boxWidth = element.boxWidth,
            colorArgb = element.colorArgb,
            backgroundPillEnabled = element.backgroundPillEnabled,
            font = runCatching { CanvasTextFont.valueOf(element.font) }.getOrElse { CanvasTextFont.POPPINS },
            alignment = runCatching { CanvasTextAlign.valueOf(element.alignment) }.getOrElse { CanvasTextAlign.CENTER }
        )
    }
}
