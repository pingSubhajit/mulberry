package com.subhajit.mulberry.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.ui.ApplySystemBarStyle
import com.subhajit.mulberry.core.ui.OnboardingPrivacyNotice
import com.subhajit.mulberry.core.ui.OnboardingTextField
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.core.ui.rememberOnboardingSystemBarStyle
import com.subhajit.mulberry.core.ui.metadata.MulberryUiMetadataProvider
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.mulberryAppColors

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

    ApplySystemBarStyle(rememberOnboardingSystemBarStyle())

    OnboardingNameScreen(
        uiState = uiState,
        onDisplayNameChanged = viewModel::onDisplayNameChanged,
        onContinue = viewModel::onContinueClicked,
        onEnterCode = viewModel::onEnterCodeClicked
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnboardingNameScreen(
    uiState: UserProfileDraft,
    onDisplayNameChanged: (String) -> Unit,
    onContinue: () -> Unit,
    onEnterCode: () -> Unit
) {
    val appColors = MaterialTheme.mulberryAppColors
    val isImeVisible = WindowInsets.isImeVisible
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .testTag(TestTags.ONBOARDING_NAME_SCREEN)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 66.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(MulberryUiMetadataProvider.brand.iconMarkDrawableRes),
                contentDescription = stringResource(MulberryUiMetadataProvider.brand.displayNameRes),
                modifier = Modifier.size(width = 48.dp, height = 72.dp)
            )
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = stringResource(R.string.onboarding_name_title),
                color = MaterialTheme.colorScheme.onBackground,
                style = TextStyle(
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 28.sp,
                    lineHeight = 38.sp
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(36.dp))
            OnboardingTextField(
                value = uiState.displayName,
                onValueChange = onDisplayNameChanged,
                label = stringResource(R.string.onboarding_name_label),
                placeholder = stringResource(R.string.onboarding_name_placeholder),
                modifier = Modifier.focusRequester(focusRequester),
                keyboardActions = KeyboardActions(onNext = {
                    if (uiState.isStepOneValid) {
                        onContinue()
                    }
                }),
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Next
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onContinue,
                enabled = uiState.isStepOneValid,
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
                    .testTag(TestTags.ONBOARDING_NAME_CONTINUE_BUTTON)
            ) {
                Text(
                    text = stringResource(R.string.onboarding_name_continue),
                    style = TextStyle(
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                        lineHeight = 24.sp
                    )
                )
            }
            Spacer(modifier = Modifier.height(34.dp))
            EnterCodePrompt(onEnterCode = onEnterCode)
        }

        if (!isImeVisible) {
            OnboardingPrivacyNotice(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 26.dp)
            )
        }
    }
}

@Composable
private fun EnterCodePrompt(onEnterCode: () -> Unit) {
    val appColors = MaterialTheme.mulberryAppColors
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onEnterCode
            )
            .testTag(TestTags.ONBOARDING_ENTER_CODE_BUTTON),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.onboarding_enter_code_prompt),
            color = appColors.mutedText,
            style = TextStyle(
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                lineHeight = 24.sp
            )
        )
        Text(
            text = " ${stringResource(R.string.onboarding_enter_code_action)}",
            color = MulberryPrimary,
            style = TextStyle(
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                lineHeight = 24.sp
            )
        )
    }
}
