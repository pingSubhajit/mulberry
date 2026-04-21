package com.subhajit.mulberry.sync

data class BackgroundCanvasSyncPayload(
    val pairSessionId: String?,
    val latestRevision: Long?
)

object BackgroundCanvasSyncPayloadParser {
    private const val TYPE_CANVAS_UPDATED = "CANVAS_UPDATED"

    fun parse(data: Map<String, String>): BackgroundCanvasSyncPayload? {
        if (data["type"] != TYPE_CANVAS_UPDATED) return null
        val pairSessionId = data["pairSessionId"]?.takeIf { it.isNotBlank() }
        val latestRevision = data["latestRevision"]?.toLongOrNull()
        return BackgroundCanvasSyncPayload(
            pairSessionId = pairSessionId,
            latestRevision = latestRevision
        )
    }
}
