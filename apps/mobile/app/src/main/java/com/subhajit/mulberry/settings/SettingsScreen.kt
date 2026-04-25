package com.subhajit.mulberry.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.subhajit.mulberry.core.flags.FeatureFlag
import com.subhajit.mulberry.core.ui.PrivacyPolicySheetContent
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.data.bootstrap.AuthStatus
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.sync.SyncState
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.MulberryPrimaryLight
import com.subhajit.mulberry.ui.theme.MulberrySurfaceVariant
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.mulberryAppColors

private enum class SettingsPane {
    Home,
    Profile,
    Partner,
    PrivacyLegal,
    About,
    DeveloperOptions
}

@Composable
fun SettingsRoute(
    onNavigateBack: () -> Unit,
    onResetAppState: () -> Unit,
    onNavigateHome: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pane by remember { mutableStateOf(SettingsPane.Home) }

    BackHandler {
        if (pane == SettingsPane.Home) {
            onNavigateBack()
        } else {
            pane = SettingsPane.Home
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SettingsEffect.RestartFromBootstrap -> onResetAppState()
                SettingsEffect.NavigateHome -> onNavigateHome()
                is SettingsEffect.Message -> snackbarHostState.showSnackbar(effect.text)
            }
        }
    }

    SettingsScreen(
        uiState = uiState,
        pane = pane,
        snackbarHostState = snackbarHostState,
        onNavigateBack = {
            if (pane == SettingsPane.Home) onNavigateBack() else pane = SettingsPane.Home
        },
        onPaneSelected = { pane = it },
        onLogout = viewModel::onLogout,
        onDisplayNameSave = viewModel::onDisplayNameSave,
        onDisconnectPartner = viewModel::onDisconnectPartner,
        onPartnerProfileSave = viewModel::onPartnerProfileSave,
        onResetAppState = viewModel::onResetAppState,
        onSeedDemoSession = viewModel::onSeedDemoSession,
        onFeatureFlagChanged = viewModel::onFeatureFlagChanged,
        onClearFeatureOverrides = viewModel::onClearFeatureOverrides,
        onVersionTapped = viewModel::onVersionTapped,
        onDeveloperOptionsEnabledChanged = {
            viewModel.onDeveloperOptionsEnabledChanged(it)
            if (!it) pane = SettingsPane.Home
        },
        onForceSyncNow = viewModel::onForceSyncNow,
        onRegenerateWallpaperSnapshot = viewModel::onRegenerateWallpaperSnapshot,
        onSendDebugPairingNotification = viewModel::onSendDebugPairingNotification
    )
}

@Composable
private fun SettingsScreen(
    uiState: SettingsUiState,
    pane: SettingsPane,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onPaneSelected: (SettingsPane) -> Unit,
    onLogout: () -> Unit,
    onDisplayNameSave: (String) -> Unit,
    onDisconnectPartner: () -> Unit,
    onPartnerProfileSave: (String, String) -> Unit,
    onResetAppState: () -> Unit,
    onSeedDemoSession: () -> Unit,
    onFeatureFlagChanged: (FeatureFlag, Boolean) -> Unit,
    onClearFeatureOverrides: () -> Unit,
    onVersionTapped: () -> Unit,
    onDeveloperOptionsEnabledChanged: (Boolean) -> Unit,
    onForceSyncNow: () -> Unit,
    onRegenerateWallpaperSnapshot: () -> Unit,
    onSendDebugPairingNotification: () -> Unit
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag(TestTags.SETTINGS_SCREEN),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            SettingsHeader(
                title = pane.title(),
                subtitle = pane.subtitle(),
                showBack = pane != SettingsPane.Home,
                onBack = onNavigateBack
            )

            when (pane) {
                SettingsPane.Home -> SettingsHomePane(uiState, onPaneSelected)
                SettingsPane.Profile -> ProfilePane(
                    uiState = uiState,
                    onLogout = onLogout,
                    onDisplayNameSave = onDisplayNameSave
                )
                SettingsPane.Partner -> PartnerPane(
                    uiState = uiState,
                    onDisconnectPartner = onDisconnectPartner,
                    onPartnerProfileSave = onPartnerProfileSave
                )
                SettingsPane.PrivacyLegal -> PrivacyLegalPane()
                SettingsPane.About -> AboutPane(uiState, onVersionTapped)
                SettingsPane.DeveloperOptions -> DeveloperOptionsPane(
                    uiState = uiState,
                    onDeveloperOptionsEnabledChanged = onDeveloperOptionsEnabledChanged,
                    onForceSyncNow = onForceSyncNow,
                    onRegenerateWallpaperSnapshot = onRegenerateWallpaperSnapshot,
                    onClearFeatureOverrides = onClearFeatureOverrides,
                    onSeedDemoSession = onSeedDemoSession,
                    onFeatureFlagChanged = onFeatureFlagChanged,
                    onResetAppState = onResetAppState,
                    onSendDebugPairingNotification = onSendDebugPairingNotification
                )
            }
        }
    }
}

