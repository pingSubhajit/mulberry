@file:Suppress("OPT_IN_USAGE", "OPT_IN_USAGE_ERROR")

package com.subhajit.mulberry.sync

import com.subhajit.mulberry.analytics.AnalyticsEventsRequest
import com.subhajit.mulberry.canvas.CanvasRenderState
import com.subhajit.mulberry.canvas.CanvasRuntime
import com.subhajit.mulberry.canvas.CanvasRuntimeEvent
import com.subhajit.mulberry.data.bootstrap.AppSession
import com.subhajit.mulberry.data.bootstrap.AuthStatus
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.PartnerWallpaperStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapState
import com.subhajit.mulberry.drawing.model.DrawingOperationType
import com.subhajit.mulberry.network.AcceptInviteResponse
import com.subhajit.mulberry.network.AuthResponse
import com.subhajit.mulberry.network.BootstrapResponse
import com.subhajit.mulberry.network.CanvasOperationBatchRequest
import com.subhajit.mulberry.network.CanvasOpsResponse
import com.subhajit.mulberry.network.CanvasPointPayload
import com.subhajit.mulberry.network.CanvasSnapshotPayload
import com.subhajit.mulberry.network.CanvasSnapshotResponse
import com.subhajit.mulberry.network.CannySsoTokenResponse
import com.subhajit.mulberry.network.ClearStreakActivityDayRequest
import com.subhajit.mulberry.network.CreateInviteResponse
import com.subhajit.mulberry.network.DebugActionResponse
import com.subhajit.mulberry.network.DeviceTokenResponse
import com.subhajit.mulberry.network.DisplayNameRequest
import com.subhajit.mulberry.network.GoogleAuthRequest
import com.subhajit.mulberry.network.LostStreakSeenRequest
import com.subhajit.mulberry.network.MulberryApiService
import com.subhajit.mulberry.network.OkResponse
import com.subhajit.mulberry.network.PartnerProfileRequest
import com.subhajit.mulberry.network.ProfileRequest
import com.subhajit.mulberry.network.RefreshRequest
import com.subhajit.mulberry.network.UpdateCanvasStrokeRenderModeRequest
import okhttp3.ResponseBody
import retrofit2.Response
import com.subhajit.mulberry.network.RedeemInviteRequest
import com.subhajit.mulberry.network.RedeemInviteResponse
import com.subhajit.mulberry.network.RegisterFcmTokenRequest
import com.subhajit.mulberry.network.ReactionConfirmRequest
import com.subhajit.mulberry.network.ReactionConfirmResponse
import com.subhajit.mulberry.network.ReactionLeaseRequest
import com.subhajit.mulberry.network.ReactionLeaseResponse
import com.subhajit.mulberry.network.ReactionSendResponse
import com.subhajit.mulberry.network.SendReactionRequest
import com.subhajit.mulberry.network.StickerAssetUrlResponse
import com.subhajit.mulberry.network.StickerPackDetailResponse
import com.subhajit.mulberry.network.StickerPackListResponse
import com.subhajit.mulberry.network.StreakResponse
import com.subhajit.mulberry.network.UnregisterFcmTokenRequest
import com.subhajit.mulberry.network.UpdateWallpaperStatusRequest
import com.subhajit.mulberry.network.WallpaperCatalogResponse
import com.subhajit.mulberry.network.WhatsNewListResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MultipartBody
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import com.google.gson.JsonObject
import com.subhajit.mulberry.network.CanvasOperationEnvelopeResponse
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasSyncRepositoryTest {
    @Test
    fun startupNormalizesInFlightOutboxBeforeConnect() = runTest {
        val events = mutableListOf<String>()
        val client = FakeCanvasSyncClient(events)
        val outbox = FakeCanvasSyncOutboxStore(events).apply {
            seedInFlight(operation("startup-op"), batchId = "persisted-batch")
        }
        val fixture = repository(
            scope = this,
            client = client,
            outbox = outbox,
            initialPairSessionId = "pair-1"
        )
        val repository = fixture.repository

        repository.start()
        advanceUntilIdle()

        assertEquals(listOf(1L), client.connectGenerations)
        val firstResetIndex = events.indexOfFirst { it == "resetInFlightToPending" }
        val firstConnectIndex = events.indexOfFirst { it == "connect:1" }
        assertTrue(firstResetIndex >= 0)
        assertTrue(firstConnectIndex > firstResetIndex)
        fixture.close()
    }

    @Test
    fun staleClosedFromPreviousGenerationIsIgnoredAfterReconnect() = runTest {
        val events = mutableListOf<String>()
        val client = FakeCanvasSyncClient(events)
        val fixture = repository(scope = this, client = client)
        val repository = fixture.repository

        repository.start()
        advanceUntilIdle()
        client.emit(1L, CanvasSyncMessage.Ready(latestRevision = 0, missedOperations = emptyList()))
        advanceUntilIdle()

        client.emit(1L, CanvasSyncMessage.Error("socket failed"))
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), client.connectGenerations)

        client.emit(2L, CanvasSyncMessage.Ready(latestRevision = 0, missedOperations = emptyList()))
        advanceUntilIdle()
        client.emit(1L, CanvasSyncMessage.Closed)
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), client.connectGenerations)
        assertTrue(repository.syncState.value is SyncState.Connected)
        fixture.close()
    }

    @Test
    fun staleAckBatchFromPreviousGenerationIsIgnoredAndCurrentConnectionResends() = runTest {
        val events = mutableListOf<String>()
        val client = FakeCanvasSyncClient(events)
        val outbox = FakeCanvasSyncOutboxStore(events).apply {
            seedPending(operation("pending-op"))
        }
        val fixture = repository(
            scope = this,
            client = client,
            outbox = outbox,
            initialPairSessionId = "pair-1"
        )
        val repository = fixture.repository

        repository.start()
        advanceUntilIdle()
        client.emit(1L, CanvasSyncMessage.Ready(latestRevision = 0, missedOperations = emptyList()))
        advanceTimeBy(16)
        advanceUntilIdle()
        assertTrue("expected a sent batch, events=$events", client.sentBatchIds.isNotEmpty())
        val firstBatchId = client.sentBatchIds.single()

        client.emit(1L, CanvasSyncMessage.Error("disconnect"))
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), client.connectGenerations)

        client.emit(
            1L,
            CanvasSyncMessage.AckBatch(
                batchId = firstBatchId,
                ackedClientOperationIds = listOf("pending-op"),
                ackedThroughRevision = 1L,
                operations = emptyList()
            )
        )
        advanceUntilIdle()
        assertTrue(outbox.containsOperation("pending-op"))

        client.emit(2L, CanvasSyncMessage.Ready(latestRevision = 0, missedOperations = emptyList()))
        advanceTimeBy(16)
        advanceUntilIdle()

        assertEquals(2, client.sentBatchIds.size)
        assertTrue(outbox.isInFlight("pending-op"))
        fixture.close()
    }

    @Test
    fun inflightWithoutBatchTriggersImmediateRepairAndReconnect() = runTest {
        val events = mutableListOf<String>()
        val client = FakeCanvasSyncClient(events)
        val outbox = FakeCanvasSyncOutboxStore(events).apply {
            seedPending(operation("stuck-op"))
        }
        val fixture = repository(
            scope = this,
            client = client,
            outbox = outbox,
            initialPairSessionId = "pair-1"
        )
        val repository = fixture.repository

        repository.start()
        advanceUntilIdle()
        client.emit(1L, CanvasSyncMessage.Ready(latestRevision = 0, missedOperations = emptyList()))
        advanceTimeBy(16)
        advanceUntilIdle()
        assertTrue("expected in-flight operation, events=$events", outbox.isInFlight("stuck-op"))

        client.emit(1L, CanvasSyncMessage.Error("disconnect"))
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), client.connectGenerations)

        outbox.forceInFlight("stuck-op")
        client.emit(2L, CanvasSyncMessage.Ready(latestRevision = 0, missedOperations = emptyList()))
        advanceUntilIdle()
        repository.queueLocalOperation(operation("follow-up-op"))
        advanceTimeBy(16)
        advanceUntilIdle()

        assertEquals("events=$events", listOf(1L, 2L, 3L), client.connectGenerations)
        assertTrue(outbox.isPending("stuck-op"))
        fixture.close()
    }

    @Test
    fun ackBatchDoesNotTriggerFalseInflightRepairWhileAcknowledgeIsInProgress() = runTest {
        val events = mutableListOf<String>()
        val client = FakeCanvasSyncClient(events)
        val outbox = FakeCanvasSyncOutboxStore(events).apply {
            seedPending(operation("first-op"))
        }
        val fixture = repository(
            scope = this,
            client = client,
            outbox = outbox,
            initialPairSessionId = "pair-1"
        )
        val repository = fixture.repository

        repository.start()
        advanceUntilIdle()
        client.emit(1L, CanvasSyncMessage.Ready(latestRevision = 0, missedOperations = emptyList()))
        advanceTimeBy(16)
        advanceUntilIdle()
        val firstBatchId = client.sentBatchIds.single()

        val acknowledgeGate = CompletableDeferred<Unit>()
        outbox.pauseNextAcknowledgeUntil(acknowledgeGate)
        client.emit(
            1L,
            CanvasSyncMessage.AckBatch(
                batchId = firstBatchId,
                ackedClientOperationIds = listOf("first-op"),
                ackedThroughRevision = 1L,
                operations = emptyList()
            )
        )
        runCurrent()

        launch {
            repository.queueLocalOperation(operation("second-op"))
        }
        runCurrent()
        advanceTimeBy(16)
        runCurrent()

        assertEquals(listOf(1L), client.connectGenerations)
        assertTrue(outbox.isPending("second-op"))

        acknowledgeGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(1L), client.connectGenerations)
        assertEquals(2, client.sentBatchIds.size)
        assertTrue(outbox.isInFlight("second-op"))
        fixture.close()
    }

    @Test
    fun skipsUnknownServerOperationsAndKeepsAdvancingTheAppliedRevision() = runTest {
        val client = FakeCanvasSyncClient(mutableListOf())
        val fixture = repository(scope = this, client = client, initialPairSessionId = "pair-1")

        fixture.repository.start()
        advanceUntilIdle()
        client.emit(1L, CanvasSyncMessage.Ready(latestRevision = 0, missedOperations = emptyList()))
        advanceUntilIdle()

        // A partner on a newer app adds a photo between known operations, and ends on one.
        client.emit(
            1L,
            CanvasSyncMessage.ServerOperationBatch(
                listOf(
                    serverOperation(revision = 1, type = "CLEAR_CANVAS"),
                    serverOperation(revision = 2, type = "ADD_PHOTO_ELEMENT"),
                    serverOperation(revision = 3, type = "UPDATE_PHOTO_ELEMENT")
                )
            )
        )
        advanceUntilIdle()
        assertEquals(3L, fixture.metadata.metadata.first().lastAppliedServerRevision)

        client.emit(1L, CanvasSyncMessage.ServerOperation(serverOperation(revision = 4, type = "CLEAR_CANVAS")))
        advanceUntilIdle()

        assertEquals(4L, fixture.metadata.metadata.first().lastAppliedServerRevision)
        assertEquals(emptyList<Long>(), fixture.api.canvasOperationRequests)
        assertEquals(listOf(1L, 2L, 3L, 4L), fixture.runtime.remoteOperations.map { it.serverRevision })
        assertEquals(
            listOf(null, "ADD_PHOTO_ELEMENT", "UPDATE_PHOTO_ELEMENT", null),
            fixture.runtime.remoteOperations.map { (it.payload as? SyncOperationPayload.Unsupported)?.type }
        )
        assertTrue(fixture.repository.syncState.value is SyncState.Connected)
        fixture.close()
    }

    @Test
    fun appliesTheSameAccountsLiveOperationsFromAnotherDevice() = runTest {
        val client = FakeCanvasSyncClient(mutableListOf())
        val fixture = repository(scope = this, client = client, initialPairSessionId = "pair-1")

        fixture.repository.start()
        advanceUntilIdle()
        client.emit(1L, CanvasSyncMessage.Ready(latestRevision = 0, missedOperations = emptyList()))
        advanceUntilIdle()

        // The same account clears on its iPhone, then draws and erases there.
        client.emit(
            1L,
            CanvasSyncMessage.ServerOperation(
                accountOperation(1, "iphone-op-1", DrawingOperationType.CLEAR_CANVAS)
            )
        )
        advanceUntilIdle()
        client.emit(
            1L,
            CanvasSyncMessage.ServerOperationBatch(
                listOf(
                    accountOperation(2, "iphone-op-2", DrawingOperationType.FINISH_STROKE),
                    accountOperation(3, "iphone-op-3", DrawingOperationType.DELETE_STROKE)
                )
            )
        )
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L, 3L), fixture.runtime.remoteOperations.map { it.serverRevision })
        assertEquals(
            DrawingOperationType.CLEAR_CANVAS,
            fixture.runtime.remoteOperations.first().type
        )
        assertEquals(3L, fixture.metadata.metadata.first().lastAppliedServerRevision)
        fixture.close()
    }

    @Test
    fun appliesTheSameAccountsMissedOperationsOnReadyButNotThisDevicesOwn() = runTest {
        val client = FakeCanvasSyncClient(mutableListOf())
        val operationIds = establishedOperationIds()
        val fixture = repository(
            scope = this,
            client = client,
            initialPairSessionId = "pair-1",
            operationIds = operationIds
        )

        fixture.repository.start()
        advanceUntilIdle()
        client.emit(
            1L,
            CanvasSyncMessage.Ready(
                latestRevision = 4,
                missedOperations = listOf(
                    accountOperation(1, "iphone-op-1", DrawingOperationType.CLEAR_CANVAS),
                    accountOperation(2, operationIds.newClientOperationId(), DrawingOperationType.FINISH_STROKE),
                    accountOperation(3, "iphone-op-3", DrawingOperationType.FINISH_STROKE),
                    accountOperation(4, "partner-op-4", DrawingOperationType.DELETE_STROKE, actorUserId = "partner-1")
                )
            )
        )
        advanceUntilIdle()

        assertEquals(0, fixture.api.snapshotRequests)
        assertEquals(listOf(1L, 3L, 4L), fixture.runtime.remoteOperations.map { it.serverRevision })
        assertEquals(4L, fixture.metadata.metadata.first().lastAppliedServerRevision)
        assertTrue(fixture.repository.syncState.value is SyncState.Connected)
        fixture.close()
    }

    @Test
    fun doesNotApplyThisDevicesOwnEchoedOperationsAgain() = runTest {
        val client = FakeCanvasSyncClient(mutableListOf())
        val operationIds = establishedOperationIds()
        val outbox = FakeCanvasSyncOutboxStore(mutableListOf())
        val clearId = operationIds.newClientOperationId()
        val strokeId = operationIds.newClientOperationId()
        outbox.seedInFlight(operation(clearId), batchId = "batch-1")
        outbox.seedInFlight(operation(strokeId), batchId = "batch-2")
        val fixture = repository(
            scope = this,
            client = client,
            outbox = outbox,
            initialPairSessionId = "pair-1",
            operationIds = operationIds
        )

        fixture.repository.start()
        advanceUntilIdle()
        client.emit(1L, CanvasSyncMessage.Ready(latestRevision = 0, missedOperations = emptyList()))
        advanceUntilIdle()

        // A CLEAR_CANVAS echoed after this device drew again would wipe the new drawing.
        client.emit(
            1L,
            CanvasSyncMessage.Ack(
                clientOperationId = clearId,
                serverRevision = 1,
                operation = accountOperation(1, clearId, DrawingOperationType.CLEAR_CANVAS)
            )
        )
        client.emit(
            1L,
            CanvasSyncMessage.AckBatch(
                batchId = "batch-2",
                ackedClientOperationIds = listOf(strokeId),
                ackedThroughRevision = 2,
                operations = listOf(accountOperation(2, strokeId, DrawingOperationType.FINISH_STROKE))
            )
        )
        client.emit(
            1L,
            CanvasSyncMessage.ServerOperation(
                accountOperation(3, "iphone-op-3", DrawingOperationType.FINISH_STROKE)
            )
        )
        advanceUntilIdle()

        assertEquals(listOf(3L), fixture.runtime.remoteOperations.map { it.serverRevision })
        assertEquals(3L, fixture.metadata.metadata.first().lastAppliedServerRevision)
        assertTrue(!outbox.containsOperation(clearId) && !outbox.containsOperation(strokeId))
        fixture.close()
    }

    @Test
    fun recognisesItsOwnOperationsInAReplayAfterARestart() = runTest {
        val lineageFile = newLineageFile()
        val beforeRestart = establishedOperationIds(lineageFile)
        val ownStroke = beforeRestart.newClientOperationId()
        val ownClear = beforeRestart.newClientOperationId()
        val ownErase = beforeRestart.newClientOperationId()

        // A new process reads the same lineage back from disk.
        val afterRestart = FileLocalCanvasOperationIds(lineageFile)
        val client = FakeCanvasSyncClient(mutableListOf())
        val fixture = repository(
            scope = this,
            client = client,
            initialPairSessionId = "pair-1",
            operationIds = afterRestart
        )

        fixture.repository.start()
        advanceUntilIdle()
        client.emit(
            1L,
            CanvasSyncMessage.Ready(
                latestRevision = 5,
                missedOperations = listOf(
                    accountOperation(1, ownStroke, DrawingOperationType.FINISH_STROKE),
                    accountOperation(2, ownClear, DrawingOperationType.CLEAR_CANVAS),
                    accountOperation(3, "iphone-op-3", DrawingOperationType.FINISH_STROKE),
                    accountOperation(4, ownErase, DrawingOperationType.DELETE_STROKE),
                    accountOperation(5, "partner-op-5", DrawingOperationType.FINISH_STROKE, actorUserId = "partner-1")
                )
            )
        )
        advanceUntilIdle()

        assertTrue(!afterRestart.needsCanvasReload)
        assertEquals(0, fixture.api.snapshotRequests)
        assertEquals(listOf(3L, 5L), fixture.runtime.remoteOperations.map { it.serverRevision })
        assertEquals(5L, fixture.metadata.metadata.first().lastAppliedServerRevision)
        fixture.close()
    }

    @Test
    fun reloadsTheCanvasOnceToRecoverOperationsAnEarlierBuildSkipped() = runTest {
        val client = FakeCanvasSyncClient(mutableListOf())
        // A lineage file that doesn't exist yet: the first launch after updating.
        val lineageFile = newLineageFile()
        val operationIds = FileLocalCanvasOperationIds(lineageFile)
        assertTrue(operationIds.needsCanvasReload)
        val drawnBeforeReload = operationIds.newClientOperationId()
        val fixture = repository(
            scope = this,
            client = client,
            initialPairSessionId = "pair-1",
            operationIds = operationIds
        )
        // The earlier build moved its cursor past the iPhone's operations without applying them.
        fixture.metadata.setLastAppliedServerRevision(4)
        fixture.api.snapshotRevision = 2
        fixture.api.latestRevision = 4
        fixture.api.serverOperations = listOf(
            accountEnvelope(3, "iphone-op-3", DrawingOperationType.FINISH_STROKE),
            accountEnvelope(4, drawnBeforeReload, DrawingOperationType.DELETE_STROKE)
        )

        fixture.repository.start()
        advanceUntilIdle()
        client.emit(1L, CanvasSyncMessage.Ready(latestRevision = 4, missedOperations = emptyList()))
        advanceUntilIdle()

        assertEquals(listOf(2L), fixture.runtime.snapshotRevisions)
        assertEquals(listOf(2L), fixture.api.canvasOperationRequests)
        // The snapshot replaced what this device drew too, so its own operations apply again.
        assertEquals(listOf(3L, 4L), fixture.runtime.remoteOperations.map { it.serverRevision })
        assertEquals(4L, fixture.metadata.metadata.first().lastAppliedServerRevision)
        assertTrue(!operationIds.needsCanvasReload)
        assertTrue(!FileLocalCanvasOperationIds(lineageFile).needsCanvasReload)

        // Reconnecting doesn't reload again.
        client.emit(1L, CanvasSyncMessage.Error("socket failed"))
        advanceUntilIdle()
        client.emit(2L, CanvasSyncMessage.Ready(latestRevision = 4, missedOperations = emptyList()))
        advanceUntilIdle()
        assertEquals(1, fixture.api.snapshotRequests)
        fixture.close()
    }

    /** An operation from the signed-in account, `user-1`, made on any of its devices. */
    private fun accountEnvelope(
        revision: Long,
        clientOperationId: String,
        type: DrawingOperationType,
        actorUserId: String = "user-1"
    ): CanvasOperationEnvelopeResponse = CanvasOperationEnvelopeResponse(
        clientOperationId = clientOperationId,
        actorUserId = actorUserId,
        pairSessionId = "pair-1",
        type = type.name,
        strokeId = if (type == DrawingOperationType.CLEAR_CANVAS) null else "stroke-$revision",
        payload = JsonObject(),
        clientCreatedAt = "2026-11-05T00:00:00.000Z",
        serverRevision = revision,
        createdAt = "2026-11-05T00:00:01.000Z"
    )

    private fun accountOperation(
        revision: Long,
        clientOperationId: String,
        type: DrawingOperationType,
        actorUserId: String = "user-1"
    ): ServerCanvasOperation =
        accountEnvelope(revision, clientOperationId, type, actorUserId).toDomainOperation()

    private fun serverOperation(revision: Long, type: String): ServerCanvasOperation =
        CanvasOperationEnvelopeResponse(
            clientOperationId = "partner-op-$revision",
            actorUserId = "partner-1",
            pairSessionId = "pair-1",
            type = type,
            strokeId = "element-$revision",
            payload = JsonObject(),
            clientCreatedAt = "2026-11-05T00:00:00.000Z",
            serverRevision = revision,
            createdAt = "2026-11-05T00:00:01.000Z"
        ).toDomainOperation()

    private fun repository(
        scope: TestScope,
        client: FakeCanvasSyncClient,
        outbox: FakeCanvasSyncOutboxStore = FakeCanvasSyncOutboxStore(mutableListOf()),
        initialPairSessionId: String? = null,
        operationIds: LocalCanvasOperationIds = establishedOperationIds()
    ): RepositoryFixture {
        val sessionBootstrapRepository = FakeSessionBootstrapRepository()
        val bootstrapRepository = FakeBootstrapRepository(sessionBootstrapRepository)
        val metadataRepository = FakeSyncMetadataRepository(initialPairSessionId)
        val runtime = FakeCanvasRuntime(operationIds)
        val apiService = FakeMulberryApiService()
        val applicationScope = CoroutineScope(
            StandardTestDispatcher(scope.testScheduler) + SupervisorJob()
        )
        val repository = DefaultCanvasSyncRepository(
            client = client,
            sessionBootstrapRepository = sessionBootstrapRepository,
            bootstrapRepository = bootstrapRepository,
            syncMetadataRepository = metadataRepository,
            syncOutboxStore = outbox,
            canvasRuntime = runtime,
            apiService = apiService,
            recoveryPolicy = CanvasRecoveryPolicy(),
            operationIds = operationIds,
            applicationScope = applicationScope
        )
        return RepositoryFixture(repository, metadataRepository, runtime, apiService, applicationScope)
    }

    private fun operation(id: String): CanvasSyncOperation = CanvasSyncOperation(
        clientOperationId = id,
        type = com.subhajit.mulberry.drawing.model.DrawingOperationType.CLEAR_CANVAS,
        strokeId = null,
        payload = SyncOperationPayload.ClearCanvas,
        clientCreatedAt = "2026-04-24T00:00:00.000Z"
    )
}

