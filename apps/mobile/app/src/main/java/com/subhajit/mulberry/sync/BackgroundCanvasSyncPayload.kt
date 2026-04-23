package com.subhajit.mulberry.sync

data class BackgroundCanvasSyncPayload(
    val pairSessionId: String?,
    val latestRevision: Long?
)

data class PairingConfirmedPushPayload(
    val pairSessionId: String?,
    val actorUserId: String?,
    val actorDisplayName: String
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

object PairingConfirmedPushPayloadParser {
    private const val TYPE_PAIRING_CONFIRMED = "PAIRING_CONFIRMED"

    fun parse(data: Map<String, String>): PairingConfirmedPushPayload? {
        if (data["type"] != TYPE_PAIRING_CONFIRMED) return null
        return PairingConfirmedPushPayload(
            pairSessionId = data["pairSessionId"]?.takeIf { it.isNotBlank() },
            actorUserId = data["actorUserId"]?.takeIf { it.isNotBlank() },
            actorDisplayName = data["actorDisplayName"]?.takeIf { it.isNotBlank() } ?: "Your partner"
        )
    }
}
