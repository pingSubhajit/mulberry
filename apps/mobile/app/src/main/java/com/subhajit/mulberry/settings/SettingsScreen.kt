package com.subhajit.mulberry.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.flags.FeatureFlag
import com.subhajit.mulberry.core.ui.PrivacyPolicySheetContent
import com.subhajit.mulberry.core.ui.TermsOfUseSheetContent
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.core.ui.mulberryTapScale
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.sync.SyncState
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.MulberryPrimaryLight
import com.subhajit.mulberry.ui.theme.MulberrySurfaceVariant
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.mulberryAppColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

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
    val profilePhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.onProfilePhotoSelected(uri)
    }
    val partnerPhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.onPartnerProfilePhotoSelected(uri)
    }

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
        onWallpaperSyncEnabledChanged = viewModel::onWallpaperSyncEnabledChanged,
        onDisplayNameSave = viewModel::onDisplayNameSave,
        onProfilePhotoChangeRequested = { profilePhotoPicker.launch("image/*") },
        onDisconnectPartner = viewModel::onDisconnectPartner,
        onPartnerProfileSave = viewModel::onPartnerProfileSave,
        onPartnerPhotoChangeRequested = { partnerPhotoPicker.launch("image/*") },
        onResetAppState = viewModel::onResetAppState,
        onLogout = viewModel::onLogout,
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
        onSendDebugPairingNotification = viewModel::onSendDebugPairingNotification,
        onMockNewDoodleNotification = viewModel::onMockNewDoodleNotification,
        onMockDrawReminderNotification = viewModel::onMockDrawReminderNotification,
        onSendCrashlyticsTestEvent = viewModel::onSendCrashlyticsTestEvent,
        onCrashlyticsTestCrash = viewModel::onCrashlyticsTestCrash
    )
}

@Composable
private fun SettingsScreen(
    uiState: SettingsUiState,
    pane: SettingsPane,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onPaneSelected: (SettingsPane) -> Unit,
    onWallpaperSyncEnabledChanged: (Boolean) -> Unit,
    onDisplayNameSave: (String) -> Unit,
    onProfilePhotoChangeRequested: () -> Unit,
    onDisconnectPartner: () -> Unit,
    onPartnerProfileSave: (String, String) -> Unit,
    onPartnerPhotoChangeRequested: () -> Unit,
    onResetAppState: () -> Unit,
    onLogout: () -> Unit,
    onSeedDemoSession: () -> Unit,
    onFeatureFlagChanged: (FeatureFlag, Boolean) -> Unit,
    onClearFeatureOverrides: () -> Unit,
    onVersionTapped: () -> Unit,
    onDeveloperOptionsEnabledChanged: (Boolean) -> Unit,
    onForceSyncNow: () -> Unit,
    onRegenerateWallpaperSnapshot: () -> Unit,
    onSendDebugPairingNotification: () -> Unit,
    onMockNewDoodleNotification: () -> Unit,
    onMockDrawReminderNotification: () -> Unit,
    onSendCrashlyticsTestEvent: () -> Unit,
    onCrashlyticsTestCrash: () -> Unit
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag(TestTags.SETTINGS_SCREEN),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
	        if (pane == SettingsPane.Home) {
	            SettingsRootPage(
	                uiState = uiState,
	                onClose = onNavigateBack,
	                onPaneSelected = onPaneSelected,
	                onWallpaperSyncEnabledChanged = onWallpaperSyncEnabledChanged,
	                onLogout = onLogout,
	                modifier = Modifier.padding(padding)
	            )
	        } else if (pane == SettingsPane.Profile) {
	            ProfilePane(
	                uiState = uiState,
                onBack = onNavigateBack,
                onDisplayNameSave = onDisplayNameSave,
                onProfilePhotoChangeRequested = onProfilePhotoChangeRequested,
                modifier = Modifier.padding(padding)
            )
        } else if (pane == SettingsPane.Partner) {
            PartnerPane(
                uiState = uiState,
                onBack = onNavigateBack,
                onDisconnectPartner = onDisconnectPartner,
                onPartnerProfileSave = onPartnerProfileSave,
                onPartnerPhotoChangeRequested = onPartnerPhotoChangeRequested,
                modifier = Modifier.padding(padding)
            )
        } else if (pane == SettingsPane.PrivacyLegal) {
            PrivacyLegalPane(
                onBack = onNavigateBack,
                modifier = Modifier.padding(padding)
            )
        } else if (pane == SettingsPane.About) {
            AboutPane(
                uiState = uiState,
                onBack = onNavigateBack,
                onVersionTapped = onVersionTapped,
                modifier = Modifier.padding(padding)
            )
        } else if (pane == SettingsPane.DeveloperOptions) {
            DeveloperOptionsPane(
                uiState = uiState,
                onBack = onNavigateBack,
                onDeveloperOptionsEnabledChanged = onDeveloperOptionsEnabledChanged,
                onForceSyncNow = onForceSyncNow,
                onRegenerateWallpaperSnapshot = onRegenerateWallpaperSnapshot,
                onClearFeatureOverrides = onClearFeatureOverrides,
                onSeedDemoSession = onSeedDemoSession,
                onFeatureFlagChanged = onFeatureFlagChanged,
                onResetAppState = onResetAppState,
                onSendDebugPairingNotification = onSendDebugPairingNotification,
                onMockNewDoodleNotification = onMockNewDoodleNotification,
                onMockDrawReminderNotification = onMockDrawReminderNotification,
                onSendCrashlyticsTestEvent = onSendCrashlyticsTestEvent,
                onCrashlyticsTestCrash = onCrashlyticsTestCrash,
                modifier = Modifier.padding(padding)
            )
        } else {
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
                    showBack = true,
                    onBack = onNavigateBack
                )

                when (pane) {
                    SettingsPane.Profile,
                    SettingsPane.Partner,
                    SettingsPane.PrivacyLegal,
                    SettingsPane.About,
                    SettingsPane.DeveloperOptions -> Unit
                    SettingsPane.Home -> Unit
                }
            }
        }
    }
}

