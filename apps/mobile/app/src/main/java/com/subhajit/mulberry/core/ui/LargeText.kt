package com.subhajit.mulberry.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity

/**
 * The font scale from which layouts change shape, as the iPhone's do at accessibility text sizes:
 * rows stack, and one-line text wraps.
 */
const val LargeTextFontScale = 1.5f

/** Whether [fontScale] is one of the large text sizes. */
fun isLargeTextScale(fontScale: Float): Boolean = fontScale >= LargeTextFontScale

/** Whether the person has chosen one of the large text sizes. */
@Composable
@ReadOnlyComposable
fun isLargeText(): Boolean = isLargeTextScale(LocalDensity.current.fontScale)