@Composable
private fun SettingsHeader(
    title: String,
    subtitle: String,
    showBack: Boolean,
    onBack: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showBack) {
            Text(
                text = "Back",
                color = MulberryPrimary,
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.clickable(onClick = onBack)
            )
        }
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 34.sp,
            lineHeight = 42.sp,
            letterSpacing = (-0.5).sp
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                color = MaterialTheme.mulberryAppColors.mutedText,
                fontFamily = PoppinsFontFamily,
                fontSize = 14.sp,
                lineHeight = 21.sp
            )
        }
    }
}

@Composable
private fun SettingsHomePane(
    uiState: SettingsUiState,
    onPaneSelected: (SettingsPane) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsNavCard(
            title = "Profile",
            body = uiState.bootstrapState.userDisplayName ?: "Signed in profile",
            leading = {
                SettingsAvatar(
                    photoUrl = uiState.bootstrapState.userPhotoUrl,
                    displayName = uiState.bootstrapState.userDisplayName,
                    size = 46.dp
                )
            },
            onClick = { onPaneSelected(SettingsPane.Profile) }
        )
        SettingsNavCard(
            title = "Partner",
            body = uiState.bootstrapState.partnerDisplayName
                ?: if (uiState.bootstrapState.pairingStatus == PairingStatus.PAIRED) {
                    "Partner details"
                } else {
                    "Not paired yet"
                },
            leading = {
                SettingsAvatar(
                    photoUrl = uiState.bootstrapState.partnerPhotoUrl,
                    displayName = uiState.bootstrapState.partnerDisplayName ?: "?",
                    size = 46.dp,
                    muted = uiState.bootstrapState.pairingStatus != PairingStatus.PAIRED
                )
            },
            onClick = { onPaneSelected(SettingsPane.Partner) }
        )
        SyncStatusCard(uiState)
        SettingsNavCard(
            title = "Privacy & Legal",
            body = "Privacy policy and data use",
            onClick = { onPaneSelected(SettingsPane.PrivacyLegal) }
        )
        SettingsNavCard(
            title = "About",
            body = "Mulberry ${uiState.appVersionName}",
            onClick = { onPaneSelected(SettingsPane.About) }
        )
        if (uiState.developerOptionsEnabled) {
            SettingsNavCard(
                title = "Developer Options",
                body = "Diagnostics, sync tools, and reset",
                onClick = { onPaneSelected(SettingsPane.DeveloperOptions) }
            )
        }
    }
}

@Composable
private fun ProfilePane(
    uiState: SettingsUiState,
    onLogout: () -> Unit,
    onDisplayNameSave: (String) -> Unit
) {
    var showLogoutConfirmation by remember { mutableStateOf(false) }
    var displayName by remember(uiState.bootstrapState.userDisplayName) {
        mutableStateOf(uiState.bootstrapState.userDisplayName.orEmpty())
    }
    val inputColor = MaterialTheme.colorScheme.onSurface
    val placeholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.34f)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        IdentityCard(
            photoUrl = uiState.bootstrapState.userPhotoUrl,
            title = uiState.bootstrapState.userDisplayName ?: "Mulberry user",
            body = uiState.bootstrapState.userEmail ?: "Google account",
            muted = false
        )
        InfoCard(
            rows = listOf(
                "User ID" to (uiState.bootstrapState.userId ?: "Unavailable"),
                "Auth state" to uiState.bootstrapState.authStatus.displayName
            )
        )
        SoftCard {
            Text(
                text = "Profile name",
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Your name", fontFamily = PoppinsFontFamily) },
                placeholder = {
                    Text(
                        text = "Enter your name",
                        color = placeholderColor,
                        fontFamily = PoppinsFontFamily
                    )
                },
                enabled = !uiState.isBusy,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    cursorColor = inputColor,
                    focusedTextColor = inputColor,
                    unfocusedTextColor = inputColor,
                    disabledTextColor = inputColor.copy(alpha = 0.45f)
                )
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { onDisplayNameSave(displayName.trim()) },
                enabled = !uiState.isBusy &&
                    displayName.isNotBlank() &&
                    displayName.trim() != uiState.bootstrapState.userDisplayName.orEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(15.38.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MulberryPrimary)
            ) {
                Text(
                    text = if (uiState.isBusy) "Saving..." else "Save profile name",
                    color = Color.White,
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        if (uiState.bootstrapState.authStatus != AuthStatus.SIGNED_OUT) {
            OutlinedButton(
                onClick = { showLogoutConfirmation = true },
                enabled = !uiState.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag(TestTags.SETTINGS_LOGOUT_BUTTON),
                shape = RoundedCornerShape(15.38.dp)
            ) {
                Text("Sign out", fontFamily = PoppinsFontFamily)
            }
        }
    }

    if (showLogoutConfirmation) {
        ConfirmationDialog(
            title = "Sign out?",
            body = "You can sign back in with Google any time.",
            confirmText = "Sign out",
            onDismiss = { showLogoutConfirmation = false },
            onConfirm = {
                showLogoutConfirmation = false
                onLogout()
            }
        )
    }
}

