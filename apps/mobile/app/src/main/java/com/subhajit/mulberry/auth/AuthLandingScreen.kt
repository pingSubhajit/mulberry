package com.subhajit.mulberry.auth

import androidx.activity.ComponentActivity
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subhajit.mulberry.core.ui.TestTags

data class AuthLandingUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

sealed interface AuthLandingEffect {
    data object NavigateToBootstrap : AuthLandingEffect
}

@Composable
fun AuthLandingRoute(
    onNavigateToBootstrap: () -> Unit,
    viewModel: AuthLandingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect is AuthLandingEffect.NavigateToBootstrap) {
                onNavigateToBootstrap()
            }
        }
    }

    AuthLandingScreen(
        uiState = uiState,
        onGoogleSignIn = {
            if (activity != null) {
                viewModel.onGoogleSignInClicked(activity)
            }
        }
    )
}

@Composable
private fun AuthLandingScreen(
    uiState: AuthLandingUiState,
    onGoogleSignIn: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(TestTags.AUTH_LANDING_SCREEN),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(
                text = "Connect with your significant other",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Share a canvas with your partner that lives on your lock-screen.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 12.dp)
            )
            uiState.errorMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }

        Button(
            onClick = onGoogleSignIn,
            enabled = !uiState.isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.AUTH_GOOGLE_BUTTON)
        ) {
            Text(if (uiState.isLoading) "Signing in..." else "Continue with Google")
        }
    }
}
