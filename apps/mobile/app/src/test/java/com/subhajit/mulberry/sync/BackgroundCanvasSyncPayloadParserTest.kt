package com.subhajit.mulberry.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundCanvasSyncPayloadParserTest {
    @Test
    fun parsesCanvasUpdatedPayload() {
        val payload = BackgroundCanvasSyncPayloadParser.parse(
            mapOf(
                "type" to "CANVAS_UPDATED",
                "pairSessionId" to "pair-1",
                "latestRevision" to "42"
            )
        )

        assertEquals("pair-1", payload?.pairSessionId)
        assertEquals(42L, payload?.latestRevision)
    }

    @Test
    fun ignoresUnsupportedPayloads() {
        val payload = BackgroundCanvasSyncPayloadParser.parse(
            mapOf(
                "type" to "OTHER",
                "pairSessionId" to "pair-1",
                "latestRevision" to "42"
            )
        )

        assertNull(payload)
    }

    @Test
    fun toleratesMissingRevisionHint() {
        val payload = BackgroundCanvasSyncPayloadParser.parse(
            mapOf(
                "type" to "CANVAS_UPDATED",
                "pairSessionId" to "pair-1"
            )
        )

        assertEquals("pair-1", payload?.pairSessionId)
        assertNull(payload?.latestRevision)
    }
}