@Composable
private fun SettingsRootPage(
    uiState: SettingsUiState,
    onClose: () -> Unit,
    onPaneSelected: (SettingsPane) -> Unit,
    onWallpaperSyncEnabledChanged: (Boolean) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPaired = uiState.bootstrapState.pairingStatus == PairingStatus.PAIRED
    val partnerName = uiState.bootstrapState.partnerDisplayName
    val userName = uiState.bootstrapState.userDisplayName ?: "Mulberry user"
    var showLogoutConfirmation by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 0.dp, bottom = 18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            RootCloseButton(onClick = onClose)
        }

        RelationshipHero(
            userPhotoUrl = uiState.bootstrapState.userPhotoUrl,
            userName = userName,
            partnerPhotoUrl = uiState.bootstrapState.partnerPhotoUrl,
            partnerName = partnerName,
            paired = isPaired
        )

        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = userName,
            color = MaterialTheme.colorScheme.onBackground,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            lineHeight = 31.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        SettingsRootSubtitle(
            paired = isPaired,
            partnerName = partnerName,
            anniversaryDate = uiState.bootstrapState.anniversaryDate
        )

        Spacer(modifier = Modifier.height(if (isPaired) 70.dp else 46.dp))
        SettingsRootMenu(
            uiState = uiState,
            onPaneSelected = onPaneSelected,
            onWallpaperSyncEnabledChanged = onWallpaperSyncEnabledChanged,
            onLogoutRequested = { showLogoutConfirmation = true }
        )

        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "Mulberry v${uiState.appVersionName}",
            color = MaterialTheme.mulberryAppColors.mutedText,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (showLogoutConfirmation) {
        ConfirmationDialog(
            title = "Log out?",
            body = "This will sign you out on this device. You can sign back in anytime.",
            confirmText = "Log out",
            onDismiss = { showLogoutConfirmation = false },
            onConfirm = {
                showLogoutConfirmation = false
                onLogout()
            }
        )
    }
}

@Composable
private fun RelationshipHero(
    userPhotoUrl: String?,
    userName: String,
    partnerPhotoUrl: String?,
    partnerName: String?,
    paired: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(138.dp),
        contentAlignment = Alignment.Center
    ) {
        if (paired) {
            FigmaHeroDecorations(modifier = Modifier.offset(x = 6.dp, y = 2.dp))
        } else {
            HeroArrow(
                modifier = Modifier
                    .offset(x = 26.dp, y = (-54).dp)
                    .size(width = 47.dp, height = 13.dp)
            )
        }
        RootAvatar(
            photoUrl = userPhotoUrl,
            displayName = userName,
            size = 100.dp,
            borderWidth = 5.dp,
            modifier = Modifier.offset(x = (-60).dp)
        )
        if (paired) {
            RootAvatar(
                photoUrl = partnerPhotoUrl,
                displayName = partnerName ?: "?",
                size = 82.dp,
                borderWidth = 0.dp,
                modifier = Modifier.offset(x = 58.dp, y = (-1).dp)
            )
        } else {
            PartnerPlaceholderAvatar(
                modifier = Modifier.offset(x = 58.dp, y = (-1).dp)
            )
        }
    }
}

@Composable
private fun SettingsRootSubtitle(
    paired: Boolean,
    partnerName: String?,
    anniversaryDate: String?
) {
    if (paired && !partnerName.isNullOrBlank()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "with: ",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                fontFamily = PoppinsFontFamily,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            Text(
                text = partnerName,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.86f),
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            anniversaryDate.toFriendlyAnniversaryDate()?.let { date ->
                Text(
                    text = ", since $date",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                    fontFamily = PoppinsFontFamily,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        }
    } else {
        Text(
            text = "waiting for partner to join",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            fontFamily = PoppinsFontFamily,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
        )
    }
}

@Composable
private fun SettingsRootMenu(
    uiState: SettingsUiState,
    onPaneSelected: (SettingsPane) -> Unit,
    onWallpaperSyncEnabledChanged: (Boolean) -> Unit,
    onLogoutRequested: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SettingsRootRow(
            icon = SettingsRootIcon.Profile,
            title = "Profile info",
            onClick = { onPaneSelected(SettingsPane.Profile) }
        )
        SettingsRootRow(
            icon = SettingsRootIcon.Partner,
            title = "Partner settings",
            onClick = { onPaneSelected(SettingsPane.Partner) }
        )
        SettingsRootRow(
            icon = if (uiState.bootstrapState.pairingStatus == PairingStatus.PAIRED) {
                SettingsRootIcon.SyncConnected
            } else {
                SettingsRootIcon.SyncUnpaired
            },
            title = "Sync status",
            status = if (uiState.bootstrapState.pairingStatus == PairingStatus.PAIRED) "Connected" else "Unpaired",
            statusColor = if (uiState.bootstrapState.pairingStatus == PairingStatus.PAIRED) {
                Color(0xFF00A53C)
            } else {
                Color(0xFFE00012)
            },
            enabled = uiState.developerOptionsEnabled,
            showChevron = uiState.developerOptionsEnabled,
            onClick = { onPaneSelected(SettingsPane.DeveloperOptions) }
        )
        SettingsRootToggleRow(
            icon = SettingsRootIcon.Lock,
            title = "Wallpaper sync",
            checked = uiState.wallpaperSyncEnabled,
            enabled = !uiState.isBusy,
            onCheckedChange = onWallpaperSyncEnabledChanged
        )
        SettingsRootRow(
            icon = SettingsRootIcon.Privacy,
            title = "Privacy and legal",
            onClick = { onPaneSelected(SettingsPane.PrivacyLegal) }
        )
        SettingsRootRow(
            icon = SettingsRootIcon.About,
            title = "About",
            onClick = { onPaneSelected(SettingsPane.About) }
        )
        if (uiState.developerOptionsEnabled) {
            SettingsRootRow(
                icon = SettingsRootIcon.About,
                title = "Developer Options",
                onClick = { onPaneSelected(SettingsPane.DeveloperOptions) }
            )
        }
        SettingsRootRow(
            icon = SettingsRootIcon.Logout,
            title = "Log out",
            enabled = !uiState.isBusy,
            showChevron = false,
            modifier = Modifier.testTag(TestTags.SETTINGS_LOGOUT_BUTTON),
            onClick = onLogoutRequested
        )
    }
}