@Composable
private fun PartnerPane(
    uiState: SettingsUiState,
    onDisconnectPartner: () -> Unit,
    onPartnerProfileSave: (String, String) -> Unit
) {
    var showDisconnectConfirmation by remember { mutableStateOf(false) }
    var partnerName by remember(uiState.bootstrapState.partnerDisplayName) {
        mutableStateOf(uiState.bootstrapState.partnerDisplayName.orEmpty())
    }
    var anniversaryDate by remember(uiState.bootstrapState.anniversaryDate) {
        mutableStateOf(uiState.bootstrapState.anniversaryDate.toMaskedAnniversaryDate())
    }
    val isPaired = uiState.bootstrapState.pairingStatus == PairingStatus.PAIRED
    val cooldownText = uiState.bootstrapState.partnerProfileNextUpdateAt
        ?.let { "Editable again at $it" }
    val anniversaryPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.34f)
    val inputColor = MaterialTheme.colorScheme.onSurface

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        IdentityCard(
            photoUrl = uiState.bootstrapState.partnerPhotoUrl,
            title = uiState.bootstrapState.partnerDisplayName ?: "No partner connected",
            body = if (isPaired) "Connected partner" else "Invite your partner from Home",
            muted = !isPaired
        )
        InfoCard(
            rows = listOf(
                "Pairing status" to uiState.bootstrapState.pairingStatus.displayName,
                "Relationship anniversary" to (uiState.bootstrapState.anniversaryDate ?: "Not set"),
                "Pair session" to (uiState.bootstrapState.pairSessionId ?: "Unavailable")
            )
        )
        SoftCard {
            Text(
                text = "Partner details",
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = partnerName,
                onValueChange = { partnerName = it },
                label = { Text("Partner name", fontFamily = PoppinsFontFamily) },
                placeholder = {
                    Text(
                        text = "Enter partner name",
                        color = anniversaryPlaceholderColor,
                        fontFamily = PoppinsFontFamily
                    )
                },
                enabled = !uiState.isBusy && cooldownText == null,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    cursorColor = inputColor,
                    focusedTextColor = inputColor,
                    unfocusedTextColor = inputColor,
                    disabledTextColor = inputColor.copy(alpha = 0.45f)
                )
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = TextFieldValue(
                    text = anniversaryDate,
                    selection = TextRange(anniversaryDate.anniversaryDateCursorOffset())
                ),
                onValueChange = { anniversaryDate = it.text.toMaskedAnniversaryDate() },
                label = { Text("Anniversary (DD-MM-YYYY)", fontFamily = PoppinsFontFamily) },
                enabled = !uiState.isBusy && cooldownText == null,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                visualTransformation = DatePlaceholderVisualTransformation(
                    inputColor = inputColor,
                    placeholderColor = anniversaryPlaceholderColor
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    cursorColor = inputColor,
                    focusedTextColor = inputColor,
                    unfocusedTextColor = inputColor,
                    disabledTextColor = inputColor.copy(alpha = 0.45f)
                )
            )
            cooldownText?.let {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = it,
                    color = MaterialTheme.mulberryAppColors.mutedText,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { onPartnerProfileSave(partnerName.trim(), anniversaryDate.trim()) },
                enabled = !uiState.isBusy &&
                    cooldownText == null &&
                    partnerName.isNotBlank() &&
                    anniversaryDate.isCompleteAnniversaryDate(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(15.38.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MulberryPrimary)
            ) {
                Text(
                    text = if (uiState.isBusy) "Saving..." else "Save partner details",
                    color = Color.White,
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        if (isPaired) {
            OutlinedButton(
                onClick = { showDisconnectConfirmation = true },
                enabled = !uiState.isBusy,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(15.38.dp)
            ) {
                Text("Disconnect partner", color = MulberryPrimary, fontFamily = PoppinsFontFamily)
            }
        }
    }

    if (showDisconnectConfirmation) {
        ConfirmationDialog(
            title = "Disconnect partner?",
            body = "This will remove the pairing on this server session. Your profile stays intact, but shared sync stops until you pair again.",
            confirmText = "Disconnect",
            onDismiss = { showDisconnectConfirmation = false },
            onConfirm = {
                showDisconnectConfirmation = false
                onDisconnectPartner()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivacyLegalPane() {
    var showPolicy by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsNavCard(
            title = "Privacy Policy",
            body = "How Mulberry handles account, canvas, sync, and wallpaper data.",
            onClick = { showPolicy = true }
        )
        SoftCard {
            Text(
                text = "Terms of Service",
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Coming before public release.",
                color = MaterialTheme.mulberryAppColors.mutedText,
                fontFamily = PoppinsFontFamily,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        }
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
private fun AboutPane(
    uiState: SettingsUiState,
    onVersionTapped: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SoftCard {
            Text(
                text = "Mulberry",
                color = MulberryPrimary,
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "A shared canvas for the lock screen.",
                color = MaterialTheme.mulberryAppColors.mutedText,
                fontFamily = PoppinsFontFamily,
                fontSize = 14.sp,
                lineHeight = 22.sp
            )
        }
        SettingsValueRow(
            label = "Version",
            value = "${uiState.appVersionName} (${uiState.appVersionCode})",
            onClick = onVersionTapped
        )
        InfoCard(
            rows = listOf(
                "Environment" to uiState.environmentLabel,
                "Build type" to uiState.buildType,
                "Flavor" to uiState.flavor.ifBlank { "default" }
            )
        )
    }
}

@Composable
private fun DeveloperOptionsPane(
    uiState: SettingsUiState,
    onDeveloperOptionsEnabledChanged: (Boolean) -> Unit,
    onForceSyncNow: () -> Unit,
    onRegenerateWallpaperSnapshot: () -> Unit,
    onClearFeatureOverrides: () -> Unit,
    onSeedDemoSession: () -> Unit,
    onFeatureFlagChanged: (FeatureFlag, Boolean) -> Unit,
    onResetAppState: () -> Unit,
    onSendDebugPairingNotification: () -> Unit
) {
    var showResetConfirmation by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SoftCard {
            ToggleRow(
                title = "Use developer options",
                body = "Turn this off to hide developer tools from Settings.",
                checked = uiState.developerOptionsEnabled,
                onCheckedChange = onDeveloperOptionsEnabledChanged
            )
        }

        SectionTitle("Diagnostics")
        InfoCard(
            rows = listOf(
                "Environment" to uiState.environmentLabel,
                "API Base URL" to uiState.apiBaseUrl,
                "User ID" to (uiState.bootstrapState.userId ?: "Unavailable"),
                "Pair session" to (uiState.bootstrapState.pairSessionId ?: "Unavailable"),
                "App build" to "${uiState.appVersionName} (${uiState.appVersionCode}) ${uiState.buildType}"
            )
        )

        SectionTitle("Sync")
        InfoCard(
            rows = listOf(
                "WebSocket" to uiState.syncState.displayName(),
                "Server revision" to uiState.syncMetadata.lastAppliedServerRevision.toString(),
                "Pending outbound" to uiState.pendingOperationCount.toString(),
                "FCM registered" to uiState.fcmRegistered.yesNo(),
                "Last error" to (uiState.syncMetadata.lastError ?: "None")
            )
        )
        SettingsPrimaryButton("Force sync now", uiState.isBusy, onForceSyncNow)
        SettingsSecondaryButton("Regenerate wallpaper snapshot", uiState.isBusy, onRegenerateWallpaperSnapshot)
        SettingsSecondaryButton(
            text = "Mock partner joined notification",
            isBusy = uiState.isBusy,
            onClick = onSendDebugPairingNotification,
            enabled = uiState.bootstrapState.pairingStatus == PairingStatus.PAIRED
        )

        if (uiState.enableDebugMenu) {
            SectionTitle("Debug Flags")
            SoftCard {
                ToggleRow(
                    title = "Placeholder pairing controls",
                    body = "Show legacy pairing controls.",
                    checked = uiState.featureFlags.showPlaceholderPairingControls,
                    onCheckedChange = {
                        onFeatureFlagChanged(FeatureFlag.PLACEHOLDER_PAIRING_CONTROLS, it)
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                ToggleRow(
                    title = "Wallpaper setup CTA",
                    body = "Show the wallpaper setup call to action.",
                    checked = uiState.featureFlags.showWallpaperSetupCta,
                    onCheckedChange = {
                        onFeatureFlagChanged(FeatureFlag.WALLPAPER_SETUP_CTA, it)
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                ToggleRow(
                    title = "Developer bootstrap actions",
                    body = "Show bootstrap shortcuts.",
                    checked = uiState.featureFlags.showDeveloperBootstrapActions,
                    onCheckedChange = {
                        onFeatureFlagChanged(FeatureFlag.DEVELOPER_BOOTSTRAP_ACTIONS, it)
                    }
                )
            }
            SettingsSecondaryButton("Reset feature flag overrides", uiState.isBusy, onClearFeatureOverrides)
            SettingsSecondaryButton("Seed demo session", uiState.isBusy, onSeedDemoSession)
        }

        SectionTitle("Reset")
        SettingsDestructiveButton(
            text = "Reset app state",
            enabled = !uiState.isBusy,
            onClick = { showResetConfirmation = true }
        )
    }

    if (showResetConfirmation) {
        ConfirmationDialog(
            title = "Reset app state?",
            body = "This clears local auth, drawing data, sync metadata, background assets, and wallpaper snapshots on this device.",
            confirmText = "Reset",
            onDismiss = { showResetConfirmation = false },
            onConfirm = {
                showResetConfirmation = false
                onResetAppState()
            }
        )
    }
}

@Composable
private fun SyncStatusCard(uiState: SettingsUiState) {
    SoftCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Sync",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = syncSummary(uiState),
                    color = MaterialTheme.mulberryAppColors.mutedText,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
            StatusPill(uiState.syncState.displayName())
        }
    }
}

@Composable
private fun SettingsNavCard(
    title: String,
    body: String,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null
) {
    SoftCard(
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            leading?.invoke()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = body,
                    color = MaterialTheme.mulberryAppColors.mutedText,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "›",
                color = MulberryPrimary,
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp
            )
        }
    }
}

@Composable
private fun IdentityCard(
    photoUrl: String?,
    title: String,
    body: String,
    muted: Boolean
) {
    SoftCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsAvatar(photoUrl = photoUrl, displayName = title, size = 68.dp, muted = muted)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    lineHeight = 26.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = body,
                    color = MaterialTheme.mulberryAppColors.mutedText,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
private fun InfoCard(rows: List<Pair<String, String>>) {
    SoftCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEach { (label, value) ->
                SettingsValueRow(label = label, value = value)
            }
        }
    }
}

@Composable
private fun SettingsValueRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = MaterialTheme.mulberryAppColors.mutedText,
            fontFamily = PoppinsFontFamily,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.weight(0.8f)
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.2f)
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Text(
                text = body,
                color = MaterialTheme.mulberryAppColors.mutedText,
                fontFamily = PoppinsFontFamily,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SoftCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.mulberryAppColors.softSurface)
            .border(1.dp, MulberryPrimary.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .padding(18.dp),
        content = content
    )
}

@Composable
private fun SettingsAvatar(
    photoUrl: String?,
    displayName: String?,
    size: androidx.compose.ui.unit.Dp,
    muted: Boolean = false
) {
    val initial = displayName?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (muted) MulberrySurfaceVariant else MulberryPrimary)
            .border(4.dp, MaterialTheme.mulberryAppColors.softBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (!photoUrl.isNullOrBlank()) {
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = initial,
                color = if (muted) MulberryPrimary else Color.White,
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.40f).sp
            )
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.mulberryAppColors.softSurfaceAlt)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            color = MulberryPrimary,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = MulberryPrimary,
        fontFamily = PoppinsFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun SettingsPrimaryButton(
    text: String,
    isBusy: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(15.38.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MulberryPrimary)
    ) {
        Text(text = text, fontFamily = PoppinsFontFamily, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SettingsSecondaryButton(
    text: String,
    isBusy: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !isBusy,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(15.38.dp)
    ) {
        Text(text = text, color = MulberryPrimary, fontFamily = PoppinsFontFamily)
    }
}

@Composable
private fun SettingsDestructiveButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .testTag(TestTags.SETTINGS_RESET_BUTTON),
        shape = RoundedCornerShape(15.38.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MulberryPrimary)
    ) {
        Text(text = text, color = Color.White, fontFamily = PoppinsFontFamily)
    }
}

