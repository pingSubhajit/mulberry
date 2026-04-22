package com.subhajit.mulberry.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.subhajit.mulberry.core.ui.OnboardingLightSystemBars
import com.subhajit.mulberry.core.ui.OnboardingPrivacyNotice
import com.subhajit.mulberry.core.ui.OnboardingTextField
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.core.ui.metadata.MulberryUiMetadataProvider
import com.subhajit.mulberry.ui.theme.MulberryError
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val OnboardingBackground = Color.White
private val HeadingInk = Color(0xFF030A14)

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

    ApplySystemBarStyle(OnboardingLightSystemBars)

    OnboardingDetailsScreen(
        uiState = uiState,
        onPartnerNameChanged = viewModel::onPartnerNameChanged,
        onAnniversaryChanged = viewModel::onAnniversaryChanged,
        onSubmit = viewModel::onSubmitClicked
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingDetailsScreen(
    uiState: OnboardingDetailsUiState,
    onPartnerNameChanged: (String) -> Unit,
    onAnniversaryChanged: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val showDatePicker = remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = uiState.draft.anniversaryDate.toUtcDateMillis()
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OnboardingBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .testTag(TestTags.ONBOARDING_DETAILS_SCREEN)
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
            Spacer(modifier = Modifier.height(61.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(
                        R.string.onboarding_details_welcome,
                        uiState.draft.displayName.ifBlank { stringResource(R.string.app_name) }
                    ),
                    color = MulberryPrimary,
                    style = TextStyle(
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                        lineHeight = 27.sp
                    )
                )
                Text(
                    text = stringResource(R.string.onboarding_details_title),
                    color = HeadingInk,
                    style = TextStyle(
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 28.sp,
                        lineHeight = 38.sp
                    )
                )
            }
            Spacer(modifier = Modifier.height(50.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                OnboardingTextField(
                    value = uiState.draft.partnerDisplayName,
                    onValueChange = onPartnerNameChanged,
                    label = stringResource(R.string.onboarding_partner_name_label),
                    placeholder = stringResource(R.string.onboarding_partner_name_placeholder),
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next
                )

                OnboardingTextField(
                    value = uiState.draft.anniversaryDate.toDisplayAnniversaryDate(),
                    onValueChange = {},
                    label = stringResource(R.string.onboarding_anniversary_label),
                    placeholder = stringResource(R.string.onboarding_anniversary_placeholder),
                    readOnly = true,
                    keyboardActions = KeyboardActions.Default,
                    onClick = { showDatePicker.value = true }
                )

                Button(
                    onClick = onSubmit,
                    enabled = uiState.draft.isComplete && !uiState.isSubmitting,
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
                        .testTag(TestTags.ONBOARDING_DETAILS_SUBMIT_BUTTON)
                ) {
                    Text(
                        text = if (uiState.isSubmitting) {
                            stringResource(R.string.onboarding_details_submitting)
                        } else {
                            stringResource(R.string.onboarding_details_continue)
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

        if (showDatePicker.value) {
            DatePickerDialog(
                onDismissRequest = { showDatePicker.value = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            datePickerState.selectedDateMillis
                                ?.toAnniversaryDate()
                                ?.let(onAnniversaryChanged)
                            showDatePicker.value = false
                        }
                    ) {
                        Text(stringResource(R.string.onboarding_date_picker_select))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker.value = false }) {
                        Text(stringResource(R.string.onboarding_date_picker_cancel))
                    }
                }
            ) {
                DatePicker(state = datePickerState)
            }
        }
    }
}

private val anniversaryStorageDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

private val anniversaryDisplayDateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

private fun Long.toAnniversaryDate(): String = anniversaryStorageDateFormat.format(Date(this))

private fun String.toUtcDateMillis(): Long? = runCatching {
    if (isBlank()) null else anniversaryStorageDateFormat.parse(this)?.time
}.getOrNull()

private fun String.toDisplayAnniversaryDate(): String = runCatching {
    if (isBlank()) {
        ""
    } else {
        anniversaryStorageDateFormat.parse(this)?.let(anniversaryDisplayDateFormat::format).orEmpty()
    }
}.getOrDefault("")
