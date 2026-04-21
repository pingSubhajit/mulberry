package com.subhajit.mulberry.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

sealed interface OnboardingNameEffect {
    data object NavigateToDetails : OnboardingNameEffect
    data object NavigateToCodeEntry : OnboardingNameEffect
}

@Composable
fun OnboardingNameRoute(
    onNavigateToDetails: () -> Unit,
    onNavigateToCodeEntry: () -> Unit,
    viewModel: OnboardingNameViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                OnboardingNameEffect.NavigateToCodeEntry -> onNavigateToCodeEntry()
                OnboardingNameEffect.NavigateToDetails -> onNavigateToDetails()
            }
        }
    }

    OnboardingNameScreen(
        uiState = uiState,
        onDisplayNameChanged = viewModel::onDisplayNameChanged,
        onContinue = viewModel::onContinueClicked,
        onEnterCode = viewModel::onEnterCodeClicked
    )
}

@Composable
private fun OnboardingNameScreen(
    uiState: UserProfileDraft,
    onDisplayNameChanged: (String) -> Unit,
    onContinue: () -> Unit,
    onEnterCode: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(TestTags.ONBOARDING_NAME_SCREEN),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "Welcome to Mulberry! Let's get you set up",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 80.dp)
        )

        OutlinedTextField(
            value = uiState.displayName,
            onValueChange = onDisplayNameChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("What is your name?") },
            placeholder = { Text("Enter the name your partner calls you") }
        )

        Button(
            onClick = onContinue,
            enabled = uiState.isStepOneValid,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.ONBOARDING_NAME_CONTINUE_BUTTON)
        ) {
            Text("Alright, next")
        }

        TextButton(
            onClick = onEnterCode,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.ONBOARDING_ENTER_CODE_BUTTON)
        ) {
            Text("Have a code? Enter code")
        }
    }
}