@Composable
private fun SettingsRootRow(
    icon: SettingsRootIcon,
    title: String,
    status: String? = null,
    statusColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RootLineIcon(
            icon = icon,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(22.dp))
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            modifier = Modifier.weight(1f)
        )
        if (status != null) {
            Text(
                text = status,
                color = statusColor,
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(end = if (showChevron) 18.dp else 0.dp)
            )
        }
        if (showChevron) {
            RootChevron()
        }
    }
}

@Composable
private fun SettingsRootToggleRow(
    icon: SettingsRootIcon,
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RootLineIcon(
            icon = icon,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(22.dp))
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun RootCloseButton(onClick: () -> Unit) {
    val strokeColor = MaterialTheme.colorScheme.onBackground
    Canvas(
        modifier = Modifier
            .size(31.dp)
            .clickable(onClick = onClick)
            .padding(7.dp)
    ) {
        val stroke = Stroke(width = 3.8f, cap = StrokeCap.Round)
        drawLine(
            color = strokeColor,
            start = androidx.compose.ui.geometry.Offset(3f, 3f),
            end = androidx.compose.ui.geometry.Offset(size.width - 3f, size.height - 3f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round
        )
        drawLine(
            color = strokeColor,
            start = androidx.compose.ui.geometry.Offset(size.width - 3f, 3f),
            end = androidx.compose.ui.geometry.Offset(3f, size.height - 3f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun RootAvatar(
    photoUrl: String?,
    displayName: String?,
    size: androidx.compose.ui.unit.Dp,
    borderWidth: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val initial = displayName?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MulberryPrimaryLight)
            .then(
                if (borderWidth > 0.dp) {
                    Modifier.border(borderWidth, MulberryPrimary, CircleShape)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(borderWidth)
                .clip(CircleShape)
                .background(MulberryPrimary),
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
                    color = Color.White,
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (size.value * 0.40f).sp
                )
            }
        }
    }
}

@Composable
private fun PartnerPlaceholderAvatar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(82.dp)
            .clip(CircleShape)
            .background(MulberryPrimaryLight.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "?",
            color = MulberryPrimary,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 58.sp
        )
    }
}

@Composable
private fun FigmaHeroDecorations(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(width = 66.dp, height = 138.dp)
    ) {
        HeroArrow(
            modifier = Modifier
                .offset(x = 18.dp, y = 15.dp)
                .size(width = 47.dp, height = 13.dp)
        )
        HeroHeart(
            size = 23.dp,
            rotation = 12.8531f,
            modifier = Modifier.offset(x = 5.11639.dp, y = 0.dp)
        )
        HeroHeart(
            size = 23.dp,
            rotation = -13.7174f,
            modifier = Modifier.offset(x = 31.601.dp, y = 25.0552.dp)
        )
        HeroHeart(
            size = 21.dp,
            rotation = 26.2702f,
            modifier = Modifier.offset(x = 41.2947.dp, y = 80.dp)
        )
        HeroHeart(
            size = 23.dp,
            rotation = -19.2486f,
            modifier = Modifier.offset(x = 4.85172.dp, y = 101.434.dp)
        )
        HeroHeart(
            size = 38.dp,
            rotation = 9.03514f,
            modifier = Modifier.offset(x = 27.2195.dp, y = 94.252.dp)
        )
    }
}

@Composable
private fun HeroHeart(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.settings_hero_heart),
        contentDescription = null,
        modifier = modifier.size(size)
    )
}

@Composable
private fun HeroHeart(
    size: androidx.compose.ui.unit.Dp,
    rotation: Float,
    modifier: Modifier = Modifier
) {
    HeroHeart(
        size = size,
        modifier = modifier.rotate(rotation)
    )
}

@Composable
private fun HeroArrow(modifier: Modifier = Modifier) {
    val strokeColor = MaterialTheme.colorScheme.onBackground
    Image(
        painter = painterResource(R.drawable.settings_hero_arrow),
        contentDescription = null,
        colorFilter = ColorFilter.tint(strokeColor),
        modifier = modifier
    )
}

