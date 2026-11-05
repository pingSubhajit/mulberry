package com.subhajit.mulberry.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subhajit.mulberry.R
import com.subhajit.mulberry.auth.SignInMethod
import com.subhajit.mulberry.core.ui.mulberryTapScale
import com.subhajit.mulberry.sync.SyncState
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.mulberryAppColors

/** Where the shared canvas is in its sync, in the words the iPhone uses. */
internal enum class SyncStatus(val label: String) {
    Connected("Connected"),
    Connecting("Connecting"),
    Offline("Offline"),
    /** Catching up after the pairing changed or the canvas fell behind. */
    Updating("Updating"),
    NotPaired("Not paired");

    companion object {
        fun of(paired: Boolean, syncState: SyncState): SyncStatus = when {
            !paired -> NotPaired
            syncState == SyncState.Connected -> Connected
            syncState == SyncState.Connecting -> Connecting
            syncState == SyncState.Recovering -> Updating
            else -> Offline
        }
    }
}

private val SyncStatus.dotColor: Color
    get() = when (this) {
        SyncStatus.Connected -> Color(0xFF00A53C)
        SyncStatus.Connecting, SyncStatus.Updating -> Color(0xFFF2B200)
        SyncStatus.Offline, SyncStatus.NotPaired -> Color(0xFF9E9E9E)
    }

/** A coloured dot and the status, as the iPhone's Settings shows it. */
@Composable
internal fun SyncStatusLabel(status: SyncStatus, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(status.dotColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = status.label,
            color = MaterialTheme.mulberryAppColors.mutedText,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    }
}

/** The quiet red link under Sign out, or a small spinner while the account is being deleted. */
@Composable
internal fun DeleteAccountAction(
    deleting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        if (deleting) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.error,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(18.dp)
                    .semantics { contentDescription = "Deleting your account" }
            )
        } else {
            Text(
                text = "Delete your account",
                color = MaterialTheme.colorScheme.error.copy(alpha = if (enabled) 1f else 0.5f),
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            )
        }
    }
}

/** Settings → Profile → Sign in: each way into the account, with a check once linked, or Link. */
@Composable
internal fun SignInMethodsSection(
    linked: Set<SignInMethod>,
    linking: SignInMethod?,
    enabled: Boolean,
    onLink: (SignInMethod) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Sign in",
            color = MaterialTheme.mulberryAppColors.mutedText,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(15.38.dp))
                .background(MaterialTheme.mulberryAppColors.inputSurface),
            verticalArrangement = Arrangement.Top
        ) {
            SignInMethod.entries.forEach { method ->
                SignInMethodRow(
                    method = method,
                    linked = method in linked,
                    linking = linking == method,
                    enabled = enabled,
                    onLink = { onLink(method) }
                )
            }
        }
    }
}

@Composable
private fun SignInMethodRow(
    method: SignInMethod,
    linked: Boolean,
    linking: Boolean,
    enabled: Boolean,
    onLink: () -> Unit
) {
    val name = when (method) {
        SignInMethod.GOOGLE -> "Google"
        SignInMethod.APPLE -> "Apple"
    }
    val ink = MaterialTheme.colorScheme.onSurface
    val clickable = !linked && !linking && enabled
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                if (linked) {
                    Modifier.clearAndSetSemantics { contentDescription = "$name, linked" }
                } else {
                    Modifier
                        .mulberryTapScale(enabled = clickable)
                        .clickable(enabled = clickable, role = Role.Button, onClick = onLink)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Link $name"
                        }
                }
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (method) {
            SignInMethod.GOOGLE -> Image(
                painter = painterResource(R.drawable.ic_google_logo),
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            SignInMethod.APPLE -> Image(
                painter = painterResource(R.drawable.ic_apple_logo),
                contentDescription = null,
                colorFilter = ColorFilter.tint(ink),
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = name,
            color = ink,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            modifier = Modifier.weight(1f)
        )
        when {
            linked -> Image(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.mulberryAppColors.mutedText),
                modifier = Modifier.size(20.dp)
            )
            linking -> CircularProgressIndicator(
                color = MulberryPrimary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
            )
            else -> Text(
                text = "Link",
                color = MulberryPrimary.copy(alpha = if (enabled) 1f else 0.5f),
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                lineHeight = 20.sp
            )
        }
    }
}