private data class RepositoryFixture(
    val repository: DefaultCanvasSyncRepository,
    val metadata: FakeSyncMetadataRepository,
    val runtime: FakeCanvasRuntime,
    val api: FakeMulberryApiService,
    private val applicationScope: CoroutineScope
) {
    fun close() {
        applicationScope.cancel()
    }
}

private class FakeCanvasSyncClient(
    private val events: MutableList<String>
) : CanvasSyncClient {
    private val _messages = MutableSharedFlow<ConnectionScopedSyncMessage>(extraBufferCapacity = 32)
    override val messages: Flow<ConnectionScopedSyncMessage> = _messages

    val connectGenerations = mutableListOf<Long>()
    val sentBatchIds = mutableListOf<String>()

    private var nextGeneration = 0L

    override fun connect(
        accessToken: String,
        pairSessionId: String,
        lastAppliedServerRevision: Long
    ): Long {
        val generation = ++nextGeneration
        connectGenerations += generation
        events += "connect:$generation"
        return generation
    }

    override fun send(operation: CanvasSyncOperation): CanvasSendResult = CanvasSendResult.Accepted(0)

    override fun sendBatch(
        batchId: String,
        operations: List<CanvasSyncOperation>
    ): CanvasSendResult {
        sentBatchIds += batchId
        events += "sendBatch:$batchId:${operations.joinToString(",") { it.clientOperationId }}"
        return CanvasSendResult.Accepted(0)
    }

    override fun disconnect() {
        events += "disconnect"
    }

    fun emit(generation: Long, message: CanvasSyncMessage) {
        _messages.tryEmit(ConnectionScopedSyncMessage(generation, message))
    }
}

