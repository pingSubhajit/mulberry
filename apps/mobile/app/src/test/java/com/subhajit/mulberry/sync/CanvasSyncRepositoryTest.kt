@file:Suppress("OPT_IN_USAGE", "OPT_IN_USAGE_ERROR")

package com.subhajit.mulberry.sync

import com.subhajit.mulberry.canvas.CanvasRenderState
import com.subhajit.mulberry.canvas.CanvasRuntime
import com.subhajit.mulberry.canvas.CanvasRuntimeEvent
import com.subhajit.mulberry.data.bootstrap.AppSession
import com.subhajit.mulberry.data.bootstrap.AuthStatus
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapState
import com.subhajit.mulberry.network.AcceptInviteResponse
import com.subhajit.mulberry.network.AuthResponse
import com.subhajit.mulberry.network.BootstrapResponse
import com.subhajit.mulberry.network.CanvasOperationBatchRequest
import com.subhajit.mulberry.network.CanvasOpsResponse
import com.subhajit.mulberry.network.CanvasPointPayload
import com.subhajit.mulberry.network.CanvasSnapshotPayload
import com.subhajit.mulberry.network.CanvasSnapshotResponse
import com.subhajit.mulberry.network.CreateInviteResponse
import com.subhajit.mulberry.network.DebugActionResponse
import com.subhajit.mulberry.network.DeviceTokenResponse
import com.subhajit.mulberry.network.DisplayNameRequest
import com.subhajit.mulberry.network.GoogleAuthRequest
import com.subhajit.mulberry.network.MulberryApiService
import com.subhajit.mulberry.network.PartnerProfileRequest
import com.subhajit.mulberry.network.ProfileRequest
import com.subhajit.mulberry.network.RefreshRequest
import com.subhajit.mulberry.network.RedeemInviteRequest
import com.subhajit.mulberry.network.RedeemInviteResponse
import com.subhajit.mulberry.network.RegisterFcmTokenRequest
import com.subhajit.mulberry.network.UnregisterFcmTokenRequest
import com.subhajit.mulberry.network.WallpaperCatalogResponse
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

    private fun repository(
        scope: TestScope,
        client: FakeCanvasSyncClient,
        outbox: FakeCanvasSyncOutboxStore = FakeCanvasSyncOutboxStore(mutableListOf()),
        initialPairSessionId: String? = null
    ): RepositoryFixture {
        val bootstrapRepository = FakeSessionBootstrapRepository()
        val metadataRepository = FakeSyncMetadataRepository(initialPairSessionId)
        val runtime = FakeCanvasRuntime()
        val apiService = FakeMulberryApiService()
        val applicationScope = CoroutineScope(
            StandardTestDispatcher(scope.testScheduler) + SupervisorJob()
        )
        val repository = DefaultCanvasSyncRepository(
            client = client,
            sessionBootstrapRepository = bootstrapRepository,
            syncMetadataRepository = metadataRepository,
            syncOutboxStore = outbox,
            canvasRuntime = runtime,
            apiService = apiService,
            recoveryPolicy = CanvasRecoveryPolicy(),
            applicationScope = applicationScope
        )
        return RepositoryFixture(repository, applicationScope)
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

    override suspend fun seedDemoSession() = Unit

    override suspend fun reset() {
        state.value = SessionBootstrapState()
        session.value = null
    }
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

private class FakeCanvasRuntime : CanvasRuntime {
    private val render = MutableStateFlow(CanvasRenderState())

    override val renderState: StateFlow<CanvasRenderState> = render
    override val outboundOperations: Flow<CanvasSyncOperation> = emptyFlow()

    override fun start(pairSessionId: String, userId: String) = Unit

    override fun stop() = Unit

    override fun reset() {
        render.value = CanvasRenderState()
    }

    override fun submit(event: CanvasRuntimeEvent) = Unit

    override suspend fun submitAndAwait(event: CanvasRuntimeEvent) = Unit

    override fun setSyncState(syncState: SyncState) {
        render.update { it.copy(syncState = syncState) }
    }
}

private class FakeMulberryApiService : MulberryApiService {
    override suspend fun authenticateWithGoogle(request: GoogleAuthRequest): AuthResponse =
        error("unused")

    override suspend fun refreshSession(request: RefreshRequest): AuthResponse = error("unused")

    override suspend fun logout() = error("unused")

    override suspend fun registerFcmToken(request: RegisterFcmTokenRequest): DeviceTokenResponse =
        error("unused")

    override suspend fun unregisterFcmToken(request: UnregisterFcmTokenRequest) = error("unused")

    override suspend fun getBootstrap(): BootstrapResponse = error("unused")

    override suspend fun updateProfile(request: ProfileRequest): BootstrapResponse = error("unused")

    override suspend fun updateDisplayName(request: DisplayNameRequest): BootstrapResponse =
        error("unused")

    override suspend fun updateProfilePhoto(image: MultipartBody.Part): BootstrapResponse =
        error("unused")

    override suspend fun updatePartnerProfile(request: PartnerProfileRequest): BootstrapResponse =
        error("unused")

    override suspend fun updatePartnerProfilePhoto(image: MultipartBody.Part): BootstrapResponse =
        error("unused")

    override suspend fun createInvite(): CreateInviteResponse = error("unused")

    override suspend fun redeemInvite(request: RedeemInviteRequest): RedeemInviteResponse =
        error("unused")

    override suspend fun acceptInvite(inviteId: String): AcceptInviteResponse = error("unused")

    override suspend fun declineInvite(inviteId: String): BootstrapResponse = error("unused")

    override suspend fun disconnectPairing(): BootstrapResponse = error("unused")

    override suspend fun sendDebugPairingConfirmationPush(): DebugActionResponse = error("unused")

    override suspend fun getCanvasOperations(afterRevision: Long): CanvasOpsResponse =
        CanvasOpsResponse(operations = emptyList())

    override suspend fun postCanvasOperationBatch(request: CanvasOperationBatchRequest): CanvasOpsResponse =
        error("unused")

    override suspend fun getCanvasSnapshot(): CanvasSnapshotResponse = CanvasSnapshotResponse(
        pairSessionId = "pair-1",
        revision = 0L,
        snapshotRevision = 0L,
        latestRevision = 0L,
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
    )

    override suspend fun getWallpapers(cursor: String?, limit: Int): WallpaperCatalogResponse =
        WallpaperCatalogResponse(items = emptyList(), nextCursor = null)
}
