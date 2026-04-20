package com.subhajit.elaris.onboarding

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
import com.subhajit.elaris.core.ui.TestTags

data class OnboardingDetailsUiState(
    val draft: UserProfileDraft = UserProfileDraft(),
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null
)

sealed interface OnboardingDetailsEffect {
    data object NavigateToBootstrap : OnboardingDetailsEffect
}

@Composable
fun OnboardingDetailsRoute(
    onNavigateToBootstrap: () -> Unit,
    viewModel: OnboardingDetailsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect is OnboardingDetailsEffect.NavigateToBootstrap) {
                onNavigateToBootstrap()
            }
        }
    }

    OnboardingDetailsScreen(
        uiState = uiState,
        onPartnerNameChanged = viewModel::onPartnerNameChanged,
        onAnniversaryChanged = viewModel::onAnniversaryChanged,
        onSubmit = viewModel::onSubmitClicked
    )
}

@Composable
private fun OnboardingDetailsScreen(
    uiState: OnboardingDetailsUiState,
    onPartnerNameChanged: (String) -> Unit,
    onAnniversaryChanged: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(TestTags.ONBOARDING_DETAILS_SCREEN),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "Welcome ${uiState.draft.displayName}. A few more things",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 80.dp)
        )

        OutlinedTextField(
            value = uiState.draft.partnerDisplayName,
            onValueChange = onPartnerNameChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("What is your partner's name?") }
        )

        OutlinedTextField(
            value = uiState.draft.anniversaryDate,
            onValueChange = onAnniversaryChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Relationship anniversary") },
            placeholder = { Text("YYYY-MM-DD") }
        )

        uiState.errorMessage?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = onSubmit,
            enabled = uiState.draft.isComplete && !uiState.isSubmitting,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.ONBOARDING_DETAILS_SUBMIT_BUTTON)
        ) {
            Text(if (uiState.isSubmitting) "Submitting..." else "Let's get going")
        }
    }
}
