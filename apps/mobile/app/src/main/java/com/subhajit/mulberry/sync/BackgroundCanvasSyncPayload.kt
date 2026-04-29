package com.subhajit.mulberry.sync

data class BackgroundCanvasSyncPayload(
    val pairSessionId: String?,
    val latestRevision: Long?
)

data class CanvasNudgePushPayload(
    val pairSessionId: String?,
    val latestRevision: Long?,
    val actorUserId: String?,
    val actorDisplayName: String
)

data class PairingConfirmedPushPayload(
    val pairSessionId: String?,
    val actorUserId: String?,
    val actorDisplayName: String
)

data class PairingDisconnectedPushPayload(
    val pairSessionId: String?,
    val actorUserId: String?,
    val actorDisplayName: String
)

data class DrawReminderPushPayload(
    val pairSessionId: String?,
    val partnerDisplayName: String,
    val reminderCount: Int
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

object CanvasNudgePushPayloadParser {
    private const val TYPE_CANVAS_NUDGE = "CANVAS_NUDGE"

    fun parse(data: Map<String, String>): CanvasNudgePushPayload? {
        if (data["type"] != TYPE_CANVAS_NUDGE) return null
        val pairSessionId = data["pairSessionId"]?.takeIf { it.isNotBlank() }
        val latestRevision = data["latestRevision"]?.toLongOrNull()
        return CanvasNudgePushPayload(
            pairSessionId = pairSessionId,
            latestRevision = latestRevision,
            actorUserId = data["actorUserId"]?.takeIf { it.isNotBlank() },
            actorDisplayName = data["actorDisplayName"]?.takeIf { it.isNotBlank() } ?: "Your partner"
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

object PairingDisconnectedPushPayloadParser {
    private const val TYPE_PAIRING_DISCONNECTED = "PAIRING_DISCONNECTED"

    fun parse(data: Map<String, String>): PairingDisconnectedPushPayload? {
        if (data["type"] != TYPE_PAIRING_DISCONNECTED) return null
        return PairingDisconnectedPushPayload(
            pairSessionId = data["pairSessionId"]?.takeIf { it.isNotBlank() },
            actorUserId = data["actorUserId"]?.takeIf { it.isNotBlank() },
            actorDisplayName = data["actorDisplayName"]?.takeIf { it.isNotBlank() } ?: "Your partner"
        )
    }
}

object DrawReminderPushPayloadParser {
    private const val TYPE_DRAW_REMINDER = "DRAW_REMINDER"

    fun parse(data: Map<String, String>): DrawReminderPushPayload? {
        if (data["type"] != TYPE_DRAW_REMINDER) return null
        return DrawReminderPushPayload(
            pairSessionId = data["pairSessionId"]?.takeIf { it.isNotBlank() },
            partnerDisplayName = data["partnerDisplayName"]?.takeIf { it.isNotBlank() } ?: "Your partner",
            reminderCount = data["reminderCount"]?.toIntOrNull() ?: 0
        )
    }
}