private class FakeCanvasSyncOutboxStore(
    private val events: MutableList<String>
) : CanvasSyncOutboxStore {
    private enum class Status {
        PENDING,
        IN_FLIGHT
    }

    private data class Entry(
        val operation: CanvasSyncOperation,
        var status: Status,
        var batchId: String? = null,
        var sentAt: Long = 0L
    )

    private val entries = linkedMapOf<String, Entry>()
    private var skipResetInFlightToPending = 0
    private var acknowledgeGate: CompletableDeferred<Unit>? = null

    override suspend fun migrateLegacyPendingOperations() {
        events += "migrateLegacyPendingOperations"
    }

    override suspend fun enqueue(operation: CanvasSyncOperation) {
        events += "enqueue:${operation.clientOperationId}"
        entries[operation.clientOperationId] = Entry(operation, Status.PENDING)
    }

    override suspend fun nextBatch(maxOperations: Int, maxPayloadBytes: Int): List<CanvasSyncOperation> =
        entries.values
            .filter { it.status == Status.PENDING }
            .take(maxOperations)
            .map { it.operation }

    override suspend fun markInFlight(
        operations: List<CanvasSyncOperation>,
        batchId: String,
        sentAt: Long
    ) {
        events += "markInFlight:$batchId"
        operations.forEach { operation ->
            entries[operation.clientOperationId]?.apply {
                status = Status.IN_FLIGHT
                this.batchId = batchId
                this.sentAt = sentAt
            }
        }
    }

    override suspend fun acknowledge(clientOperationIds: List<String>) {
        acknowledgeGate?.await()
        acknowledgeGate = null
        events += "acknowledge:${clientOperationIds.joinToString(",")}"
        clientOperationIds.forEach(entries::remove)
    }

    override suspend fun resetInFlightToPending() {
        if (skipResetInFlightToPending > 0) {
            skipResetInFlightToPending -= 1
            events += "resetInFlightToPending:skipped"
            return
        }
        events += "resetInFlightToPending"
        entries.values
            .filter { it.status == Status.IN_FLIGHT }
            .forEach {
                it.status = Status.PENDING
                it.batchId = null
                it.sentAt = 0L
            }
    }

    override suspend fun resetStaleInFlightToPending(staleBefore: Long): Int {
        val staleEntries = entries.values.filter {
            it.status == Status.IN_FLIGHT && it.sentAt < staleBefore
        }
        staleEntries.forEach {
            it.status = Status.PENDING
            it.batchId = null
            it.sentAt = 0L
        }
        if (staleEntries.isNotEmpty()) {
            events += "resetStaleInFlightToPending:${staleEntries.size}"
        }
        return staleEntries.size
    }

    override suspend fun hasPendingOperations(): Boolean = entries.isNotEmpty()

    override suspend fun summary(): SyncOutboxSummary = SyncOutboxSummary(
        pending = entries.values.count { it.status == Status.PENDING },
        inFlight = entries.values.count { it.status == Status.IN_FLIGHT }
    )

    override suspend fun clear() {
        events += "clear"
        entries.clear()
    }

    fun seedPending(operation: CanvasSyncOperation) {
        entries[operation.clientOperationId] = Entry(operation, Status.PENDING)
    }

    fun seedInFlight(operation: CanvasSyncOperation, batchId: String) {
        entries[operation.clientOperationId] = Entry(
            operation = operation,
            status = Status.IN_FLIGHT,
            batchId = batchId,
            sentAt = System.currentTimeMillis()
        )
    }

    fun skipNextResetInFlightToPending() {
        skipResetInFlightToPending += 1
    }

    fun pauseNextAcknowledgeUntil(gate: CompletableDeferred<Unit>) {
        acknowledgeGate = gate
    }

    fun forceInFlight(clientOperationId: String) {
        entries[clientOperationId]?.apply {
            status = Status.IN_FLIGHT
            batchId = "forced-batch"
            sentAt = System.currentTimeMillis()
        }
    }

    fun containsOperation(clientOperationId: String): Boolean = entries.containsKey(clientOperationId)

    fun isInFlight(clientOperationId: String): Boolean =
        entries[clientOperationId]?.status == Status.IN_FLIGHT

    fun isPending(clientOperationId: String): Boolean =
        entries[clientOperationId]?.status == Status.PENDING
}

