package com.subhajit.mulberry.sync

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.subhajit.mulberry.drawing.model.CanvasTextAlign
import com.subhajit.mulberry.drawing.model.CanvasTextFont
import com.subhajit.mulberry.drawing.model.DrawingOperationType
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.network.CanvasOperationEnvelopeResponse
import com.subhajit.mulberry.network.ClientCanvasOperationRequest
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

private val gson = Gson()

data class ClientOperationMessage(
    val type: String = "CLIENT_OP",
    val operation: ClientOperationBody
)

data class ClientOperationBatchMessage(
    val type: String = "CLIENT_OP_BATCH",
    val batchId: String,
    val operations: List<ClientOperationBody>,
    val clientCreatedAt: String
)

data class ClientOperationBody(
    val clientOperationId: String,
    val type: String,
    val strokeId: String?,
    val payload: JsonObject,
    val clientCreatedAt: String,
    val clientLocalDate: String? = null
)

data class HelloMessage(
    val type: String = "HELLO",
    val accessToken: String,
    val pairSessionId: String,
    val lastAppliedServerRevision: Long
)

data class ReadyMessage(
    val type: String,
    val pairSessionId: String,
    val userId: String,
    val latestRevision: Long,
    val missedOperations: List<CanvasOperationEnvelopeResponse> = emptyList()
)

data class AckMessage(
    val type: String,
    val clientOperationId: String,
    val serverRevision: Long,
    val operation: CanvasOperationEnvelopeResponse?
)

data class AckBatchMessage(
    val type: String,
    val batchId: String,
    val ackedClientOperationIds: List<String>,
    val ackedThroughRevision: Long,
    val operations: List<CanvasOperationEnvelopeResponse>
)

data class ServerOperationMessage(
    val type: String,
    val operation: CanvasOperationEnvelopeResponse
)

data class ServerOperationBatchMessage(
    val type: String,
    val operations: List<CanvasOperationEnvelopeResponse>
)

data class FlowControlMessage(
    val type: String,
    val mode: String,
    val maxAppendHz: Int,
    val reason: String?
)

data class ErrorMessage(
    val type: String,
    val message: String
)

fun newClientOperation(
    type: DrawingOperationType,
    strokeId: String?,
    payload: SyncOperationPayload
): CanvasSyncOperation = CanvasSyncOperation(
    clientOperationId = UUID.randomUUID().toString(),
    type = type,
    strokeId = strokeId,
    payload = payload,
    clientCreatedAt = nowIsoString()
)

fun CanvasSyncOperation.toWireJson(): String = gson.toJson(
    ClientOperationMessage(
        operation = ClientOperationBody(
            clientOperationId = clientOperationId,
            type = type.name,
            strokeId = strokeId,
            payload = payload.toJsonObject(),
            clientCreatedAt = clientCreatedAt,
            clientLocalDate = clientCreatedAt.toClientLocalDate()
        )
    )
)

fun List<CanvasSyncOperation>.toBatchWireJson(batchId: String): String = gson.toJson(
    ClientOperationBatchMessage(
        batchId = batchId,
        operations = map { operation ->
            ClientOperationBody(
                clientOperationId = operation.clientOperationId,
                type = operation.type.name,
                strokeId = operation.strokeId,
                payload = operation.payload.toJsonObject(),
                clientCreatedAt = operation.clientCreatedAt,
                clientLocalDate = operation.clientCreatedAt.toClientLocalDate()
            )
        },
        clientCreatedAt = nowIsoString()
    )
)

fun CanvasSyncOperation.toClientRequest(): ClientCanvasOperationRequest =
    ClientCanvasOperationRequest(
        clientOperationId = clientOperationId,
        type = type.name,
        strokeId = strokeId,
        payload = payload.toJsonObject(),
        clientCreatedAt = clientCreatedAt,
        clientLocalDate = clientCreatedAt.toClientLocalDate()
    )

fun helloJson(
    accessToken: String,
    pairSessionId: String,
    lastAppliedServerRevision: Long
): String = gson.toJson(
    HelloMessage(
        accessToken = accessToken,
        pairSessionId = pairSessionId,
        lastAppliedServerRevision = lastAppliedServerRevision
    )
)

