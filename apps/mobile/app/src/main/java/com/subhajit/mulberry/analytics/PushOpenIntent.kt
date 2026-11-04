package com.subhajit.mulberry.analytics

import android.content.Intent

/** Marks a notification's launch intent with its push type, so opening it can be counted once. */
object PushOpenIntent {
    private const val EXTRA_PUSH_TYPE = "com.subhajit.mulberry.extra.PUSH_TYPE"
    private const val REQUEST_CODE_BASE = 4100

    fun tag(intent: Intent, pushType: PushType): Intent =
        intent.putExtra(EXTRA_PUSH_TYPE, pushType.wireValue)

    /**
     * A PendingIntent request code per push type. Tagged intents often share an action with other
     * notifications, and FLAG_UPDATE_CURRENT would otherwise swap their extras.
     */
    fun requestCode(pushType: PushType): Int = REQUEST_CODE_BASE + pushType.ordinal

    /** The push type [intent] was opened from, removing the mark so a later delivery doesn't recount it. */
    fun consume(intent: Intent?): PushType? {
        if (intent?.hasExtra(EXTRA_PUSH_TYPE) != true) return null
        val payloadType = intent.getStringExtra(EXTRA_PUSH_TYPE)
        intent.removeExtra(EXTRA_PUSH_TYPE)
        return PushType.fromPayloadType(payloadType)
    }
}