private enum class SettingsRootIcon {
    Profile,
    Partner,
    SyncConnected,
    SyncUnpaired,
    Lock,
    Privacy,
    About,
    Logout
}

@DrawableRes
private fun SettingsRootIcon.drawableRes(): Int = when (this) {
    SettingsRootIcon.Profile -> R.drawable.settings_icon_profile
    SettingsRootIcon.Partner -> R.drawable.settings_icon_partner
    SettingsRootIcon.SyncConnected -> R.drawable.settings_icon_sync_connected
    SettingsRootIcon.SyncUnpaired -> R.drawable.settings_icon_sync_unpaired
    SettingsRootIcon.Lock -> R.drawable.settings_icon_lock
    SettingsRootIcon.Privacy -> R.drawable.settings_icon_privacy
    SettingsRootIcon.About -> R.drawable.settings_icon_about
    SettingsRootIcon.Logout -> R.drawable.settings_icon_logout
}

@Composable
private fun RootLineIcon(
    icon: SettingsRootIcon,
    color: Color,
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(icon.drawableRes()),
        contentDescription = null,
        colorFilter = ColorFilter.tint(color),
        modifier = modifier
    )
}

@Composable
private fun RootChevron() {
    val chevronColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f)
    Canvas(modifier = Modifier.size(width = 8.dp, height = 12.dp)) {
        drawLine(
            color = chevronColor,
            start = androidx.compose.ui.geometry.Offset(2f, 2f),
            end = androidx.compose.ui.geometry.Offset(size.width - 2f, size.height / 2f),
            strokeWidth = 3.0f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = chevronColor,
            start = androidx.compose.ui.geometry.Offset(size.width - 2f, size.height / 2f),
            end = androidx.compose.ui.geometry.Offset(2f, size.height - 2f),
            strokeWidth = 3.0f,
            cap = StrokeCap.Round
        )
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
private fun ProfilePane(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onDisplayNameSave: (String) -> Unit,
    onProfilePhotoChangeRequested: () -> Unit,
    modifier: Modifier = Modifier
) {
    var displayName by remember(uiState.bootstrapState.userDisplayName) {
        mutableStateOf(uiState.bootstrapState.userDisplayName.orEmpty())
    }
    SettingsDetailScaffold(
        title = "Profile info",
        onBack = onBack,
        bottomBar = {
            SettingsSaveButton(
                text = if (uiState.isBusy) "Saving" else "Save",
                enabled = !uiState.isBusy &&
                    displayName.isNotBlank() &&
                    displayName.trim() != uiState.bootstrapState.userDisplayName.orEmpty(),
                onClick = { onDisplayNameSave(displayName.trim()) }
            )
        },
        modifier = modifier
    ) {
        Spacer(modifier = Modifier.height(72.dp))
        EditableProfileAvatar(
            photoUrl = uiState.bootstrapState.userPhotoUrl,
            displayName = uiState.bootstrapState.userDisplayName,
            size = 100.dp,
            borderWidth = 5.dp,
            onEdit = onProfilePhotoChangeRequested,
            enabled = !uiState.isBusy
        )
        Spacer(modifier = Modifier.height(76.dp))
        SettingsDetailField(
            label = "What is your name?",
            value = displayName,
            placeholder = "Enter your name",
            onValueChange = { displayName = it },
            enabled = !uiState.isBusy
        )
        Spacer(modifier = Modifier.height(24.dp))
        SettingsDetailField(
            label = "What is your email address?",
            value = uiState.bootstrapState.userEmail.orEmpty(),
            placeholder = "Google account",
            onValueChange = {},
            enabled = false
        )
    }
}

@Composable
private fun PartnerPane(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onDisconnectPartner: () -> Unit,
    onPartnerProfileSave: (String, String) -> Unit,
    onPartnerPhotoChangeRequested: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDisconnectConfirmation by remember { mutableStateOf(false) }
    var partnerName by remember(uiState.bootstrapState.partnerDisplayName) {
        mutableStateOf(uiState.bootstrapState.partnerDisplayName.orEmpty())
    }
    var anniversaryDate by remember(uiState.bootstrapState.anniversaryDate) {
        mutableStateOf(uiState.bootstrapState.anniversaryDate.toMaskedAnniversaryDate())
    }
    val isPaired = uiState.bootstrapState.pairingStatus == PairingStatus.PAIRED
    val cooldownText = if (isPaired) {
        partnerCooldownChipText(uiState.bootstrapState.partnerProfileNextUpdateAt)
    } else {
        null
    }
    val canEditPartner = !uiState.isBusy && cooldownText == null

    SettingsDetailScaffold(
        title = "Partner settings",
        onBack = onBack,
        bottomBar = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (isPaired) {
                    Text(
                        text = "Unpair partner?",
                        color = Color(0xFFE00012),
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        modifier = Modifier
                            .clickable(enabled = !uiState.isBusy) { showDisconnectConfirmation = true }
                            .padding(bottom = 24.dp)
                    )
                }
                SettingsSaveButton(
                    text = if (uiState.isBusy) "Saving" else "Save",
                    enabled = canEditPartner &&
                        partnerName.isNotBlank() &&
                        anniversaryDate.isCompleteAnniversaryDate() &&
                        (
                            partnerName.trim() != uiState.bootstrapState.partnerDisplayName.orEmpty() ||
                                anniversaryDate != uiState.bootstrapState.anniversaryDate.toMaskedAnniversaryDate()
                            ),
                    onClick = { onPartnerProfileSave(partnerName.trim(), anniversaryDate.trim()) }
                )
            }
        },
        modifier = modifier
    ) {
        Spacer(modifier = Modifier.height(54.dp))
        Box(contentAlignment = Alignment.Center) {
            EditableProfileAvatar(
                photoUrl = uiState.bootstrapState.partnerPhotoUrl,
                displayName = uiState.bootstrapState.partnerDisplayName,
                size = 90.dp,
                borderWidth = 0.dp,
                muted = !isPaired,
                onEdit = onPartnerPhotoChangeRequested,
                enabled = canEditPartner,
                editButtonSize = 36.dp,
                editButtonOffsetX = 6.dp,
                editButtonOffsetY = 4.dp,
                editIconSize = 20.dp
            )
            cooldownText?.let {
                PartnerCooldownChip(
                    text = it,
                    modifier = Modifier.offset(x = 66.dp, y = 24.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(62.dp))
        SettingsDetailField(
            label = "What is your partner's name?",
            value = partnerName,
            placeholder = "Enter partner name",
            onValueChange = { partnerName = it },
            enabled = canEditPartner
        )
        Spacer(modifier = Modifier.height(24.dp))
        SettingsDetailField(
            label = "Relationship anniversary",
            value = anniversaryDate,
            placeholder = ANNIVERSARY_DATE_PLACEHOLDER,
            onValueChange = { anniversaryDate = it.toMaskedAnniversaryDate() },
            enabled = canEditPartner,
            datePlaceholder = true
        )
        Spacer(modifier = Modifier.height(56.dp))
        PartnerMetricRow(
            icon = SettingsRootIcon.Partner,
            title = "Paired for",
            value = pairedDurationText(uiState.bootstrapState.pairedAt),
            valueColor = MulberryPrimary
        )
        Spacer(modifier = Modifier.height(24.dp))
        PartnerMetricRow(
            icon = null,
            title = "Daily streak",
            value = "${uiState.bootstrapState.currentStreakDays} days"
        )
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

@Composable
private fun SettingsDetailScaffold(
    title: String,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 0.dp, bottom = 18.dp)
    ) {
        SettingsDetailHeader(title = title, onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
        bottomBar()
    }
}

@Composable
private fun SettingsDetailHeader(title: String, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
        contentAlignment = Alignment.Center
    ) {
        SettingsBackButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart)
        )
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.76f),
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val iconColor = MaterialTheme.mulberryAppColors.iconMuted
    Box(
        modifier = modifier
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
                color = iconColor,
                start = center.copy(x = size.width * 0.22f),
                end = center.copy(x = size.width * 0.78f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Square
            )
            drawLine(
                color = iconColor,
                start = center.copy(x = size.width * 0.22f),
                end = center.copy(x = size.width * 0.44f, y = size.height * 0.28f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Square
            )
            drawLine(
                color = iconColor,
                start = center.copy(x = size.width * 0.22f),
                end = center.copy(x = size.width * 0.44f, y = size.height * 0.72f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Square
            )
        }
    }
}

@Composable
private fun EditableProfileAvatar(
    photoUrl: String?,
    displayName: String?,
    size: androidx.compose.ui.unit.Dp,
    borderWidth: androidx.compose.ui.unit.Dp,
    onEdit: () -> Unit,
    enabled: Boolean,
    muted: Boolean = false,
    editButtonSize: androidx.compose.ui.unit.Dp = 54.dp,
    editButtonOffsetX: androidx.compose.ui.unit.Dp = 12.dp,
    editButtonOffsetY: androidx.compose.ui.unit.Dp = 4.dp,
    editIconSize: androidx.compose.ui.unit.Dp = 26.dp
) {
    Box(contentAlignment = Alignment.BottomEnd) {
        SettingsAvatar(
            photoUrl = photoUrl,
            displayName = displayName,
            size = size,
            muted = muted,
            borderWidth = borderWidth,
            borderColor = MulberryPrimary
        )
        EditPhotoButton(
            onClick = onEdit,
            enabled = enabled,
            size = editButtonSize,
            iconSize = editIconSize,
            modifier = Modifier.offset(x = editButtonOffsetX, y = editButtonOffsetY)
        )
    }
}

@Composable
private fun EditPhotoButton(
    onClick: () -> Unit,
    enabled: Boolean,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.settings_icon_edit_pencil),
            contentDescription = null,
            colorFilter = ColorFilter.tint(
                MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 1f else 0.38f)
            ),
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
private fun SettingsDetailField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    datePlaceholder: Boolean = false
) {
    val inputColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.55f)
    val placeholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.34f)
    val textStyle = androidx.compose.ui.text.TextStyle(
        color = inputColor,
        fontFamily = PoppinsFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 28.sp
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp)
            .clip(RoundedCornerShape(15.38.dp))
            .background(MaterialTheme.mulberryAppColors.inputSurface)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (datePlaceholder) {
            BasicTextField(
                value = TextFieldValue(
                    text = value,
                    selection = TextRange(value.anniversaryDateCursorOffset())
                ),
                onValueChange = { onValueChange(it.text) },
                enabled = enabled,
                singleLine = true,
                textStyle = textStyle,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                visualTransformation = DatePlaceholderVisualTransformation(
                    inputColor = inputColor,
                    placeholderColor = placeholderColor
                ),
                cursorBrush = SolidColor(inputColor),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(inputColor),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isBlank()) {
                            Text(
                                text = placeholder,
                                color = placeholderColor,
                                fontFamily = PoppinsFontFamily,
                                fontSize = 20.sp,
                                lineHeight = 28.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
    }
}

@Composable
private fun PartnerCooldownChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomStart
    ) {
        Canvas(
            modifier = Modifier
                .size(width = 18.dp, height = 12.dp)
                .offset(x = 8.dp, y = 6.dp)
        ) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.10f, 0f)
                cubicTo(size.width * 0.24f, size.height * 0.38f, size.width * 0.16f, size.height * 0.78f, 0f, size.height)
                cubicTo(size.width * 0.48f, size.height * 0.88f, size.width * 0.82f, size.height * 0.58f, size.width, 0f)
                close()
            }
            drawPath(path, MulberryPrimary)
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(MulberryPrimary)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = text,
                color = Color.White,
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp,
                lineHeight = 13.sp
            )
        }
    }
}

