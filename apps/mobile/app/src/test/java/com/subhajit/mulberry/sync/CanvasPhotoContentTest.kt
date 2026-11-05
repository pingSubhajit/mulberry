package com.subhajit.mulberry.sync

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.subhajit.mulberry.drawing.geometry.hasLegacyGeometry
import com.subhajit.mulberry.drawing.model.CanvasPhotoElement
import com.subhajit.mulberry.drawing.model.CanvasPhotoStyle
import com.subhajit.mulberry.drawing.model.CanvasStickerElement
import com.subhajit.mulberry.drawing.model.CanvasTextElement
import com.subhajit.mulberry.drawing.model.DrawingOperationType
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.network.CanvasSnapshotResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Photos sync as iOS sends them; content newer than this build is still synced past safely. */
class CanvasPhotoContentTest {
    @Test
    fun readsPhotoOperationsAndSkipsUnknownTypes() {
        val batch = parseServerOperationBatch(
            """
            {
              "type":"SERVER_OP_BATCH",
              "operations":[
                ${envelope(revision = 1, type = "ADD_PHOTO_ELEMENT", strokeId = "photo-1", payload = PHOTO_PAYLOAD)},
                ${envelope(revision = 2, type = "ADD_HOLOGRAM_ELEMENT", strokeId = "future-1", payload = "{}")},
                ${envelope(revision = 3, type = "DELETE_PHOTO_ELEMENT", strokeId = "photo-1", payload = "{}")}
              ]
            }
            """.trimIndent()
        )

        val operations = batch.operations.map { it.toDomainOperation() }

        assertEquals(listOf(1L, 2L, 3L), operations.map { it.serverRevision })
        assertEquals(DrawingOperationType.ADD_PHOTO_ELEMENT, operations[0].type)
        assertEquals(SyncOperationPayload.AddPhotoElement(EXPECTED_PHOTO), operations[0].payload)
        assertNull(operations[1].type)
        assertEquals(SyncOperationPayload.Unsupported("ADD_HOLOGRAM_ELEMENT"), operations[1].payload)
        assertEquals(SyncOperationPayload.DeletePhotoElement, operations[2].payload)
        assertFalse(operations.any { it.hasLegacyGeometry() })
    }

    @Test
    fun skipsAPhotoWithoutItsUploadOrShape() {
        val noUpload = JsonParser.parseString("""{"id":"p","center":{"x":0.5,"y":0.5},"aspectRatio":1}""").asJsonObject
        val noShape = JsonParser.parseString("""{"id":"p","center":{"x":0.5,"y":0.5},"photoId":"u","aspectRatio":0}""").asJsonObject

        assertEquals(
            SyncOperationPayload.Unsupported("ADD_PHOTO_ELEMENT"),
            noUpload.toSyncPayload(DrawingOperationType.ADD_PHOTO_ELEMENT)
        )
        assertEquals(
            SyncOperationPayload.Unsupported("UPDATE_PHOTO_ELEMENT"),
            noShape.toSyncPayload(DrawingOperationType.UPDATE_PHOTO_ELEMENT)
        )
    }

    @Test
    fun readsAnUnknownStyleAsAFrame() {
        val json = JsonParser.parseString(
            """{"id":"p","center":{"x":0.5,"y":0.5},"photoId":"u","aspectRatio":1,"style":"HOLOGRAM"}"""
        ).asJsonObject

        assertEquals(CanvasPhotoStyle.FRAME, json.toPhotoElement()?.style)
    }

    @Test
    fun writesThePhotoPayloadIOSReadsAndLeavesOutAMissingCutout() {
        val written = EXPECTED_PHOTO.toPhotoPayloadJson()
        assertEquals(EXPECTED_PHOTO, written.toPhotoElement())
        assertEquals("POLAROID", written.get("style").asString)
        assertEquals(0.3, written.getAsJsonObject("center").get("x").asDouble, 0.0001)

        val withoutCutout = EXPECTED_PHOTO.copy(cutoutPhotoId = null).toPhotoPayloadJson()
        assertFalse(withoutCutout.has("cutoutPhotoId"))
    }

    @Test
    fun keepsPhotosInASnapshotInTheServersOrder() {
        val response = Gson().fromJson(
            """
            {
              "pairSessionId":"pair-1",
              "snapshotRevision":4,
              "latestRevision":4,
              "revision":4,
              "updatedAt":null,
              "snapshot":{
                "strokes":[],
                "textElements":[],
                "elements":[
                  {"kind":"PHOTO",${PHOTO_PAYLOAD.removePrefix("{")},
                  {"kind":"STICKER","id":"sticker-1","createdAt":2,"center":{"x":0.4,"y":0.6},"rotationRad":0,
                   "scale":0.22,"packKey":"kawaii-cats","packVersion":1,"stickerId":"8"},
                  {"kind":"TEXT","id":"text-1","text":"Hi","createdAt":1,"center":{"x":0.5,"y":0.5},"rotationRad":0,
                   "scale":1,"boxWidth":0.7,"colorArgb":4279308561,"backgroundPillEnabled":false,"font":"POPPINS",
                   "alignment":"CENTER"},
                  {"kind":"PHOTO","id":"photo-2","createdAt":4,"center":{"x":0.5,"y":0.5},"rotationRad":0,"scale":0.5,
                   "aspectRatio":1.5,"style":"FRAME"},
                  {"kind":"SOMETHING_NEWER","id":"future-1"}
                ]
              }
            }
            """.trimIndent(),
            CanvasSnapshotResponse::class.java
        )

        val elements = response.snapshot.toDomainElements()

        // photo-2 has no upload, so it is left out, as the newer kind is.
        assertEquals(listOf("photo-1", "sticker-1", "text-1"), elements.map { it.id })
        assertEquals(EXPECTED_PHOTO, elements[0])
        assertEquals(CanvasStickerElement::class, elements[1]::class)
        assertEquals(CanvasTextElement::class, elements[2]::class)
    }

    private fun envelope(revision: Long, type: String, strokeId: String?, payload: String): String =
        """
        {
          "clientOperationId":"client-op-$revision",
          "actorUserId":"partner-1",
          "pairSessionId":"pair-1",
          "type":"$type",
          "strokeId":${strokeId?.let { "\"$it\"" } ?: "null"},
          "payload":$payload,
          "clientCreatedAt":"2026-11-05T00:00:00.000Z",
          "serverRevision":$revision,
          "createdAt":"2026-11-05T00:00:01.000Z"
        }
        """.trimIndent()

    private companion object {
        const val PHOTO_PAYLOAD =
            """{"id":"photo-1","createdAt":3,"center":{"x":0.3,"y":0.7},"rotationRad":0.2,"scale":0.5,""" +
                """"photoId":"upload-1","aspectRatio":1.5,"style":"POLAROID","cutoutPhotoId":"cutout-1"}"""

        val EXPECTED_PHOTO = CanvasPhotoElement(
            id = "photo-1",
            createdAt = 3L,
            center = StrokePoint(x = 0.3f, y = 0.7f),
            rotationRad = 0.2f,
            scale = 0.5f,
            photoId = "upload-1",
            cutoutPhotoId = "cutout-1",
            aspectRatio = 1.5f,
            style = CanvasPhotoStyle.POLAROID
        )
    }
}
