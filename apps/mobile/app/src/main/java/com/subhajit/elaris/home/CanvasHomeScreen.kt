package com.subhajit.elaris.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subhajit.elaris.core.ui.TestTags

@Composable
fun CanvasHomeRoute(
    onNavigateToSettings: () -> Unit,
    viewModel: CanvasHomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    CanvasHomeScreen(
        uiState = uiState,
        onNavigateToSettings = onNavigateToSettings,
        onWallpaperConfiguredChanged = viewModel::onWallpaperConfiguredChanged
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CanvasHomeScreen(
    uiState: CanvasHomeUiState,
    onNavigateToSettings: () -> Unit,
    onWallpaperConfiguredChanged: (Boolean) -> Unit
) {
    Scaffold(
        modifier = Modifier.testTag(TestTags.HOME_SCREEN),
        topBar = {
            TopAppBar(
                title = { Text("Shared Canvas Home") },
                actions = {
                    TextButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag(TestTags.HOME_SETTINGS_BUTTON)
                    ) {
                        Text("Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Home placeholder",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "This screen is the handoff point for future drawing, wallpaper, and sync workstreams.",
                style = MaterialTheme.typography.bodyLarge
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Session status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(text = "Environment: ${uiState.environmentLabel}")
                    Text(text = "Pairing: ${uiState.bootstrapState.pairingStatus.displayName}")
                    Text(text = "Session: ${uiState.bootstrapState.sessionDisplayState.displayName}")
                }
            }

            if (uiState.featureFlags.showWallpaperSetupCta) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Wallpaper setup placeholder",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Mark wallpaper as configured")
                                Text(
                                    text = if (uiState.bootstrapState.hasWallpaperConfigured) {
                                        "Local placeholder state says the wallpaper setup flow has been completed."
                                    } else {
                                        "Use this switch to simulate future wallpaper onboarding progress."
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = uiState.bootstrapState.hasWallpaperConfigured,
                                onCheckedChange = onWallpaperConfiguredChanged
                            )
                        }
                    }
                }
            }

            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Shared Canvas")
            }

            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Live Wallpaper Setup Coming Soon")
            }
        }
    }
}
