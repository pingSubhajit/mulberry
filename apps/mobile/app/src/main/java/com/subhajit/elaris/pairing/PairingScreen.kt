package com.subhajit.elaris.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subhajit.elaris.core.ui.TestTags

@Composable
fun PairingRoute(
    onNavigateToCanvasHome: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect is PairingEffect.NavigateToCanvasHome) {
                onNavigateToCanvasHome()
            }
        }
    }

    PairingScreen(
        uiState = uiState,
        onUseDemoSessionClicked = viewModel::onUseDemoSessionClicked
    )
}

@Composable
private fun PairingScreen(
    uiState: PairingUiState,
    onUseDemoSessionClicked: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .testTag(TestTags.PAIRING_SCREEN),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Pair your two devices",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "The backend is not connected yet, so this screen is intentionally a product placeholder. It shows the future pairing affordances and the environment this build is pointed at.",
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Build configuration",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(text = "Environment: ${uiState.environmentLabel}")
                Text(text = "API Base URL: ${uiState.apiBaseUrl}")
            }
        }

        if (uiState.showPlaceholderPairingControls) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Planned pairing flow",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Generate Pairing Token")
                    }
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Scan or Enter Token")
                    }
                    Text(
                        text = "These controls stay disabled until the session and backend workstreams land.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (uiState.showDeveloperBootstrapActions) {
            TextButton(
                onClick = onUseDemoSessionClicked,
                modifier = Modifier.testTag(TestTags.PAIRING_DEMO_BUTTON)
            ) {
                Text("Use Demo Paired Session")
            }
        }
    }
}