private class FakeSessionBootstrapRepository : SessionBootstrapRepository {
    override val state = MutableStateFlow(
        SessionBootstrapState(
            authStatus = AuthStatus.SIGNED_IN,
            pairingStatus = PairingStatus.PAIRED,
            pairSessionId = "pair-1",
            userId = "user-1"
        )
    )
    override val session = MutableStateFlow<AppSession?>(
        AppSession(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            userId = "user-1"
        )
    )

    override suspend fun getCurrentSession(): AppSession? = session.value

    override suspend fun cacheBootstrap(state: SessionBootstrapState) {
        this.state.value = state
    }

    override suspend fun cacheSession(session: AppSession?) {
        this.session.value = session
    }

    override suspend fun setWallpaperConfigured(configured: Boolean) {
        state.update { it.copy(hasWallpaperConfigured = configured) }
    }

    override suspend fun setPartnerWallpaperStatus(status: PartnerWallpaperStatus?) {
        state.update { it.copy(partnerWallpaperStatus = status) }
    }

    override suspend fun setAnniversaryDate(pairSessionId: String, anniversaryDate: String): Boolean {
        if (state.value.pairSessionId != pairSessionId) return false
        state.update { it.copy(anniversaryDate = anniversaryDate) }
        return true
    }

    override suspend fun endPair(pairSessionId: String): Boolean {
        if (state.value.pairSessionId != pairSessionId) return false
        state.update { it.copy(pairSessionId = null, anniversaryDate = null) }
        return true
    }

