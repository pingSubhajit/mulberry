package com.subhajit.mulberry.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.subhajit.mulberry.app.snackbar.MulberrySnackbarController
import com.subhajit.mulberry.navigation.MulberryNavHost

@Composable
fun MulberryApp(
    onRouteChanged: (String?) -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        MulberrySnackbarController.requests.collect { request ->
            val result = snackbarHostState.showSnackbar(
                message = request.message,
                actionLabel = request.actionLabel,
                duration = if (request.isIndefinite) {
                    SnackbarDuration.Indefinite
                } else {
                    SnackbarDuration.Short
                }
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                val actionKey = request.actionKey ?: return@collect
                MulberrySnackbarController.emitAction(actionKey)
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            MulberryNavHost(onRouteChanged = onRouteChanged)
        }
    }
}
