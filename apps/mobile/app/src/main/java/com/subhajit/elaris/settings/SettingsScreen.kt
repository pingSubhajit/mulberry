package com.subhajit.elaris.settings

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subhajit.elaris.core.flags.FeatureFlag
import com.subhajit.elaris.core.ui.TestTags

@Composable
fun SettingsRoute(
    onNavigateBack: () -> Unit,
    onResetAppState: () -> Unit,
    onNavigateHome: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SettingsEffect.RestartFromBootstrap -> onResetAppState()
                SettingsEffect.NavigateHome -> onNavigateHome()
            }
        }
    }

    SettingsScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onResetAppState = viewModel::onResetAppState,
        onSeedDemoSession = viewModel::onSeedDemoSession,
        onFeatureFlagChanged = viewModel::onFeatureFlagChanged,
        onClearFeatureOverrides = viewModel::onClearFeatureOverrides
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    uiState: SettingsUiState,
    onNavigateBack: () -> Unit,
    onResetAppState: () -> Unit,
    onSeedDemoSession: () -> Unit,
    onFeatureFlagChanged: (FeatureFlag, Boolean) -> Unit,
    onClearFeatureOverrides: () -> Unit
) {
    Scaffold(
        modifier = Modifier.testTag(TestTags.SETTINGS_SCREEN),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text("Back")
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "App information",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(text = "Environment: ${uiState.environmentLabel}")
                    Text(text = "API Base URL: ${uiState.apiBaseUrl}")
                    Text(text = "Current pairing state: ${uiState.bootstrapState.pairingStatus.displayName}")
                }
            }

            Button(
                onClick = onResetAppState,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.SETTINGS_RESET_BUTTON)
            ) {
                Text("Reset App State")
            }

            if (uiState.enableDebugMenu) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.SETTINGS_DEVELOPER_SECTION)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Developer controls",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        FlagToggleRow(
                            title = "Show pairing placeholder controls",
                            checked = uiState.featureFlags.showPlaceholderPairingControls,
                            onCheckedChange = {
                                onFeatureFlagChanged(
                                    FeatureFlag.PLACEHOLDER_PAIRING_CONTROLS,
                                    it
                                )
                            }
                        )
                        FlagToggleRow(
                            title = "Show wallpaper setup CTA",
                            checked = uiState.featureFlags.showWallpaperSetupCta,
                            onCheckedChange = {
                                onFeatureFlagChanged(
                                    FeatureFlag.WALLPAPER_SETUP_CTA,
                                    it
                                )
                            }
                        )
                        FlagToggleRow(
                            title = "Show developer bootstrap actions",
                            checked = uiState.featureFlags.showDeveloperBootstrapActions,
                            onCheckedChange = {
                                onFeatureFlagChanged(
                                    FeatureFlag.DEVELOPER_BOOTSTRAP_ACTIONS,
                                    it
                                )
                            }
                        )
                        OutlinedButton(
                            onClick = onClearFeatureOverrides,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Reset Feature Flag Overrides")
                        }
                        OutlinedButton(
                            onClick = onSeedDemoSession,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Seed Demo Session")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlagToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