    override suspend fun hasShownBrushToolGuide(userId: String): Boolean = false

    override suspend fun markBrushToolGuideShown(userId: String) = Unit

    override suspend fun setStreakLevelUpBanner(streakDays: Int, shownAtMs: Long, expiresAtMs: Long) = Unit

    override suspend fun dismissStreakLevelUpBanner(dismissedAtMs: Long) = Unit

    override suspend fun clearStreakLevelUpBanner() = Unit

    override suspend fun seedDemoSession() = Unit

    override suspend fun reset() {
        state.value = SessionBootstrapState()
        session.value = null
    }
}

private class FakeBootstrapRepository(
    private val sessionBootstrapRepository: FakeSessionBootstrapRepository
) : com.subhajit.mulberry.bootstrap.BootstrapRepository {
    override val cachedState: Flow<SessionBootstrapState> = sessionBootstrapRepository.state

    override suspend fun refreshBootstrap(): Result<SessionBootstrapState> =
        Result.success(sessionBootstrapRepository.state.value)
}

private class FakeSyncMetadataRepository(
    initialPairSessionId: String? = null
) : SyncMetadataRepository {
    private val state = MutableStateFlow(SyncMetadata(pairSessionId = initialPairSessionId))

    override val metadata: Flow<SyncMetadata> = state

    override suspend fun setLastAppliedServerRevision(revision: Long) {
        state.update { it.copy(lastAppliedServerRevision = revision) }
    }

    override suspend fun resetForPairSession(pairSessionId: String) {
        state.value = SyncMetadata(pairSessionId = pairSessionId)
    }

    override suspend fun setLastError(message: String?) {
        state.update { it.copy(lastError = message) }
    }

    override suspend fun setPendingOperations(operations: List<CanvasSyncOperation>) {
        state.update { it.copy(pendingOperations = operations) }
    }

    override suspend fun reset() {
        state.value = SyncMetadata()
    }
}

