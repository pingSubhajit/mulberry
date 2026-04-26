package com.subhajit.mulberry.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subhajit.mulberry.R
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.mulberryAppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingPrivacyNotice(modifier: Modifier = Modifier) {
    var showPolicy by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val appColors = MaterialTheme.mulberryAppColors

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${stringResource(R.string.privacy_policy_agreement)} ",
            color = appColors.subtleText,
            style = TextStyle(
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 10.sp,
                lineHeight = 24.sp
            ),
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.privacy_policy),
            color = appColors.mutedText,
            style = TextStyle(
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                lineHeight = 24.sp
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier.clickable(onClick = { showPolicy = true })
        )
    }

    if (showPolicy) {
        ModalBottomSheet(
            onDismissRequest = { showPolicy = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            PrivacyPolicySheetContent(onDismiss = { showPolicy = false })
        }
    }
}

@Composable
fun PrivacyPolicySheetContent(onDismiss: () -> Unit) {
    LegalDocumentSheetContent(
        title = stringResource(R.string.privacy_policy_sheet_title),
        updated = stringResource(R.string.privacy_policy_sheet_updated),
        rawResourceId = R.raw.privacy_policy,
        onDismiss = onDismiss
    )
}

@Composable
fun TermsOfUseSheetContent(onDismiss: () -> Unit) {
    LegalDocumentSheetContent(
        title = "Terms of Use",
        updated = "Last updated: April 23, 2026",
        rawResourceId = R.raw.terms_of_use,
        onDismiss = onDismiss
    )
}

@Composable
private fun LegalDocumentSheetContent(
    title: String,
    updated: String,
    rawResourceId: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val appColors = MaterialTheme.mulberryAppColors
    val documentText = remember(rawResourceId) {
        context.resources.openRawResource(rawResourceId)
            .bufferedReader()
            .use { it.readText() }
            .trim()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = TextStyle(
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 24.sp,
                    lineHeight = 31.sp
                )
            )
            Text(
                text = updated,
                color = appColors.mutedText,
                style = TextStyle(
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            )
        }

        Text(
            text = documentText,
            color = appColors.mutedText,
            style = TextStyle(
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 21.sp
            ),
            modifier = Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
        )

        Button(
            onClick = onDismiss,
            shape = RoundedCornerShape(15.38.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MulberryPrimary,
                contentColor = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
        ) {
            Text(
                text = stringResource(R.string.privacy_policy_sheet_close),
                style = TextStyle(
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    lineHeight = 24.sp
                )
            )
        }
    }
}
