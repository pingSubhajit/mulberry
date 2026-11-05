package com.subhajit.mulberry.auth

import com.subhajit.mulberry.analytics.OnboardingStep
import com.subhajit.mulberry.analytics.TrackOnboardingStep
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.ui.ApplySystemBarStyle
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.core.ui.isLargeText
import com.subhajit.mulberry.core.ui.mulberryTapScale
import com.subhajit.mulberry.core.ui.metadata.AuthLandingMetadata
import com.subhajit.mulberry.core.ui.metadata.AuthProviderId
import com.subhajit.mulberry.core.ui.metadata.AuthProviderMetadata
import com.subhajit.mulberry.core.ui.metadata.MulberryUiMetadataProvider
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.MulberryInk
import com.subhajit.mulberry.ui.theme.mulberryAppColors

data class AuthLandingUiState(
    val isLoading: Boolean = false,
    /** The button that is signing in, or null while the automatic Google sign-in runs. */
    val signingInWith: SignInMethod? = null,
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
    TrackOnboardingStep(OnboardingStep.WELCOME)
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

    LaunchedEffect(activity) {
        if (activity != null) {
            viewModel.onScreenShown(activity)
        }
    }

    val metadata = remember { MulberryUiMetadataProvider.authLanding }
    ApplySystemBarStyle(metadata.systemBarStyle)

    AuthLandingScreen(
        uiState = uiState,
        metadata = metadata,
        onSignIn = { method ->
            if (activity != null) {
                viewModel.onSignInClicked(activity, method)
            }
        }
    )
}

@Composable
private fun AuthLandingScreen(
    uiState: AuthLandingUiState,
    metadata: AuthLandingMetadata,
    onSignIn: (SignInMethod) -> Unit
) {
    val appColors = MaterialTheme.mulberryAppColors
    val largeText = isLargeText()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(TestTags.AUTH_LANDING_SCREEN)
    ) {
        Image(
            painter = painterResource(metadata.backgroundDrawableRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (largeText) {
            // A near-opaque cream wash keeps large text legible over the artwork, as on iPhone.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LargeTextWash)
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(top = 66.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.brand_iconmark_white),
                    contentDescription = stringResource(MulberryUiMetadataProvider.brand.displayNameRes),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 48.dp, height = 72.dp)
                )
                Spacer(modifier = Modifier.height(32.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(metadata.headlineRes),
                        color = MulberryInk,
                        style = TextStyle(
                            fontFamily = PoppinsFontFamily,
                            fontSize = 41.sp,
                            lineHeight = 46.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(metadata.subtitleRes),
                        color = MulberryInk,
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
            }

            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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

                metadata.providers.forEach { provider ->
                    val method = when (provider.id) {
                        AuthProviderId.GOOGLE -> SignInMethod.GOOGLE
                        AuthProviderId.APPLE -> SignInMethod.APPLE
                    }
                    SignInButton(
                        provider = provider,
                        method = method,
                        // The automatic Google sign-in shows on the Google button.
                        signingIn = uiState.isLoading &&
                            (uiState.signingInWith ?: SignInMethod.GOOGLE) == method,
                        enabled = !uiState.isLoading && provider.enabled,
                        onClick = { onSignIn(method) }
                    )
                }
            }
        }
    }
}

private val LargeTextWash = Color(red = 1f, green = 0.96f, blue = 0.93f, alpha = 0.94f)

/** Google's white button, and Apple's black one under it, the same size. */
@Composable
private fun SignInButton(
    provider: AuthProviderMetadata,
    method: SignInMethod,
    signingIn: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val appColors = MaterialTheme.mulberryAppColors
    val container = when (method) {
        SignInMethod.GOOGLE -> appColors.authButtonSurface
        SignInMethod.APPLE -> Color.Black
    }
    val content = when (method) {
        SignInMethod.GOOGLE -> appColors.authButtonContent
        SignInMethod.APPLE -> Color.White
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(15.38.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.72f),
            disabledContentColor = content.copy(alpha = 0.72f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .testTag(
                when (method) {
                    SignInMethod.GOOGLE -> TestTags.AUTH_GOOGLE_BUTTON
                    SignInMethod.APPLE -> TestTags.AUTH_APPLE_BUTTON
                }
            )
            .mulberryTapScale(enabled = enabled)
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (signingIn) {
                CircularProgressIndicator(
                    color = content,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(21.dp)
                )
            } else {
                Image(
                    painter = painterResource(provider.iconDrawableRes),
                    contentDescription = null,
                    modifier = Modifier.size(21.dp)
                )
            }
            Spacer(modifier = Modifier.size(10.dp))
            Text(
                text = if (signingIn) {
                    stringResource(R.string.auth_signing_in)
                } else {
                    stringResource(provider.labelRes)
                },
                style = TextStyle(
                    fontFamily = PoppinsFontFamily,
                    fontSize = 13.5.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
        }
    }
}
