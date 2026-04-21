package com.subhajit.mulberry.sync

import com.subhajit.mulberry.core.config.AppConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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

    data class ServerOperation(val operation: ServerCanvasOperation) : CanvasSyncMessage
    data class Error(val message: String) : CanvasSyncMessage
    data object Closed : CanvasSyncMessage
}

interface CanvasSyncClient {
    val messages: SharedFlow<CanvasSyncMessage>

    fun connect(accessToken: String, pairSessionId: String, lastAppliedServerRevision: Long)
    fun send(operation: CanvasSyncOperation)
    fun disconnect()
}

@Singleton
class OkHttpCanvasSyncClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val appConfig: AppConfig
) : CanvasSyncClient {
    private val _messages = MutableSharedFlow<CanvasSyncMessage>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val messages: SharedFlow<CanvasSyncMessage> = _messages

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
                    _messages.tryEmit(message)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _messages.tryEmit(CanvasSyncMessage.Error(t.message ?: "Sync connection failed"))
                    _messages.tryEmit(CanvasSyncMessage.Closed)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _messages.tryEmit(CanvasSyncMessage.Closed)
                }
            }
        )
    }

    override fun send(operation: CanvasSyncOperation) {
        webSocket?.send(operation.toWireJson())
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
        "SERVER_OP" -> CanvasSyncMessage.ServerOperation(
            parseServerOperation(raw).operation.toDomainOperation()
        )
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