@Composable
private fun PartnerMetricRow(
    icon: SettingsRootIcon?,
    title: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onBackground
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            RootLineIcon(
                icon = icon,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(24.dp)
            )
        } else {
            Image(
                painter = painterResource(R.drawable.settings_icon_daily_streak),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(22.dp))
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = valueColor,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun SettingsSaveButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .mulberryTapScale(enabled = enabled),
        shape = RoundedCornerShape(15.38.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MulberryPrimary,
            disabledContainerColor = MulberryPrimary.copy(alpha = 0.45f)
        )
    ) {
        Text(
            text = text,
            color = Color.White,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
            lineHeight = 28.sp
        )
    }
}

private fun partnerCooldownChipText(nextUpdateAt: String?): String? {
    if (nextUpdateAt.isNullOrBlank()) return null
    return runCatching {
        val minutes = ChronoUnit.MINUTES.between(Instant.now(), Instant.parse(nextUpdateAt))
        if (minutes <= 0) {
            null
        } else {
            val hours = ((minutes + 59) / 60).coerceAtLeast(1)
            "in $hours hours"
        }
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivacyLegalPane(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeSheet by remember { mutableStateOf<LegalSheet?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    SettingsDetailScaffold(
        title = "Privacy and legal",
        onBack = onBack,
        bottomBar = {},
        modifier = modifier
    ) {
        Spacer(modifier = Modifier.height(54.dp))
        PrivacyLegalCard(
            title = "Privacy policy",
            body = "How Mulberry facilitates handling and the security of your data",
            onClick = { activeSheet = LegalSheet.PrivacyPolicy }
        )
        Spacer(modifier = Modifier.height(20.dp))
        PrivacyLegalCard(
            title = "Terms of use",
            body = "Terms and conditions you accept as you continue to use the service",
            onClick = { activeSheet = LegalSheet.TermsOfUse }
        )
        Spacer(modifier = Modifier.height(36.dp))
        PrivacyPromiseRow()
    }

    if (activeSheet != null) {
        ModalBottomSheet(
            onDismissRequest = { activeSheet = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            when (activeSheet) {
                LegalSheet.PrivacyPolicy -> PrivacyPolicySheetContent(onDismiss = { activeSheet = null })
                LegalSheet.TermsOfUse -> TermsOfUseSheetContent(onDismiss = { activeSheet = null })
                null -> Unit
            }
        }
    }
}

@Composable
private fun PrivacyLegalCard(
    title: String,
    body: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.38.dp))
            .background(MulberryPrimaryLight.copy(alpha = 0.34f))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 19.sp,
            lineHeight = 25.sp
        )
        Text(
            text = body,
            color = MaterialTheme.colorScheme.onBackground,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 21.sp
        )
    }
}

