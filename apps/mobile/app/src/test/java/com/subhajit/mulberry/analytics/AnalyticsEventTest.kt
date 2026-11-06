package com.subhajit.mulberry.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsEventTest {
    @Test
    fun `events carry the contract's names and properties`() {
        assertEquals(
            "mulberry_onboarding_step_viewed" to mapOf("step" to "redeem_code_entry"),
            AnalyticsEvent.OnboardingStepViewed(OnboardingStep.REDEEM_CODE_ENTRY).let { it.name to it.properties }
        )
        assertEquals(
            "mulberry_wallpaper_setup_step" to mapOf(
                "surface_type" to "ANDROID_WALLPAPER",
                "step" to "set_wallpaper",
                "outcome" to "failed",
                "failure_reason" to "not_set_in_picker"
            ),
            AnalyticsEvent.WallpaperSetupStepChanged(
                step = WallpaperSetupStep.SET_WALLPAPER,
                outcome = WallpaperSetupOutcome.FAILED,
                failureReason = WallpaperSetupFailureReason.NOT_SET_IN_PICKER
            ).let { it.name to it.properties }
        )
        assertEquals(
            mapOf("surface_type" to "ANDROID_WALLPAPER", "step" to "intro", "outcome" to "viewed"),
            AnalyticsEvent.WallpaperSetupStepChanged(WallpaperSetupStep.INTRO, WallpaperSetupOutcome.VIEWED).properties
        )
        assertEquals(
            "mulberry_push_opened" to mapOf("push_type" to "DRAW_REMINDER"),
            AnalyticsEvent.PushOpened(PushType.DRAW_REMINDER).let { it.name to it.properties }
        )
    }

    @Test
    fun `share events carry the spec's names and properties`() {
        assertEquals(
            "mulberry_share_opened" to emptyMap<String, String>(),
            AnalyticsEvent.ShareOpened.let { it.name to it.properties }
        )
        assertEquals(
            "mulberry_share_frame_selected" to mapOf("frame_key" to "polaroid"),
            AnalyticsEvent.ShareFrameSelected(frameKey = "polaroid").let { it.name to it.properties }
        )
        assertEquals(
            "mulberry_share_completed" to mapOf(
                "frame_key" to "valentines",
                "used_real_wallpaper" to "false",
                "caption_edited" to "true"
            ),
            AnalyticsEvent.ShareCompleted(
                frameKey = "valentines",
                usedRealWallpaper = false,
                captionEdited = true
            ).let { it.name to it.properties }
        )
    }

    @Test
    fun `wire values match the tracking plan`() {
        assertEquals(
            listOf(
                "welcome", "sign_in", "profile_name", "partner_details", "pairing_choice",
                "invite_code_shown", "redeem_code_entry", "invite_review", "waiting_for_partner",
                "paired_celebration", "notifications_permission", "wallpaper_setup", "onboarding_finished"
            ),
            OnboardingStep.entries.map { it.wireValue }
        )
        assertEquals(
            listOf(
                "intro", "choose_background", "set_wallpaper", "install_shortcut", "create_automation",
                "grant_permission", "enable_overlay", "run_test", "finished"
            ),
            WallpaperSetupStep.entries.map { it.wireValue }
        )
        assertEquals(listOf("viewed", "completed", "failed", "skipped"), WallpaperSetupOutcome.entries.map { it.wireValue })
        assertEquals(
            listOf("ANDROID_WALLPAPER", "IOS_SHORTCUT", "MACOS_OVERLAY"),
            WallpaperSurfaceType.entries.map { it.wireValue }
        )
        WallpaperSetupFailureReason.entries.forEach {
            assertTrue(it.wireValue, Regex("^[a-z0-9]+(_[a-z0-9]+)*$").matches(it.wireValue) && it.wireValue.length <= 64)
        }
    }

    @Test
    fun `push payload types map to push_type, anything else is OTHER`() {
        assertEquals(PushType.CANVAS_NUDGE, PushType.fromPayloadType("CANVAS_NUDGE"))
        assertEquals(PushType.PARTNER_VISIBILITY_CHANGED, PushType.fromPayloadType("PARTNER_VISIBILITY_CHANGED"))
        assertEquals(PushType.OTHER, PushType.fromPayloadType("CANVAS_UPDATED"))
        assertEquals(PushType.OTHER, PushType.fromPayloadType("canvas_nudge"))
        assertEquals(PushType.OTHER, PushType.fromPayloadType(null))
        assertEquals(
            listOf(
                "CANVAS_NUDGE", "DRAW_REMINDER", "REACTION", "PAIRING_CONFIRMED", "PAIRING_DISCONNECTED",
                "PARTNER_VISIBILITY_CHANGED", "ANNIVERSARY_CHANGED", "OTHER"
            ),
            PushType.entries.map { it.wireValue }
        )
    }
}