@Composable
private fun ConfirmationDialog(
    title: String,
    body: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
        AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Text(
                text = body,
                fontFamily = PoppinsFontFamily,
                lineHeight = 20.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = MulberryPrimary, fontFamily = PoppinsFontFamily)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", fontFamily = PoppinsFontFamily)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp)
    )
}

private fun SettingsPane.title(): String = when (this) {
    SettingsPane.Home -> "Settings"
    SettingsPane.Profile -> "Profile"
    SettingsPane.Partner -> "Partner"
    SettingsPane.PrivacyLegal -> "Privacy & Legal"
    SettingsPane.About -> "About"
    SettingsPane.DeveloperOptions -> "Developer Options"
}

private fun SettingsPane.subtitle(): String = when (this) {
    SettingsPane.Home -> "Keep your Mulberry account and connection tidy."
    SettingsPane.Profile -> "Your Google account details and session."
    SettingsPane.Partner -> "View your connected partner details."
    SettingsPane.PrivacyLegal -> "Policies that explain how Mulberry works."
    SettingsPane.About -> "Build information and app details."
    SettingsPane.DeveloperOptions -> "Diagnostics and support tools."
}

private fun SyncState.displayName(): String = when (this) {
    SyncState.Connected -> "Connected"
    SyncState.Connecting -> "Connecting"
    SyncState.Disconnected -> "Disconnected"
    SyncState.Recovering -> "Recovering"
    is SyncState.Error -> "Error"
}

