package com.subhajit.mulberry.sync

import com.google.gson.Gson
import com.subhajit.mulberry.drawing.model.DrawingOperationType
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.network.CanvasOperationEnvelopeResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasSyncJsonTest {
    private val gson = Gson()

    @Test
    fun serializesAddStrokeOperationWithCanonicalPayload() {
        val operation = newClientOperation(
            type = DrawingOperationType.ADD_STROKE,
            strokeId = "stroke-1",
            payload = SyncOperationPayload.AddStroke(
                id = "stroke-1",
                colorArgb = 0xff111111,
                width = 12f,
                createdAt = 123L,
                firstPoint = StrokePoint(10f, 20f)
            )
        )

        val json = gson.fromJson(operation.toWireJson(), Map::class.java)

        assertEquals("CLIENT_OP", json["type"])
        val body = json["operation"] as Map<*, *>
        assertEquals("ADD_STROKE", body["type"])
        assertEquals("stroke-1", body["strokeId"])
        val payload = body["payload"] as Map<*, *>
        assertEquals("stroke-1", payload["id"])
        assertEquals(12.0, payload["width"])
    }

    @Test
    fun mapsServerAppendPointsPayloadToDomainOperation() {
        val response = CanvasOperationEnvelopeResponse(
            clientOperationId = "client-op-1",
            actorUserId = "user-1",
            pairSessionId = "pair-1",
            type = "APPEND_POINTS",
            strokeId = "stroke-1",
            payload = gson.fromJson(
                """{"points":[{"x":1.0,"y":2.0},{"x":3.0,"y":4.0}]}""",
                com.google.gson.JsonObject::class.java
            ),
            clientCreatedAt = "2026-01-01T00:00:00.000Z",
            serverRevision = 2L,
            createdAt = "2026-01-01T00:00:01.000Z"
        )

        val operation = response.toDomainOperation()

        assertEquals(DrawingOperationType.APPEND_POINTS, operation.type)
        assertTrue(operation.payload is SyncOperationPayload.AppendPoints)
        val payload = operation.payload as SyncOperationPayload.AppendPoints
        assertEquals(listOf(StrokePoint(1f, 2f), StrokePoint(3f, 4f)), payload.points)
    }
}