@Composable
private fun PrivacyPromiseRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        PrivacyPromiseItem(
            iconRes = R.drawable.settings_legal_lock,
            label = "No data\nsharing"
        )
        PrivacyPromiseItem(
            iconRes = R.drawable.settings_legal_megaphone,
            label = "No\nadvertising"
        )
        PrivacyPromiseItem(
            iconRes = R.drawable.settings_legal_search,
            label = "No\ntracking"
        )
    }
}

@Composable
private fun PrivacyPromiseItem(
    @DrawableRes iconRes: Int,
    label: String
) {
    Column(
        modifier = Modifier.width(96.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onBackground,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center
        )
    }
}

private enum class LegalSheet {
    PrivacyPolicy,
    TermsOfUse
}

@Composable
private fun AboutPane(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onVersionTapped: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current

    SettingsDetailScaffold(
        title = "About",
        onBack = onBack,
        bottomBar = {},
        modifier = modifier
    ) {
        Spacer(modifier = Modifier.height(42.dp))
        Image(
            painter = painterResource(R.drawable.about_page_banner),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(724f / 410f)
                .clip(RoundedCornerShape(15.38.dp))
        )
        Spacer(modifier = Modifier.height(36.dp))
        AboutInfoRow(
            label = "App version",
            value = uiState.appVersionName.ifBlank { "Unknown" },
            onClick = onVersionTapped
        )
        AboutInfoRow(
            label = "Build number",
            value = uiState.appVersionCode.toString()
        )
        AboutInfoRow(
            label = "Released on",
            value = "26/04/2026"
        )
        AboutInfoRow(
            label = "App flavour",
            value = uiState.flavor.ifBlank { uiState.buildType.ifBlank { "default" } }
        )
        AboutInfoRow(
            label = "Website",
            value = "mulberry.my",
            onClick = { uriHandler.openUri("https://mulberry.my") },
            showChevron = true
        )
        AboutInfoRow(
            label = "GitHub",
            value = "pingSubhajit/mulberry",
            onClick = { uriHandler.openUri("https://github.com/pingSubhajit/mulberry") },
            showChevron = true
        )
    }
}

