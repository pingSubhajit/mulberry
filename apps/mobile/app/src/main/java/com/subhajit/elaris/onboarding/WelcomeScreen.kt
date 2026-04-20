package com.subhajit.elaris.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.subhajit.elaris.core.ui.TestTags

@Composable
fun WelcomeRoute(
    onNavigateToPairing: () -> Unit,
    viewModel: WelcomeViewModel = hiltViewModel()
) {
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect is WelcomeEffect.NavigateToPairing) {
                onNavigateToPairing()
            }
        }
    }

    WelcomeScreen(
        onContinueClicked = viewModel::onContinueClicked
    )
}

@Composable
private fun WelcomeScreen(
    onContinueClicked: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 40.dp)
            .testTag(TestTags.WELCOME_SCREEN),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Elaris",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "A shared lock screen canvas for two people.",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "Draw inside the app, then let the live wallpaper turn your lock screen into an always-near shared space. Real-time when both phones are active, dependable catch-up when they are not.",
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "What this foundation includes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Onboarding, placeholder pairing, bootstrap persistence, settings, and a home shell ready for drawing, sync, and wallpaper workstreams.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Button(
            onClick = onContinueClicked,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.WELCOME_CONTINUE_BUTTON)
        ) {
            Text("Get Started")
        }
    }
}
