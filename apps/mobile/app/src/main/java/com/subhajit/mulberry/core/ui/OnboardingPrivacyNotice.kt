package com.subhajit.mulberry.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subhajit.mulberry.R
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.mulberryAppColors

private enum class LegalDocument { PRIVACY, TERMS }

/** "By continuing you agree to our Terms of Use and Privacy Policy", as iOS and the Mac word it,
 * each link opening its document in a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingPrivacyNotice(modifier: Modifier = Modifier) {
    var document by remember { mutableStateOf<LegalDocument?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val appColors = MaterialTheme.mulberryAppColors
    val linkStyle = TextLinkStyles(style = SpanStyle(color = appColors.mutedText, fontWeight = FontWeight.SemiBold))
    val agreement = stringResource(R.string.privacy_policy_agreement)
    val terms = stringResource(R.string.terms_of_use)
    val and = stringResource(R.string.legal_and)
    val privacy = stringResource(R.string.privacy_policy)

    Text(
        text = buildAnnotatedString {
            append("$agreement ")
            withLink(LinkAnnotation.Clickable("terms", linkStyle) { document = LegalDocument.TERMS }) { append(terms) }
            append(" $and ")
            withLink(LinkAnnotation.Clickable("privacy", linkStyle) { document = LegalDocument.PRIVACY }) { append(privacy) }
        },
        color = appColors.subtleText,
        style = TextStyle(
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 10.sp,
            lineHeight = 16.sp
        ),
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth()
    )

    document?.let { shown ->
        ModalBottomSheet(
            onDismissRequest = { document = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            when (shown) {
                LegalDocument.PRIVACY -> PrivacyPolicySheetContent(onDismiss = { document = null })
                LegalDocument.TERMS -> TermsOfUseSheetContent(onDismiss = { document = null })
            }
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
            // Shorter at the large text sizes, so the title and button still fit in the sheet.
            modifier = Modifier
                .heightIn(max = if (isLargeText()) 240.dp else 420.dp)
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
                .mulberryTapScale()
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
