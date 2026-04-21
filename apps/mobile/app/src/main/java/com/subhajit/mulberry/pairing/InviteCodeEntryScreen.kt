package com.subhajit.mulberry.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.subhajit.mulberry.core.ui.TestTags

data class InviteCodeEntryUiState(
    val code: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null
)

sealed interface InviteCodeEntryEffect {
    data object NavigateToBootstrap : InviteCodeEntryEffect
}

@Composable
fun InviteCodeEntryRoute(
    onNavigateToBootstrap: () -> Unit,
    viewModel: InviteCodeEntryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect is InviteCodeEntryEffect.NavigateToBootstrap) {
                onNavigateToBootstrap()
            }
        }
    }

    InviteCodeEntryScreen(
        uiState = uiState,
        onCodeChanged = viewModel::onCodeChanged,
        onSubmit = viewModel::onSubmitClicked
    )
}

@Composable
private fun InviteCodeEntryScreen(
    uiState: InviteCodeEntryUiState,
    onCodeChanged: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(TestTags.INVITE_CODE_SCREEN),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "Let's get you set up",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 80.dp)
        )

        OutlinedTextField(
            value = uiState.code,
            onValueChange = { onCodeChanged(it.take(6).filter(Char::isDigit)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("6-digit invite code") }
        )

        uiState.errorMessage?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = onSubmit,
            enabled = uiState.code.length == 6 && !uiState.isSubmitting,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.INVITE_CODE_SUBMIT_BUTTON)
        ) {
            Text(if (uiState.isSubmitting) "Submitting..." else "Let's get going")
        }
    }
}
