package com.subhajit.mulberry.app.bootstrap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.navigation.AppRoute

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
            .testTag(TestTags.BOOTSTRAP_SCREEN),
        contentAlignment = Alignment.Center
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = "Loading your shared space...",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
