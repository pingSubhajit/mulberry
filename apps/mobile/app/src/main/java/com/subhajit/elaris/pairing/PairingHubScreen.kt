package com.subhajit.elaris.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

data class PairingHubUiState(
    val currentInvite: CreateInviteResult? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

sealed interface PairingHubEffect {
    data object NavigateToCodeEntry : PairingHubEffect
}

@Composable
fun PairingHubRoute(
    onNavigateToCodeEntry: () -> Unit,
    viewModel: PairingHubViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect is PairingHubEffect.NavigateToCodeEntry) {
                onNavigateToCodeEntry()
            }
        }
    }

    PairingHubScreen(
        uiState = uiState,
        onCreateCode = viewModel::onCreateCodeClicked,
        onEnterCode = viewModel::onEnterCodeClicked
    )
}

@Composable
private fun PairingHubScreen(
    uiState: PairingHubUiState,
    onCreateCode: () -> Unit,
    onEnterCode: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(TestTags.PAIRING_HUB_SCREEN),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "Let's get you set up",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 80.dp)
        )

        uiState.currentInvite?.let { invite ->
            Text(
                text = "Current invite code: ${invite.code}",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(text = "Expires at: ${invite.expiresAt}")
        }

        uiState.errorMessage?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = onCreateCode,
            enabled = !uiState.isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.PAIRING_CREATE_CODE_BUTTON)
        ) {
            Text(if (uiState.isLoading) "Creating..." else "Generate Invite Code")
        }

        Button(
            onClick = onEnterCode,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.PAIRING_ENTER_CODE_BUTTON)
        ) {
            Text("Enter Code")
        }
    }
}
