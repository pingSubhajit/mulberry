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

interface CanvasSyncClient {
    val messages: Flow<CanvasSyncMessage>

    fun connect(accessToken: String, pairSessionId: String, lastAppliedServerRevision: Long)
    fun send(operation: CanvasSyncOperation)
    fun sendBatch(batchId: String, operations: List<CanvasSyncOperation>)
    fun disconnect()
}

@Singleton
class OkHttpCanvasSyncClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val appConfig: AppConfig
) : CanvasSyncClient {
    private val messagesChannel = Channel<CanvasSyncMessage>(Channel.UNLIMITED)
    override val messages: Flow<CanvasSyncMessage> = messagesChannel.receiveAsFlow()

    private var webSocket: WebSocket? = null

    override fun connect(
        accessToken: String,
        pairSessionId: String,
        lastAppliedServerRevision: Long
    ) {
        disconnect()
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
                    val message = runCatching { parseWireMessage(text) }
                        .getOrElse { CanvasSyncMessage.Error("Invalid sync message") }
                    messagesChannel.trySend(message)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    messagesChannel.trySend(
                        CanvasSyncMessage.Error(t.message ?: "Sync connection failed")
                    )
                    messagesChannel.trySend(CanvasSyncMessage.Closed)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    messagesChannel.trySend(CanvasSyncMessage.Closed)
                }
            }
        )
    }

    override fun send(operation: CanvasSyncOperation) {
        webSocket?.send(operation.toWireJson())
    }

    override fun sendBatch(batchId: String, operations: List<CanvasSyncOperation>) {
        if (operations.isEmpty()) return
        webSocket?.send(operations.toBatchWireJson(batchId))
    }

    override fun disconnect() {
        webSocket?.close(1000, "disconnect")
        webSocket = null
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
}
