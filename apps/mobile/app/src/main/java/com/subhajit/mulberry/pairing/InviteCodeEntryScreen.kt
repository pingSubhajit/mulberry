package com.subhajit.mulberry.pairing

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.ui.ApplySystemBarStyle
import com.subhajit.mulberry.core.ui.OnboardingPrivacyNotice
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.core.ui.mulberryTapScale
import com.subhajit.mulberry.core.ui.rememberOnboardingSystemBarStyle
import com.subhajit.mulberry.ui.theme.MulberryError
import com.subhajit.mulberry.ui.theme.MulberryInk
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.mulberryAppColors
import kotlinx.coroutines.delay

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
    onNavigateBack: () -> Unit,
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

    ApplySystemBarStyle(rememberOnboardingSystemBarStyle())

    InviteCodeEntryScreen(
        uiState = uiState,
        onCodeChanged = viewModel::onCodeChanged,
        onSubmit = viewModel::onSubmitClicked,
        onNavigateBack = onNavigateBack
    )
}

@Composable
private fun InviteCodeEntryScreen(
    uiState: InviteCodeEntryUiState,
    onCodeChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val appColors = MaterialTheme.mulberryAppColors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .testTag(TestTags.INVITE_CODE_SCREEN)
    ) {
        InviteCodeTopBar(
            onNavigateBack = onNavigateBack,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 11.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 102.dp),
            verticalArrangement = Arrangement.spacedBy(35.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "(•••) •••",
                    color = MulberryPrimary,
                    style = TextStyle(
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                        lineHeight = 27.sp
                    )
                )
                Text(
                    text = stringResource(R.string.invite_code_title),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = TextStyle(
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 28.sp,
                        lineHeight = 38.sp
                    )
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                InviteCodeInput(
                    code = uiState.code,
                    onCodeChanged = { value ->
                        onCodeChanged(value.filter(Char::isDigit).take(6))
                    },
                    onSubmit = {
                        if (uiState.code.length == 6 && !uiState.isSubmitting) {
                            onSubmit()
                        }
                    }
                )

                Button(
                    onClick = onSubmit,
                    enabled = uiState.code.length == 6 && !uiState.isSubmitting,
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
                        .testTag(TestTags.INVITE_CODE_SUBMIT_BUTTON)
                        .mulberryTapScale(enabled = uiState.code.length == 6 && !uiState.isSubmitting)
                ) {
                    Text(
                        text = if (uiState.isSubmitting) {
                            "Submitting..."
                        } else {
                            stringResource(R.string.invite_code_continue)
                        },
                        style = TextStyle(
                            fontFamily = PoppinsFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp,
                            lineHeight = 24.sp
                        )
                    )
                }

                uiState.errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MulberryError,
                        style = TextStyle(
                            fontFamily = PoppinsFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        OnboardingPrivacyNotice(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp)
                .padding(bottom = 26.dp)
        )
    }
}

@Composable
private fun InviteCodeTopBar(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appColors = MaterialTheme.mulberryAppColors
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
            color = appColors.mutedText,
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
        val arrowColor = MaterialTheme.mulberryAppColors.iconMuted
        Canvas(modifier = Modifier.size(24.dp)) {
            val strokeWidth = 2.dp.toPx()
            drawLine(
                color = arrowColor,
                start = Offset(size.width * 0.68f, size.height * 0.18f),
                end = Offset(size.width * 0.28f, size.height * 0.50f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = arrowColor,
                start = Offset(size.width * 0.28f, size.height * 0.50f),
                end = Offset(size.width * 0.68f, size.height * 0.82f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = arrowColor,
                start = Offset(size.width * 0.30f, size.height * 0.50f),
                end = Offset(size.width * 0.86f, size.height * 0.50f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun InviteCodeInput(
    code: String,
    onCodeChanged: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var showCursor by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(530)
            showCursor = !showCursor
        }
    }

    BasicTextField(
        value = code,
        onValueChange = onCodeChanged,
        singleLine = true,
        textStyle = TextStyle(color = Color.Transparent),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        decorationBox = { innerTextField ->
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(6) { index ->
                        CodeCell(
                            digit = code.getOrNull(index),
                            isActive = index == code.length.coerceAtMost(5) && code.length < 6,
                            showCursor = showCursor
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(1.dp)
                        .alpha(0f)
                ) {
                    innerTextField()
                }
            }
        }
    )
}

@Composable
private fun CodeCell(
    digit: Char?,
    isActive: Boolean,
    showCursor: Boolean
) {
    Row(
        modifier = Modifier
            .size(width = 51.dp, height = 66.dp)
            .background(MaterialTheme.mulberryAppColors.inputSurface, RoundedCornerShape(15.38.dp)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isActive) {
            Box(
                modifier = Modifier
                    .size(width = 2.dp, height = 32.dp)
                    .alpha(if (showCursor) 1f else 0f)
                    .background(MulberryPrimary)
            )
        }
        Text(
            text = digit?.toString() ?: "0",
            color = if (digit == null) MaterialTheme.mulberryAppColors.subtleText else MulberryInk,
            style = TextStyle(
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 32.sp,
                lineHeight = 24.sp
            )
        )
    }
}