/** Starts a new lineage on a snapshot, as [com.subhajit.mulberry.canvas.DefaultCanvasRuntime] does. */
private class FakeCanvasRuntime(
    private val operationIds: LocalCanvasOperationIds
) : CanvasRuntime {
    private val render = MutableStateFlow(CanvasRenderState())

    override val renderState: StateFlow<CanvasRenderState> = render
    override val outboundOperations: Flow<CanvasSyncOperation> = emptyFlow()

    override fun start(pairSessionId: String, userId: String) = Unit

    override fun stop() = Unit

    override fun reset() {
        render.value = CanvasRenderState()
    }

    override fun submit(event: CanvasRuntimeEvent) = Unit

    /** Server operations handed to the canvas, in the order they arrived. */
    val remoteOperations = mutableListOf<ServerCanvasOperation>()

    /** The revision of each snapshot the canvas was replaced with. */
    val snapshotRevisions = mutableListOf<Long>()

    override suspend fun submitAndAwait(event: CanvasRuntimeEvent) {
        when (event) {
            is CanvasRuntimeEvent.RemoteBatch -> remoteOperations += event.operations
            is CanvasRuntimeEvent.RecoveryOperations -> remoteOperations += event.operations
            is CanvasRuntimeEvent.RecoverySnapshot -> {
                operationIds.startNewLineage()
                snapshotRevisions += event.serverRevision
                render.update {
                    it.copy(committedStrokes = event.strokes, committedElements = event.elements)
                }
            }
            else -> Unit
        }
    }

    override fun setSyncState(syncState: SyncState) {
        render.update { it.copy(syncState = syncState) }
    }
}