@Composable
private fun AboutInfoRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = false
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .then(
            if (onClick != null) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier
            }
        )
        .padding(vertical = 14.dp)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onBackground,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 18.dp, end = if (showChevron) 18.dp else 0.dp)
                .weight(1f)
        )
        if (showChevron) {
            RootChevron()
        }
    }
}

@Composable
private fun DeveloperOptionsPane(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onDeveloperOptionsEnabledChanged: (Boolean) -> Unit,
    onForceSyncNow: () -> Unit,
    onRegenerateWallpaperSnapshot: () -> Unit,
    onClearFeatureOverrides: () -> Unit,
    onSeedDemoSession: () -> Unit,
    onFeatureFlagChanged: (FeatureFlag, Boolean) -> Unit,
    onResetAppState: () -> Unit,
    onSendDebugPairingNotification: () -> Unit,
    onMockNewDoodleNotification: () -> Unit,
    onMockDrawReminderNotification: () -> Unit,
    onSendCrashlyticsTestEvent: () -> Unit,
    onCrashlyticsTestCrash: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showResetConfirmation by remember { mutableStateOf(false) }
    var showCrashlyticsCrashConfirmation by remember { mutableStateOf(false) }

    SettingsDetailScaffold(
        title = "Developer options",
        onBack = onBack,
        bottomBar = {},
        modifier = modifier
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        DeveloperOptionsHeroCard(
            enabled = uiState.developerOptionsEnabled,
            onEnabledChanged = onDeveloperOptionsEnabledChanged
        )
        Spacer(modifier = Modifier.height(20.dp))
        DeveloperSectionCard(
            title = "App diagnostics",
            body = "Current environment and account identifiers for support, QA, and release verification."
        ) {
            DeveloperValueList(
                rows = listOf(
                    "Environment" to uiState.environmentLabel,
                    "API Base URL" to uiState.apiBaseUrl,
                    "User ID" to (uiState.bootstrapState.userId ?: "Unavailable"),
                    "Pair session" to (uiState.bootstrapState.pairSessionId ?: "Unavailable"),
                    "App build" to "${uiState.appVersionName} (${uiState.appVersionCode}) ${uiState.buildType}"
                )
            )
            if (uiState.developerOptionsEnabled) {
                Spacer(modifier = Modifier.height(18.dp))
                SettingsSecondaryButton(
                    text = "Send Crashlytics test event",
                    isBusy = uiState.isBusy,
                    onClick = onSendCrashlyticsTestEvent,
                    enabled = !uiState.isBusy
                )
                Spacer(modifier = Modifier.height(10.dp))
                SettingsDestructiveButton(
                    text = "Crash app (Crashlytics test)",
                    enabled = !uiState.isBusy,
                    onClick = { showCrashlyticsCrashConfirmation = true }
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        DeveloperSectionCard(
            title = "Sync tools",
            body = "Realtime state, delivery health, and manual recovery actions for the current device."
        ) {
            DeveloperValueList(
                rows = listOf(
                    "WebSocket" to uiState.syncState.displayName(),
                    "Server revision" to uiState.syncMetadata.lastAppliedServerRevision.toString(),
                    "Pending outbound" to uiState.pendingOperationCount.toString(),
                    "FCM registered" to uiState.fcmRegistered.yesNo(),
                    "Last error" to (uiState.syncMetadata.lastError ?: "None")
                )
            )
            Spacer(modifier = Modifier.height(18.dp))
            SettingsPrimaryButton("Force sync now", uiState.isBusy, onForceSyncNow)
            Spacer(modifier = Modifier.height(10.dp))
            SettingsSecondaryButton(
                "Regenerate wallpaper snapshot",
                uiState.isBusy,
                onRegenerateWallpaperSnapshot
            )
            Spacer(modifier = Modifier.height(10.dp))
            SettingsSecondaryButton(
                text = "Mock partner joined notification",
                isBusy = uiState.isBusy,
                onClick = onSendDebugPairingNotification,
                enabled = uiState.bootstrapState.pairingStatus == PairingStatus.PAIRED
            )
            Spacer(modifier = Modifier.height(10.dp))
            SettingsSecondaryButton(
                text = "Mock new doodle notification",
                isBusy = uiState.isBusy,
                onClick = onMockNewDoodleNotification,
                enabled = !uiState.isBusy
            )
            Spacer(modifier = Modifier.height(10.dp))
            SettingsSecondaryButton(
                text = "Mock 24h draw reminder",
                isBusy = uiState.isBusy,
                onClick = onMockDrawReminderNotification,
                enabled = !uiState.isBusy
            )
        }

        if (uiState.developerOptionsEnabled) {
            Spacer(modifier = Modifier.height(20.dp))
            DeveloperSectionCard(
                title = "Feature overrides",
                body = "Temporary local switches for staging flows and internal walkthroughs."
            ) {
                ToggleRow(
                    title = "Placeholder pairing controls",
                    body = "Show legacy pairing controls.",
                    checked = uiState.featureFlags.showPlaceholderPairingControls,
                    onCheckedChange = {
                        onFeatureFlagChanged(FeatureFlag.PLACEHOLDER_PAIRING_CONTROLS, it)
                    }
                )
                DeveloperSectionDivider()
                ToggleRow(
                    title = "Wallpaper setup CTA",
                    body = "Show the wallpaper setup call to action.",
                    checked = uiState.featureFlags.showWallpaperSetupCta,
                    onCheckedChange = {
                        onFeatureFlagChanged(FeatureFlag.WALLPAPER_SETUP_CTA, it)
                    }
                )
                DeveloperSectionDivider()
                ToggleRow(
                    title = "Developer bootstrap actions",
                    body = "Show bootstrap shortcuts.",
                    checked = uiState.featureFlags.showDeveloperBootstrapActions,
                    onCheckedChange = {
                        onFeatureFlagChanged(FeatureFlag.DEVELOPER_BOOTSTRAP_ACTIONS, it)
                    }
                )
                Spacer(modifier = Modifier.height(18.dp))
                SettingsSecondaryButton(
                    "Reset feature flag overrides",
                    uiState.isBusy,
                    onClearFeatureOverrides
                )
                Spacer(modifier = Modifier.height(10.dp))
                SettingsSecondaryButton("Seed demo session", uiState.isBusy, onSeedDemoSession)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        DeveloperSectionCard(
            title = "Reset app state",
            body = "Clears local auth, sync state, drawing data, background assets, and wallpaper snapshots on this device."
        ) {
            SettingsDestructiveButton(
                text = "Reset app state",
                enabled = !uiState.isBusy,
                onClick = { showResetConfirmation = true }
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
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

    if (showCrashlyticsCrashConfirmation) {
        ConfirmationDialog(
            title = "Crash the app?",
            body = "This intentionally crashes the app to verify Crashlytics reporting. Only do this on a test build/device.",
            confirmText = "Crash",
            onDismiss = { showCrashlyticsCrashConfirmation = false },
            onConfirm = {
                showCrashlyticsCrashConfirmation = false
                onCrashlyticsTestCrash()
            }
        )
    }
}

@Composable
private fun DeveloperOptionsHeroCard(
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit
) {
    SoftCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatusPill(text = "Support tools")
                Text(
                    text = "Developer options",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    lineHeight = 26.sp
                )
                Text(
                    text = "Keep advanced diagnostics tucked away unless you need to inspect sync, debug feature gates, or recover local state.",
                    color = MaterialTheme.mulberryAppColors.mutedText,
                    fontFamily = PoppinsFontFamily,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChanged,
                modifier = Modifier.padding(start = 16.dp)
            )
        }
    }
}

@Composable
private fun DeveloperSectionCard(
    title: String,
    body: String,
    content: @Composable ColumnScope.() -> Unit
) {
    SoftCard {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            lineHeight = 24.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = body,
            color = MaterialTheme.mulberryAppColors.mutedText,
            fontFamily = PoppinsFontFamily,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(18.dp))
        content()
    }
}

@Composable
private fun DeveloperValueList(rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        rows.forEachIndexed { index, row ->
            SettingsValueRow(label = row.first, value = row.second)
            if (index != rows.lastIndex) {
                DeveloperSectionDivider()
            }
        }
    }
}

@Composable
private fun DeveloperSectionDivider() {
    Spacer(modifier = Modifier.height(14.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MulberryPrimary.copy(alpha = 0.08f))
    )
    Spacer(modifier = Modifier.height(14.dp))
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
    muted: Boolean = false,
    borderWidth: androidx.compose.ui.unit.Dp = 4.dp,
    borderColor: Color? = null
) {
    val initial = displayName?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val resolvedBorderColor = borderColor ?: MaterialTheme.mulberryAppColors.softBorder
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (muted) MulberrySurfaceVariant else MulberryPrimary)
            .then(
                if (borderWidth > 0.dp) {
                    Modifier.border(borderWidth, resolvedBorderColor, CircleShape)
                } else {
                    Modifier
                }
            ),
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
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .mulberryTapScale(enabled = !isBusy),
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
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .mulberryTapScale(enabled = enabled && !isBusy),
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
            .testTag(TestTags.SETTINGS_RESET_BUTTON)
            .mulberryTapScale(enabled = enabled),
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
            TextButton(onClick = onConfirm, modifier = Modifier.mulberryTapScale()) {
                Text(confirmText, color = MulberryPrimary, fontFamily = PoppinsFontFamily)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.mulberryTapScale()) {
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

private fun pairedDurationText(pairedAt: String?): String {
    if (pairedAt.isNullOrBlank()) return "Not paired"
    return runCatching {
        val start = Instant.parse(pairedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = java.time.LocalDate.now()
        val days = ChronoUnit.DAYS.between(start, today).coerceAtLeast(0) + 1
        "$days days and counting"
    }.getOrElse {
        "Paired"
    }
}

private fun String?.toFriendlyAnniversaryDate(): String? {
    if (isNullOrBlank()) return null
    return runCatching {
        val date = LocalDate.parse(this)
        val month = date.format(DateTimeFormatter.ofPattern("MMMM", Locale.US))
        "${date.dayOfMonth}${date.dayOfMonth.ordinalSuffix()} $month, ${date.year % 100}"
    }.getOrNull()
}

private fun Int.ordinalSuffix(): String =
    if (this in 11..13) {
        "th"
    } else {
        when (this % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
    }

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
