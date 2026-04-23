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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subhajit.mulberry.core.ui.ApplySystemBarStyle
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.core.ui.metadata.AuthLandingMetadata
import com.subhajit.mulberry.core.ui.metadata.AuthProviderId
import com.subhajit.mulberry.core.ui.metadata.MulberryUiMetadataProvider
import com.subhajit.mulberry.ui.theme.MulberryDarkBackground
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.mulberryAppColors

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
    val appColors = MaterialTheme.mulberryAppColors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MulberryDarkBackground)
            .testTag(TestTags.AUTH_LANDING_SCREEN)
    ) {
        Image(
            painter = painterResource(metadata.backgroundDrawableRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(metadata.headlineRes),
                    color = Color.White,
                    style = TextStyle(
                        fontFamily = PoppinsFontFamily,
                        fontSize = 41.sp,
                        lineHeight = 46.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(metadata.subtitleRes),
                    color = Color.White,
                    style = TextStyle(
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Normal,
                        fontSize = 17.5.sp,
                        lineHeight = 25.sp,
                        letterSpacing = 0.25.sp
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(315.dp)
                )
            }

            uiState.errorMessage?.let {
                Surface(
                    color = appColors.authMessageSurface,
                    contentColor = Color(0xFFB31329),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = it,
                        style = TextStyle(
                            fontFamily = PoppinsFontFamily,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }

            Button(
                onClick = onGoogleSignIn,
                enabled = !uiState.isLoading && googleProvider.enabled,
                shape = RoundedCornerShape(15.38.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = appColors.authButtonSurface,
                    contentColor = appColors.authButtonContent,
                    disabledContainerColor = appColors.authButtonSurface.copy(alpha = 0.72f),
                    disabledContentColor = appColors.authButtonContent.copy(alpha = 0.72f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag(TestTags.AUTH_GOOGLE_BUTTON)
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            color = appColors.authButtonContent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(21.dp)
                        )
                    } else {
                        Image(
                            painter = painterResource(googleProvider.iconDrawableRes),
                            contentDescription = null,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(
                        text = if (uiState.isLoading) {
                            stringResource(com.subhajit.mulberry.R.string.auth_signing_in)
                        } else {
                            stringResource(googleProvider.labelRes)
                        },
                        style = TextStyle(
                            fontFamily = PoppinsFontFamily,
                            fontSize = 13.5.sp,
                            lineHeight = 24.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