private class FakeMulberryApiService : MulberryApiService {
    override suspend fun authenticateWithGoogle(request: GoogleAuthRequest): AuthResponse =
        error("unused")

    override suspend fun authenticateWithApple(
        request: com.subhajit.mulberry.network.AppleAuthRequest
    ): AuthResponse = error("unused")

    override suspend fun linkGoogle(request: GoogleAuthRequest): BootstrapResponse = error("unused")

    override suspend fun linkApple(
        request: com.subhajit.mulberry.network.AppleAuthRequest
    ): BootstrapResponse = error("unused")

    override suspend fun deleteAccount(): retrofit2.Response<Unit> = error("unused")

    override suspend fun refreshSession(request: RefreshRequest): AuthResponse = error("unused")

    override suspend fun logout() = error("unused")

    override suspend fun registerFcmToken(request: RegisterFcmTokenRequest): DeviceTokenResponse =
        error("unused")

    override suspend fun unregisterFcmToken(request: UnregisterFcmTokenRequest) = error("unused")

    override suspend fun getBootstrap(): BootstrapResponse = error("unused")

    override suspend fun getStreak(today: String): StreakResponse = error("unused")

    override suspend fun markLostStreakSeen(request: LostStreakSeenRequest): OkResponse = error("unused")

    override suspend fun updateProfile(request: ProfileRequest): BootstrapResponse = error("unused")