private fun syncSummary(uiState: SettingsUiState): String =
    buildString {
        append("Revision ${uiState.syncMetadata.lastAppliedServerRevision}")
        append(" · ")
        append("${uiState.pendingOperationCount} pending")
        uiState.syncMetadata.lastError?.let {
            append(" · ")
            append(it)
        }
    }

private fun Boolean.yesNo(): String = if (this) "Yes" else "No"

private const val ANNIVERSARY_DATE_PLACEHOLDER = "DD-MM-YYYY"

private fun String?.toMaskedAnniversaryDate(): String {
    val raw = this.orEmpty()
    val digits = if (raw.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) {
        raw.substring(8, 10) + raw.substring(5, 7) + raw.substring(0, 4)
    } else {
        raw.filter(Char::isDigit).take(8)
    }
    val slots = ANNIVERSARY_DATE_PLACEHOLDER.toCharArray()
    var digitIndex = 0
    for (index in slots.indices) {
        if (slots[index] == 'Y' || slots[index] == 'M' || slots[index] == 'D') {
            if (digitIndex < digits.length) {
                slots[index] = digits[digitIndex]
                digitIndex += 1
            }
        }
    }
    return slots.concatToString()
}

private fun String.isCompleteAnniversaryDate(): Boolean =
    matches(Regex("""\d{2}-\d{2}-\d{4}"""))

