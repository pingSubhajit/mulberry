package com.subhajit.mulberry.home

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor

/** Rotations settle level, or on a quarter turn, as on the iPhone, with a tick as they land. */
internal object ElementRotation {
    /** How close a twist must come to a quarter turn to settle on it: 3°. */
    const val SETTLE_TOLERANCE_RAD: Float = (3.0 * PI / 180.0).toFloat()

    /** How close the rotation slider must come to level to settle there. */
    const val SLIDER_SETTLE_DEGREES: Float = 3f

    private const val QUARTER_TURN_RAD: Float = (PI / 2.0).toFloat()

    /** The quarter turn nearest [angleRad], if it is within [toleranceRad] of it. */
    fun quarterTurnNear(angleRad: Float, toleranceRad: Float = SETTLE_TOLERANCE_RAD): Float? {
        val nearest = floor(angleRad / QUARTER_TURN_RAD + 0.5f) * QUARTER_TURN_RAD
        return if (abs(angleRad - nearest) <= toleranceRad) nearest else null
    }

    /** [angleRad] settled onto a nearby quarter turn, or unchanged. */
    fun settle(angleRad: Float): Float = quarterTurnNear(angleRad) ?: angleRad

    /** Whether [angleRad] sits on a quarter turn. */
    fun isSquare(angleRad: Float): Boolean = quarterTurnNear(angleRad, toleranceRad = 1e-4f) != null

    /** The slider's value, settled on level within [SLIDER_SETTLE_DEGREES]. */
    fun settleSliderDegrees(degrees: Float): Float =
        if (abs(degrees) < SLIDER_SETTLE_DEGREES) 0f else degrees
}