    override suspend fun updateDisplayName(request: DisplayNameRequest): BootstrapResponse =
        error("unused")

    override suspend fun updateProfilePhoto(image: MultipartBody.Part): BootstrapResponse =
        error("unused")

    override suspend fun updatePartnerProfile(request: PartnerProfileRequest): BootstrapResponse =
        error("unused")

    override suspend fun updatePartnerProfileWithPhoto(
        partnerDisplayName: okhttp3.RequestBody,
        anniversaryDate: okhttp3.RequestBody,
        image: MultipartBody.Part
    ): BootstrapResponse = error("unused")

    override suspend fun updatePartnerProfilePhoto(image: MultipartBody.Part): BootstrapResponse =
        error("unused")

    override suspend fun updateCanvasStrokeRenderMode(request: UpdateCanvasStrokeRenderModeRequest): BootstrapResponse =
        error("unused")

    override suspend fun updateWallpaperStatus(request: UpdateWallpaperStatusRequest): DebugActionResponse =
        error("unused")

    override suspend fun createInvite(): CreateInviteResponse = error("unused")

    override suspend fun redeemInvite(request: RedeemInviteRequest): RedeemInviteResponse =
        error("unused")

    override suspend fun acceptInvite(inviteId: String): AcceptInviteResponse = error("unused")

    override suspend fun declineInvite(inviteId: String): BootstrapResponse = error("unused")

    override suspend fun disconnectPairing(): BootstrapResponse = error("unused")

    override suspend fun sendDebugPairingConfirmationPush(): DebugActionResponse = error("unused")

    override suspend fun sendDebugPairingDisconnectedPush(): DebugActionResponse = error("unused")

    override suspend fun clearStreakActivityDay(request: ClearStreakActivityDayRequest): DebugActionResponse =
        error("unused")

    /** Each `afterRevision` asked for; a request means sync saw a gap and went to recover. */
    val canvasOperationRequests = mutableListOf<Long>()

    /** Every operation the server holds; each request gets those after its revision. */
    var serverOperations: List<CanvasOperationEnvelopeResponse> = emptyList()

    override suspend fun getCanvasOperations(afterRevision: Long): CanvasOpsResponse {
        canvasOperationRequests += afterRevision
        return CanvasOpsResponse(
            operations = serverOperations.filter { it.serverRevision > afterRevision }
        )
    }

    override suspend fun postCanvasOperationBatch(request: CanvasOperationBatchRequest): CanvasOpsResponse =
        error("unused")

    var snapshotRevision = 0L
    var latestRevision = 0L
    var snapshotRequests = 0
        private set

    override suspend fun uploadCanvasPhoto(image: okhttp3.MultipartBody.Part): com.subhajit.mulberry.network.CanvasPhotoUploadResponse =
        error("Not used")

    override suspend fun getCanvasPhotoUrl(photoId: String): com.subhajit.mulberry.network.CanvasPhotoUrlResponse =
        error("Not used")

    override suspend fun getCanvasSnapshot(): CanvasSnapshotResponse = CanvasSnapshotResponse(
        pairSessionId = "pair-1",
        revision = snapshotRevision,
        snapshotRevision = snapshotRevision,
        latestRevision = latestRevision,
        snapshot = CanvasSnapshotPayload(
            strokes = listOf(
                com.subhajit.mulberry.network.CanvasSnapshotStroke(
                    id = "snapshot-stroke",
                    colorArgb = 0xff000000,
                    width = 4f,
                    createdAt = 1L,
                    points = listOf(CanvasPointPayload(0f, 0f))
                )
            )
        ),
        updatedAt = null
    ).also { snapshotRequests += 1 }

    override suspend fun getWallpapers(cursor: String?, limit: Int): WallpaperCatalogResponse =
        WallpaperCatalogResponse(items = emptyList(), nextCursor = null)

    override suspend fun getStickerPacks(): StickerPackListResponse = error("unused")

    override suspend fun getStickerPackDetail(packKey: String, version: Int?): StickerPackDetailResponse =
        error("unused")

    override suspend fun getStickerAssetUrl(
        packKey: String,
        version: Int,
        stickerId: String,
        variant: String
    ): StickerAssetUrlResponse = error("unused")

    override suspend fun sendReaction(request: SendReactionRequest): ReactionSendResponse = error("unused")

    override suspend fun leaseReactionPlayback(request: ReactionLeaseRequest): ReactionLeaseResponse = error("unused")

    override suspend fun confirmReactionPlayback(request: ReactionConfirmRequest): ReactionConfirmResponse =
        error("unused")

    override suspend fun getWhatsNewMarkdown(version: String): Response<ResponseBody> = error("unused")

    override suspend fun getLatestWhatsNewMarkdown(): Response<ResponseBody> = error("unused")

    override suspend fun getWhatsNewEntries(
        cursor: String?,
        limit: Int
    ): WhatsNewListResponse = error("unused")

    override suspend fun getCannySsoToken(): CannySsoTokenResponse = error("unused")

    override suspend fun sendAnalyticsEvents(request: AnalyticsEventsRequest): Response<ResponseBody> =
        error("unused")
}