private fun String.anniversaryDateCursorOffset(): Int {
    val digitCount = count(Char::isDigit)
    if (digitCount == 0) return 0
    val editableIndexes = listOf(0, 1, 3, 4, 6, 7, 8, 9)
    return (editableIndexes[(digitCount - 1).coerceAtMost(editableIndexes.lastIndex)] + 1)
        .coerceAtMost(length)
}

private class DatePlaceholderVisualTransformation(
    private val inputColor: Color,
    private val placeholderColor: Color
) : VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): TransformedText {
        val transformed = buildAnnotatedString {
            text.text.forEachIndexed { index, character ->
                val color = if (
                    character.isAnniversaryPlaceholderCharacter() ||
                    character.isPendingAnniversaryHyphen(text.text, index)
                ) {
                    placeholderColor
                } else {
                    inputColor
                }
                withStyle(SpanStyle(color = color)) {
                    append(character)
                }
            }
        }
        return TransformedText(transformed, OffsetMapping.Identity)
    }
}

private fun Char.isAnniversaryPlaceholderCharacter(): Boolean =
    this == 'D' || this == 'M' || this == 'Y'

private fun Char.isPendingAnniversaryHyphen(text: String, index: Int): Boolean =
    this == '-' && text.getOrNull(index + 1)?.isDigit() != true
