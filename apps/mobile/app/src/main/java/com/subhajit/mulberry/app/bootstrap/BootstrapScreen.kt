package com.subhajit.mulberry.app.bootstrap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.navigation.AppRoute
import com.subhajit.mulberry.ui.theme.MulberryDarkBackground

@Composable
fun BootstrapRoute(
    onRouteResolved: (AppRoute) -> Unit,
    viewModel: BootstrapViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.destination) {
        uiState.destination?.let(onRouteResolved)
    }

    BootstrapScreen()
}

@Composable
private fun BootstrapScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MulberryDarkBackground)
            .testTag(TestTags.BOOTSTRAP_SCREEN)
    )
}
