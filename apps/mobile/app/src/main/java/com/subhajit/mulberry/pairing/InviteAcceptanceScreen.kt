package com.subhajit.mulberry.pairing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.ui.ApplySystemBarStyle
import com.subhajit.mulberry.core.ui.OnboardingLightSystemBars
import com.subhajit.mulberry.core.ui.OnboardingPrivacyNotice
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.data.bootstrap.PendingInviteSummary
import com.subhajit.mulberry.ui.theme.MulberryError
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily

private val InviteAcceptanceBackground = Color.White
private val InviteAcceptanceInk = Color(0xFF030A14)
private val HelpText = Color.Black.copy(alpha = 0.60f)
private val MutedText = Color.Black.copy(alpha = 0.40f)

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

    ApplySystemBarStyle(OnboardingLightSystemBars)

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
    val invite = uiState.pendingInvite
    val recipientName = invite?.recipientDisplayName
        ?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.home_default_user_name)
    val inviterName = invite?.inviterDisplayName
        ?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.app_name)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(InviteAcceptanceBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .testTag(TestTags.INVITE_ACCEPTANCE_SCREEN)
    ) {
        InviteAcceptanceTopBar(
            onNavigateBack = onDecline,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 11.dp)
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 107.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(36.dp)
        ) {
            InviteAcceptanceHeader(
                recipientName = recipientName,
                inviterName = inviterName
            )

            Image(
                painter = painterResource(R.drawable.invite_acceptance_couple),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(276.dp)
            )

            InviteAcceptanceActions(
                isEnabled = invite != null && !uiState.isLoading,
                isLoading = uiState.isLoading,
                errorMessage = uiState.errorMessage,
                onAccept = onAccept,
                onDecline = onDecline
            )
        }

        OnboardingPrivacyNotice(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp)
                .padding(bottom = 26.dp)
        )

        if (uiState.isLoading) {
            CircularProgressIndicator(
                color = MulberryPrimary,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun InviteAcceptanceTopBar(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(start = 4.dp, end = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BackButton(onClick = onNavigateBack)
        Text(
            text = stringResource(R.string.invite_code_help),
            color = HelpText,
            style = TextStyle(
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 24.sp
            )
        )
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val strokeWidth = 2.dp.toPx()
            drawLine(
                color = Color(0xFF46514D),
                start = center.copy(x = size.width * 0.22f),
                end = center.copy(x = size.width * 0.78f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Square
            )
            drawLine(
                color = Color(0xFF46514D),
                start = center.copy(x = size.width * 0.22f),
                end = center.copy(x = size.width * 0.44f, y = size.height * 0.28f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Square
            )
            drawLine(
                color = Color(0xFF46514D),
                start = center.copy(x = size.width * 0.22f),
                end = center.copy(x = size.width * 0.44f, y = size.height * 0.72f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Square
            )
        }
    }
}

@Composable
private fun InviteAcceptanceHeader(
    recipientName: String,
    inviterName: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Image(
            painter = painterResource(R.drawable.brand_iconmark_color),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(width = 48.dp, height = 72.dp)
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.invite_acceptance_welcome, recipientName),
                color = MulberryPrimary,
                style = TextStyle(
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    lineHeight = 27.sp
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(R.string.invite_acceptance_title, inviterName),
                color = InviteAcceptanceInk,
                style = TextStyle(
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 28.sp,
                    lineHeight = 32.sp
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun InviteAcceptanceActions(
    isEnabled: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Button(
            onClick = onAccept,
            enabled = isEnabled,
            shape = RoundedCornerShape(15.38.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MulberryPrimary,
                contentColor = Color.White,
                disabledContainerColor = MulberryPrimary.copy(alpha = 0.45f),
                disabledContentColor = Color.White.copy(alpha = 0.80f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag(TestTags.INVITE_ACCEPT_BUTTON)
        ) {
            Text(
                text = if (isLoading) {
                    stringResource(R.string.invite_acceptance_connecting)
                } else {
                    stringResource(R.string.invite_acceptance_continue)
                },
                style = TextStyle(
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    lineHeight = 24.sp
                )
            )
        }

        Text(
            text = buildAnnotatedString {
                append(stringResource(R.string.invite_acceptance_wrong_partner))
                append(" ")
                withStyle(SpanStyle(color = MulberryPrimary, fontWeight = FontWeight.SemiBold)) {
                    append(stringResource(R.string.invite_acceptance_disconnect))
                }
            },
            color = MutedText,
            style = TextStyle(
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 24.sp
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = isEnabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDecline
                )
                .testTag(TestTags.INVITE_DECLINE_BUTTON)
        )

        errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                color = MulberryError,
                style = TextStyle(
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
