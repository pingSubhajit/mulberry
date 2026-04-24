package com.subhajit.mulberry.sync

import com.subhajit.mulberry.core.config.AppConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface CanvasSyncMessage {
    data class Ready(
        val latestRevision: Long,
        val missedOperations: List<ServerCanvasOperation>
    ) : CanvasSyncMessage

    data class Ack(
        val clientOperationId: String,
        val serverRevision: Long,
        val operation: ServerCanvasOperation?
    ) : CanvasSyncMessage

    data class AckBatch(
        val batchId: String,
        val ackedClientOperationIds: List<String>,
        val ackedThroughRevision: Long,
        val operations: List<ServerCanvasOperation>
    ) : CanvasSyncMessage

    data class ServerOperation(val operation: ServerCanvasOperation) : CanvasSyncMessage
    data class ServerOperationBatch(val operations: List<ServerCanvasOperation>) : CanvasSyncMessage
    data class FlowControl(val mode: String, val maxAppendHz: Int, val reason: String?) : CanvasSyncMessage
    data object ResyncRequired : CanvasSyncMessage
    data class Error(val message: String) : CanvasSyncMessage
    data object Closed : CanvasSyncMessage
}

sealed interface CanvasSendResult {
    data class Accepted(val queueSizeBytes: Long) : CanvasSendResult
    data object Rejected : CanvasSendResult
}

data class ConnectionScopedSyncMessage(
    val generation: Long,
    val message: CanvasSyncMessage
)

interface CanvasSyncClient {
    val messages: Flow<ConnectionScopedSyncMessage>

    fun connect(accessToken: String, pairSessionId: String, lastAppliedServerRevision: Long): Long
    fun send(operation: CanvasSyncOperation): CanvasSendResult
    fun sendBatch(batchId: String, operations: List<CanvasSyncOperation>): CanvasSendResult
    fun disconnect()
}

@Singleton
class OkHttpCanvasSyncClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val appConfig: AppConfig
) : CanvasSyncClient {
    private val messagesChannel = Channel<ConnectionScopedSyncMessage>(capacity = Channel.UNLIMITED)
    override val messages: Flow<ConnectionScopedSyncMessage> = messagesChannel.receiveAsFlow()

    private var webSocket: WebSocket? = null
    private var connectionGeneration = 0L

    override fun connect(
        accessToken: String,
        pairSessionId: String,
        lastAppliedServerRevision: Long
    ): Long {
        disconnect()
        val generation = ++connectionGeneration
        val request = Request.Builder()
            .url(appConfig.apiBaseUrl.toWebSocketUrl())
            .build()
        webSocket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(
                        helloJson(
                            accessToken = accessToken,
                            pairSessionId = pairSessionId,
                            lastAppliedServerRevision = lastAppliedServerRevision
                        )
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (generation != connectionGeneration) return
                    val message = runCatching { parseWireMessage(text) }
                        .getOrElse { CanvasSyncMessage.Error("Invalid sync message") }
                    enqueueMessage(generation, message)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (generation != connectionGeneration) return
                    enqueueMessage(
                        generation,
                        CanvasSyncMessage.Error(t.message ?: "Sync connection failed")
                    )
                    enqueueMessage(generation, CanvasSyncMessage.Closed)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (generation != connectionGeneration) return
                    enqueueMessage(generation, CanvasSyncMessage.Closed)
                }
            }
        )
        return generation
    }

    override fun send(operation: CanvasSyncOperation): CanvasSendResult {
        val socket = webSocket ?: return CanvasSendResult.Rejected
        if (socket.queueSize() > MAX_QUEUED_BYTES) return CanvasSendResult.Rejected
        return if (socket.send(operation.toWireJson())) {
            CanvasSendResult.Accepted(socket.queueSize())
        } else {
            CanvasSendResult.Rejected
        }
    }

    override fun sendBatch(
        batchId: String,
        operations: List<CanvasSyncOperation>
    ): CanvasSendResult {
        if (operations.isEmpty()) return CanvasSendResult.Rejected
        val socket = webSocket ?: return CanvasSendResult.Rejected
        if (socket.queueSize() > MAX_QUEUED_BYTES) return CanvasSendResult.Rejected
        return if (socket.send(operations.toBatchWireJson(batchId))) {
            CanvasSendResult.Accepted(socket.queueSize())
        } else {
            CanvasSendResult.Rejected
        }
    }

    override fun disconnect() {
        connectionGeneration++
        webSocket?.close(1000, "disconnect")
        webSocket = null
    }

    private fun enqueueMessage(generation: Long, message: CanvasSyncMessage) {
        messagesChannel.trySend(ConnectionScopedSyncMessage(generation, message))
    }

    private fun parseWireMessage(raw: String): CanvasSyncMessage = when (parseMessageType(raw)) {
        "READY" -> {
            val ready = parseReady(raw)
            CanvasSyncMessage.Ready(
                latestRevision = ready.latestRevision,
                missedOperations = ready.missedOperations.map { it.toDomainOperation() }
            )
        }
        "ACK" -> {
            val ack = parseAck(raw)
            CanvasSyncMessage.Ack(
                clientOperationId = ack.clientOperationId,
                serverRevision = ack.serverRevision,
                operation = ack.operation?.toDomainOperation()
            )
        }
        "ACK_BATCH" -> {
            val ack = parseAckBatch(raw)
            CanvasSyncMessage.AckBatch(
                batchId = ack.batchId,
                ackedClientOperationIds = ack.ackedClientOperationIds,
                ackedThroughRevision = ack.ackedThroughRevision,
                operations = ack.operations.map { it.toDomainOperation() }
            )
        }
        "SERVER_OP" -> CanvasSyncMessage.ServerOperation(
            parseServerOperation(raw).operation.toDomainOperation()
        )
        "SERVER_OP_BATCH" -> CanvasSyncMessage.ServerOperationBatch(
            parseServerOperationBatch(raw).operations.map { it.toDomainOperation() }
        )
        "FLOW_CONTROL" -> {
            val flowControl = parseFlowControl(raw)
            CanvasSyncMessage.FlowControl(
                mode = flowControl.mode,
                maxAppendHz = flowControl.maxAppendHz,
                reason = flowControl.reason
            )
        }
        "RESYNC_REQUIRED" -> CanvasSyncMessage.ResyncRequired
        "ERROR" -> CanvasSyncMessage.Error(parseError(raw).message)
        else -> CanvasSyncMessage.Error("Unsupported sync message")
    }

    private fun String.toWebSocketUrl(): String {
        val base = trimEnd('/')
        return when {
            base.startsWith("https://") -> base.replaceFirst("https://", "wss://")
            base.startsWith("http://") -> base.replaceFirst("http://", "ws://")
            else -> base
        } + "/canvas/sync"
    }

    private companion object {
        const val MAX_QUEUED_BYTES = 512L * 1024L
    }
}