fun parseMessageType(raw: String): String? =
    runCatching { gson.fromJson(raw, JsonObject::class.java)["type"]?.asString }.getOrNull()

fun parseReady(raw: String): ReadyMessage = gson.fromJson(raw, ReadyMessage::class.java)

fun parseAck(raw: String): AckMessage = gson.fromJson(raw, AckMessage::class.java)

fun parseAckBatch(raw: String): AckBatchMessage = gson.fromJson(raw, AckBatchMessage::class.java)

fun parseServerOperation(raw: String): ServerOperationMessage =
    gson.fromJson(raw, ServerOperationMessage::class.java)

fun parseServerOperationBatch(raw: String): ServerOperationBatchMessage =
    gson.fromJson(raw, ServerOperationBatchMessage::class.java)

fun parseFlowControl(raw: String): FlowControlMessage = gson.fromJson(raw, FlowControlMessage::class.java)

fun parseError(raw: String): ErrorMessage = gson.fromJson(raw, ErrorMessage::class.java)

fun CanvasOperationEnvelopeResponse.toDomainOperation(): ServerCanvasOperation =
    ServerCanvasOperation(
        clientOperationId = clientOperationId,
        actorUserId = actorUserId,
        pairSessionId = pairSessionId,
        type = DrawingOperationType.valueOf(type),
        strokeId = strokeId,
        payload = payload.toSyncPayload(DrawingOperationType.valueOf(type)),
        clientCreatedAt = clientCreatedAt,
        serverRevision = serverRevision,
        createdAt = createdAt
    )

fun SyncOperationPayload.toJsonObject(): JsonObject = when (this) {
    is SyncOperationPayload.AddStroke -> gson.toJsonTree(
        mapOf(
            "id" to id,
            "colorArgb" to colorArgb,
            "width" to width,
            "createdAt" to createdAt,
            "firstPoint" to mapOf("x" to firstPoint.x, "y" to firstPoint.y)
        )
    ).asJsonObject
    is SyncOperationPayload.AppendPoints -> gson.toJsonTree(
        mapOf("points" to points.map { mapOf("x" to it.x, "y" to it.y) })
    ).asJsonObject
    SyncOperationPayload.FinishStroke -> JsonObject()
    SyncOperationPayload.DeleteStroke -> JsonObject()
    SyncOperationPayload.ClearCanvas -> JsonObject()
    is SyncOperationPayload.AddTextElement -> gson.toJsonTree(
        mapOf(
            "id" to id,
            "text" to text,
            "createdAt" to createdAt,
            "center" to mapOf("x" to center.x, "y" to center.y),
            "rotationRad" to rotationRad,
            "scale" to scale,
            "boxWidth" to boxWidth,
            "colorArgb" to colorArgb,
            "backgroundPillEnabled" to backgroundPillEnabled,
            "font" to font.name,
            "alignment" to alignment.name
        )
    ).asJsonObject
    is SyncOperationPayload.UpdateTextElement -> gson.toJsonTree(
        mapOf(
            "id" to id,
            "text" to text,
            "createdAt" to createdAt,
            "center" to mapOf("x" to center.x, "y" to center.y),
            "rotationRad" to rotationRad,
            "scale" to scale,
            "boxWidth" to boxWidth,
            "colorArgb" to colorArgb,
            "backgroundPillEnabled" to backgroundPillEnabled,
            "font" to font.name,
            "alignment" to alignment.name
        )
    ).asJsonObject
    SyncOperationPayload.DeleteTextElement -> JsonObject()
}

