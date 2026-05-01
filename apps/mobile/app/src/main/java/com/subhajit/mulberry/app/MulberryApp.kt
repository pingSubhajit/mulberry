package com.subhajit.mulberry.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.subhajit.mulberry.navigation.MulberryNavHost

@Composable
fun MulberryApp() {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        InAppSnackbarBus.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { _ ->
        Surface(modifier = Modifier.fillMaxSize()) {
            MulberryNavHost()
        }
    }
}
