package com.subhajit.mulberry.analytics

/**
 * App events from docs/analytics/tracking-plan.md ("App events (Phase 2)"). The server rejects any
 * other event name or value, so new ones are added there first.
 */
sealed interface AnalyticsEvent {
    val name: String
    val properties: Map<String, String>

    data class OnboardingStepViewed(val step: OnboardingStep) : AnalyticsEvent {
        override val name: String = "mulberry_onboarding_step_viewed"
        override val properties: Map<String, String> = mapOf("step" to step.wireValue)
    }

    data class WallpaperSetupStepChanged(
        val step: WallpaperSetupStep,
        val outcome: WallpaperSetupOutcome,
        val failureReason: WallpaperSetupFailureReason? = null,
        val surfaceType: WallpaperSurfaceType = WallpaperSurfaceType.ANDROID_WALLPAPER
    ) : AnalyticsEvent {
        override val name: String = "mulberry_wallpaper_setup_step"
        override val properties: Map<String, String> = buildMap {
            put("surface_type", surfaceType.wireValue)
            put("step", step.wireValue)
            put("outcome", outcome.wireValue)
            failureReason?.let { put("failure_reason", it.wireValue) }
        }
    }

    data class PushOpened(val pushType: PushType) : AnalyticsEvent {
        override val name: String = "mulberry_push_opened"
        override val properties: Map<String, String> = mapOf("push_type" to pushType.wireValue)
    }
}

enum class OnboardingStep(val wireValue: String) {
    WELCOME("welcome"),
    SIGN_IN("sign_in"),
    PROFILE_NAME("profile_name"),
    PARTNER_DETAILS("partner_details"),
    PAIRING_CHOICE("pairing_choice"),
    INVITE_CODE_SHOWN("invite_code_shown"),
    REDEEM_CODE_ENTRY("redeem_code_entry"),
    INVITE_REVIEW("invite_review"),
    WAITING_FOR_PARTNER("waiting_for_partner"),
    PAIRED_CELEBRATION("paired_celebration"),
    NOTIFICATIONS_PERMISSION("notifications_permission"),
    WALLPAPER_SETUP("wallpaper_setup"),
    ONBOARDING_FINISHED("onboarding_finished")
}

enum class WallpaperSurfaceType(val wireValue: String) {
    ANDROID_WALLPAPER("ANDROID_WALLPAPER"),
    IOS_SHORTCUT("IOS_SHORTCUT"),
    MACOS_OVERLAY("MACOS_OVERLAY")
}

enum class WallpaperSetupStep(val wireValue: String) {
    INTRO("intro"),
    CHOOSE_BACKGROUND("choose_background"),
    SET_WALLPAPER("set_wallpaper"),
    INSTALL_SHORTCUT("install_shortcut"),
    CREATE_AUTOMATION("create_automation"),
    GRANT_PERMISSION("grant_permission"),
    ENABLE_OVERLAY("enable_overlay"),
    RUN_TEST("run_test"),
    FINISHED("finished")
}

enum class WallpaperSetupOutcome(val wireValue: String) {
    VIEWED("viewed"),
    COMPLETED("completed"),
    FAILED("failed"),
    SKIPPED("skipped")
}

/** `failure_reason` is free-form snake_case on the server; an enum keeps it short and personless. */
enum class WallpaperSetupFailureReason(val wireValue: String) {
    BACKGROUND_IMPORT_FAILED("background_import_failed"),
    SNAPSHOT_FAILED("snapshot_failed"),
    NOT_SET_IN_PICKER("not_set_in_picker")
}

enum class PushType(val wireValue: String) {
    CANVAS_NUDGE("CANVAS_NUDGE"),
    DRAW_REMINDER("DRAW_REMINDER"),
    REACTION("REACTION"),
    PAIRING_CONFIRMED("PAIRING_CONFIRMED"),
    PAIRING_DISCONNECTED("PAIRING_DISCONNECTED"),
    PARTNER_VISIBILITY_CHANGED("PARTNER_VISIBILITY_CHANGED"),
    ANNIVERSARY_CHANGED("ANNIVERSARY_CHANGED"),
    OTHER("OTHER");

    companion object {
        /** Maps a push payload's `type`; anything unknown is [OTHER]. */
        fun fromPayloadType(type: String?): PushType =
            entries.firstOrNull { it != OTHER && it.wireValue == type } ?: OTHER
    }
}

enum class DeviceFamily(val wireValue: String) {
    ANDROID_PHONE("android_phone"),
    ANDROID_TABLET("android_tablet");

    companion object {
        private const val TABLET_SMALLEST_WIDTH_DP = 600

        fun fromSmallestWidthDp(smallestWidthDp: Int): DeviceFamily =
            if (smallestWidthDp >= TABLET_SMALLEST_WIDTH_DP) ANDROID_TABLET else ANDROID_PHONE
    }
}
