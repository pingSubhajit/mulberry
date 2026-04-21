package com.subhajit.mulberry.auth

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subhajit.mulberry.core.ui.ApplySystemBarStyle
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.core.ui.metadata.AuthLandingMetadata
import com.subhajit.mulberry.core.ui.metadata.AuthProviderId
import com.subhajit.mulberry.core.ui.metadata.MulberryUiMetadataProvider

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

    val metadata = remember { MulberryUiMetadataProvider.authLanding }
    ApplySystemBarStyle(metadata.systemBarStyle)

    AuthLandingScreen(
        uiState = uiState,
        metadata = metadata,
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
    metadata: AuthLandingMetadata,
    onGoogleSignIn: () -> Unit
) {
    val googleProvider = metadata.providers.first { it.id == AuthProviderId.GOOGLE }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070009))
            .testTag(TestTags.AUTH_LANDING_SCREEN)
    ) {
        Image(
            painter = painterResource(metadata.backgroundDrawableRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xEE070009),
                            0.36f to Color(0xAA070009),
                            0.68f to Color(0x16070009),
                            1.0f to Color(0x44070009)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(metadata.headlineRes),
                    color = Color.White,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(metadata.subtitleRes),
                    color = Color.White.copy(alpha = 0.92f),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                )
            }

            uiState.errorMessage?.let {
                Surface(
                    color = Color(0xDFFFFFFF),
                    contentColor = MaterialTheme.colorScheme.error,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }

            Button(
                onClick = onGoogleSignIn,
                enabled = !uiState.isLoading && googleProvider.enabled,
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF5F5D62),
                    disabledContainerColor = Color.White.copy(alpha = 0.72f),
                    disabledContentColor = Color(0xFF5F5D62).copy(alpha = 0.72f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .testTag(TestTags.AUTH_GOOGLE_BUTTON)
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            color = Color(0xFF5F5D62),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Image(
                            painter = painterResource(googleProvider.iconDrawableRes),
                            contentDescription = null,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.size(18.dp))
                    Text(
                        text = if (uiState.isLoading) {
                            stringResource(com.subhajit.mulberry.R.string.auth_signing_in)
                        } else {
                            stringResource(googleProvider.labelRes)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