fun JsonObject?.toSyncPayload(type: DrawingOperationType): SyncOperationPayload = when (type) {
    DrawingOperationType.ADD_STROKE -> {
        val point = this?.getAsJsonObject("firstPoint")
        SyncOperationPayload.AddStroke(
            id = this?.get("id")?.asString.orEmpty(),
            colorArgb = this?.get("colorArgb")?.asLong ?: 0xff111111,
            width = this?.get("width")?.asFloat ?: 8f,
            createdAt = this?.get("createdAt")?.asLong ?: System.currentTimeMillis(),
            firstPoint = StrokePoint(
                x = point?.get("x")?.asFloat ?: 0f,
                y = point?.get("y")?.asFloat ?: 0f
            )
        )
    }
    DrawingOperationType.APPEND_POINTS -> {
        val points = this?.getAsJsonArray("points")?.mapNotNull { element ->
            val point = element.asJsonObject
            StrokePoint(
                x = point.get("x")?.asFloat ?: return@mapNotNull null,
                y = point.get("y")?.asFloat ?: return@mapNotNull null
            )
        }.orEmpty()
        SyncOperationPayload.AppendPoints(points)
    }
    DrawingOperationType.FINISH_STROKE -> SyncOperationPayload.FinishStroke
    DrawingOperationType.DELETE_STROKE -> SyncOperationPayload.DeleteStroke
    DrawingOperationType.CLEAR_CANVAS -> SyncOperationPayload.ClearCanvas
    DrawingOperationType.ADD_TEXT_ELEMENT -> {
        val center = this?.getAsJsonObject("center")
        SyncOperationPayload.AddTextElement(
            id = this?.get("id")?.asString.orEmpty(),
            text = this?.get("text")?.asString.orEmpty(),
            createdAt = this?.get("createdAt")?.asLong ?: System.currentTimeMillis(),
            center = StrokePoint(
                x = center?.get("x")?.asFloat ?: 0f,
                y = center?.get("y")?.asFloat ?: 0f
            ),
            rotationRad = this?.get("rotationRad")?.asFloat ?: 0f,
            scale = this?.get("scale")?.asFloat ?: 1f,
            boxWidth = this?.get("boxWidth")?.asFloat ?: 0.7f,
            colorArgb = this?.get("colorArgb")?.asLong ?: 0xff111111,
            backgroundPillEnabled = this?.get("backgroundPillEnabled")?.asBoolean ?: false,
            font = runCatching {
                CanvasTextFont.valueOf(this?.get("font")?.asString ?: "POPPINS")
            }.getOrElse { CanvasTextFont.POPPINS },
            alignment = runCatching {
                CanvasTextAlign.valueOf(this?.get("alignment")?.asString ?: "CENTER")
            }.getOrElse { CanvasTextAlign.CENTER }
        )
    }
    DrawingOperationType.UPDATE_TEXT_ELEMENT -> {
        val center = this?.getAsJsonObject("center")
        SyncOperationPayload.UpdateTextElement(
            id = this?.get("id")?.asString.orEmpty(),
            text = this?.get("text")?.asString.orEmpty(),
            createdAt = this?.get("createdAt")?.asLong ?: System.currentTimeMillis(),
            center = StrokePoint(
                x = center?.get("x")?.asFloat ?: 0f,
                y = center?.get("y")?.asFloat ?: 0f
            ),
            rotationRad = this?.get("rotationRad")?.asFloat ?: 0f,
            scale = this?.get("scale")?.asFloat ?: 1f,
            boxWidth = this?.get("boxWidth")?.asFloat ?: 0.7f,
            colorArgb = this?.get("colorArgb")?.asLong ?: 0xff111111,
            backgroundPillEnabled = this?.get("backgroundPillEnabled")?.asBoolean ?: false,
            font = runCatching {
                CanvasTextFont.valueOf(this?.get("font")?.asString ?: "POPPINS")
            }.getOrElse { CanvasTextFont.POPPINS },
            alignment = runCatching {
                CanvasTextAlign.valueOf(this?.get("alignment")?.asString ?: "CENTER")
            }.getOrElse { CanvasTextAlign.CENTER }
        )
    }
    DrawingOperationType.DELETE_TEXT_ELEMENT -> SyncOperationPayload.DeleteTextElement
}

private fun nowIsoString(): String = SimpleDateFormat(
    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
    Locale.US
).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date())

private fun String.toClientLocalDate(): String = runCatching {
    Instant.parse(this).atZone(ZoneId.systemDefault()).toLocalDate().toString()
}.getOrElse {
    LocalDate.now().toString()
}
