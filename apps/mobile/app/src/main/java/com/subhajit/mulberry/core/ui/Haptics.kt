package com.subhajit.mulberry.core.ui

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * The moments the app gives feedback for, named after the iPhone's feedback styles. Each is played
 * with Android's own haptic vocabulary (see [hapticFeedbackConstant]).
 */
enum class MulberryHaptic {
    /** A choice among options: a font, an alignment, a colour, a sticker (iOS `.selection`). */
    Selection,

    /** A rotation settling on level or on a quarter turn (iOS `.selection` on the snap). */
    RotationSnap,

    /** Something went well: the streak's "today" moment, the wallpaper test answering (iOS `.success`). */
    Success,

    /** A light tap: a reaction sent, a press on the streak bolt (iOS `.impact(weight: .light)`). */
    LightImpact,

    /** A held finger opening the reactions (iOS `.impact(weight: .medium)` on the hold). */
    LongPress,

    /** The partner's reactions arriving (iOS `.impact(weight: .medium)` on arrival). */
    ReactionArrived,

    /** The streak breaking (iOS `.impact(weight: .heavy)` on the cracks). */
    Reject
}

/** The [HapticFeedbackConstants] value for [haptic] on a device running [sdkInt]. */
fun hapticFeedbackConstant(haptic: MulberryHaptic, sdkInt: Int = Build.VERSION.SDK_INT): Int =
    when (haptic) {
        MulberryHaptic.Selection,
        MulberryHaptic.RotationSnap -> if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        MulberryHaptic.Success -> if (sdkInt >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        MulberryHaptic.LightImpact -> HapticFeedbackConstants.CONTEXT_CLICK
        MulberryHaptic.LongPress,
        MulberryHaptic.ReactionArrived -> HapticFeedbackConstants.LONG_PRESS
        MulberryHaptic.Reject -> if (sdkInt >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
    }

/**
 * The predefined vibration for [haptic], for places with no view to play it through, or null on
 * releases before predefined effects (API 29).
 */
fun predefinedVibrationEffect(haptic: MulberryHaptic, sdkInt: Int = Build.VERSION.SDK_INT): Int? {
    if (sdkInt < Build.VERSION_CODES.Q) return null
    return when (haptic) {
        MulberryHaptic.Selection,
        MulberryHaptic.RotationSnap -> VibrationEffect.EFFECT_TICK
        MulberryHaptic.Success -> VibrationEffect.EFFECT_DOUBLE_CLICK
        MulberryHaptic.LightImpact -> VibrationEffect.EFFECT_CLICK
        MulberryHaptic.LongPress,
        MulberryHaptic.ReactionArrived,
        MulberryHaptic.Reject -> VibrationEffect.EFFECT_HEAVY_CLICK
    }
}

/** Plays [haptic] through this view, so the system's touch-feedback setting decides whether it's felt. */
fun View.performMulberryHaptic(haptic: MulberryHaptic) {
    performHapticFeedback(hapticFeedbackConstant(haptic))
}

/**
 * Plays [haptic] where there is no view to play it through, such as the live wallpaper. It is
 * marked as touch feedback, and skipped when touch feedback is off, as a view's would be.
 */
fun Context.performMulberryHapticWithoutView(haptic: MulberryHaptic) {
    val hapticsOn = runCatching {
        Settings.System.getInt(contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
    }.getOrDefault(true)
    if (!hapticsOn) return
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    } ?: return
    if (!vibrator.hasVibrator()) return
    runCatching {
        val effect = predefinedVibrationEffect(haptic)?.let(VibrationEffect::createPredefined)
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE)
            } else {
                null
            }
        when {
            effect == null -> {
                @Suppress("DEPRECATION")
                vibrator.vibrate(30L)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> vibrator.vibrate(
                effect,
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
            )
            else -> {
                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    effect,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        }
    }
}

/** Plays the app's haptics from a composable, through the view hosting it. */
@Stable
class MulberryHaptics internal constructor(private val view: View) {
    fun perform(haptic: MulberryHaptic) = view.performMulberryHaptic(haptic)
}

@Composable
fun rememberMulberryHaptics(): MulberryHaptics {
    val view = LocalView.current
    return remember(view) { MulberryHaptics(view) }
}

/**
 * Plays [haptic] each time [value] changes after first showing, as SwiftUI's
 * `.sensoryFeedback(_, trigger:)` does.
 */
@Composable
fun <T> HapticOnChange(value: T, haptic: MulberryHaptic) {
    val haptics = rememberMulberryHaptics()
    val previous = remember { PreviousValue(value) }
    LaunchedEffect(value) {
        if (previous.value == value) return@LaunchedEffect
        previous.value = value
        haptics.perform(haptic)
    }
}

private class PreviousValue<T>(var value: T)
