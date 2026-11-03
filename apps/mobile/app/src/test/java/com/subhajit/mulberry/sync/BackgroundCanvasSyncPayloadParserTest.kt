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

    @Test
    fun parsesDrawReminderPayload() {
        val payload = DrawReminderPushPayloadParser.parse(
            mapOf(
                "type" to "DRAW_REMINDER",
                "pairSessionId" to "pair-1",
                "partnerDisplayName" to "Ankita",
                "reminderCount" to "2"
            )
        )

        assertEquals("pair-1", payload?.pairSessionId)
        assertEquals("Ankita", payload?.partnerDisplayName)
        assertEquals(2, payload?.reminderCount)
    }

    @Test
    fun ignoresUnsupportedDrawReminderPayloads() {
        val payload = DrawReminderPushPayloadParser.parse(
            mapOf(
                "type" to "OTHER",
                "pairSessionId" to "pair-1"
            )
        )

        assertNull(payload)
    }

    @Test
    fun parsesPairEndedPayload() {
        assertEquals(
            PairEndedPushPayload(pairSessionId = "pair-1"),
            PairEndedPushPayloadParser.parse(mapOf("type" to "PAIR_ENDED", "pairSessionId" to "pair-1"))
        )
        assertNull(PairEndedPushPayloadParser.parse(mapOf("type" to "PAIR_ENDED")))
        assertNull(PairEndedPushPayloadParser.parse(mapOf("type" to "REACTION", "pairSessionId" to "pair-1")))
    }

    @Test
    fun parsesAnniversaryChangedPayload() {
        val payload = AnniversaryChangedPushPayloadParser.parse(
            mapOf(
                "type" to "ANNIVERSARY_CHANGED",
                "pairSessionId" to "pair-1",
                "actorUserId" to "user-1",
                "anniversaryDate" to "2026-02-14"
            )
        )

        assertEquals("pair-1", payload?.pairSessionId)
        assertEquals("2026-02-14", payload?.anniversaryDate)
    }

    @Test
    fun ignoresAnniversaryChangedPayloadWithoutAValidDate() {
        for (date in listOf(null, "", "  ", "2026-02-30", "14/02/2026")) {
            val data = buildMap {
                put("type", "ANNIVERSARY_CHANGED")
                put("pairSessionId", "pair-1")
                if (date != null) put("anniversaryDate", date)
            }
            assertNull(date, AnniversaryChangedPushPayloadParser.parse(data))
        }
    }

    @Test
    fun ignoresOtherPayloadsAsAnniversaryChanges() {
        val payload = AnniversaryChangedPushPayloadParser.parse(
            mapOf(
                "type" to "CANVAS_UPDATED",
                "pairSessionId" to "pair-1",
                "anniversaryDate" to "2026-02-14"
            )
        )

        assertNull(payload)
    }
}
