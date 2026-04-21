package com.subhajit.mulberry.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.data.bootstrap.PendingInviteSummary

data class InviteAcceptanceUiState(
    val pendingInvite: PendingInviteSummary? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

sealed interface InviteAcceptanceEffect {
    data object NavigateToBootstrap : InviteAcceptanceEffect
}

@Composable
fun InviteAcceptanceRoute(
    onNavigateToBootstrap: () -> Unit,
    viewModel: InviteAcceptanceViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect is InviteAcceptanceEffect.NavigateToBootstrap) {
                onNavigateToBootstrap()
            }
        }
    }

    InviteAcceptanceScreen(
        uiState = uiState,
        onAccept = viewModel::onAcceptClicked,
        onDecline = viewModel::onDeclineClicked
    )
}

@Composable
private fun InviteAcceptanceScreen(
    uiState: InviteAcceptanceUiState,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(TestTags.INVITE_ACCEPTANCE_SCREEN),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        val invite = uiState.pendingInvite
        Text(
            text = if (invite != null) {
                "${invite.inviterDisplayName} invited you to Mulberry"
            } else {
                "Invitation"
            },
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 80.dp)
        )

        invite?.let {
            Text(
                text = "Code ${it.code} is ready to connect ${it.inviterDisplayName} and ${it.recipientDisplayName}.",
                style = MaterialTheme.typography.bodyLarge
            )
        }

        uiState.errorMessage?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = onAccept,
            enabled = invite != null && !uiState.isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.INVITE_ACCEPT_BUTTON)
        ) {
            Text(if (uiState.isLoading) "Connecting..." else "Awesome, let's go")
        }

        TextButton(
            onClick = onDecline,
            enabled = invite != null && !uiState.isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.INVITE_DECLINE_BUTTON)
        ) {
            Text("Wrong partner? Disconnect")
        }
    }
}
