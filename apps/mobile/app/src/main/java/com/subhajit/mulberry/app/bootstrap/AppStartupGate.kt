package com.subhajit.mulberry.app.bootstrap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object AppStartupGate {
    private const val MAX_SPLASH_HOLD_MS = 3_000L

    private val _keepSplashVisible = MutableStateFlow(true)
    val keepSplashVisible: StateFlow<Boolean> = _keepSplashVisible

    fun armTimeout(scope: CoroutineScope) {
        _keepSplashVisible.value = true
        scope.launch {
            delay(MAX_SPLASH_HOLD_MS)
            release()
        }
    }

    fun release() {
        _keepSplashVisible.value = false
    }
}

@Composable
fun ReleaseStartupGateAfterFirstFrame() {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        AppStartupGate.release()
    }
}
