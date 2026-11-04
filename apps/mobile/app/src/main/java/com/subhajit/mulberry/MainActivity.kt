package com.subhajit.mulberry

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.subhajit.mulberry.analytics.AnalyticsEvent
import com.subhajit.mulberry.analytics.AppAnalytics
import com.subhajit.mulberry.analytics.PushOpenIntent
import com.subhajit.mulberry.analytics.WallpaperSetupTracker
import com.subhajit.mulberry.app.AppForegroundState
import com.subhajit.mulberry.app.MulberryApp
import com.subhajit.mulberry.app.bootstrap.AppStartupGate
import com.subhajit.mulberry.app.bootstrap.ReleaseStartupGateAfterFirstFrame
import com.subhajit.mulberry.app.shortcut.AppShortcutActionController
import com.subhajit.mulberry.app.snackbar.MulberrySnackbarController
import com.subhajit.mulberry.app.snackbar.MulberrySnackbarRequest
import com.subhajit.mulberry.data.bootstrap.AuthStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.pairing.inbound.InboundInviteActionController
import com.subhajit.mulberry.pairing.inbound.InboundInviteRepository
import com.subhajit.mulberry.pairing.inbound.InboundInviteSource
import com.subhajit.mulberry.pairing.inbound.InstallReferrerInboundInviteIngester
import com.subhajit.mulberry.pairing.inbound.normalizeInviteCode
import com.subhajit.mulberry.sync.CanvasSyncRepository
import com.subhajit.mulberry.sync.FcmTokenRepository
import com.subhajit.mulberry.sync.PartnerWallpaperStatusReportCoordinator
import com.subhajit.mulberry.ui.theme.MulberryTheme
import com.subhajit.mulberry.update.AppUpdateGate
import com.subhajit.mulberry.update.AppUpdateRequiredScreen
import com.subhajit.mulberry.update.AppUpdateStatus
import com.subhajit.mulberry.update.InAppUpdatePromptCoordinator
import com.subhajit.mulberry.whatsnew.WhatsNewPrompter
import com.subhajit.mulberry.navigation.AppRoute
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var canvasSyncRepository: CanvasSyncRepository
    @Inject lateinit var fcmTokenRepository: FcmTokenRepository
    @Inject lateinit var inboundInviteRepository: InboundInviteRepository
    @Inject lateinit var installReferrerInboundInviteIngester: InstallReferrerInboundInviteIngester
    @Inject lateinit var sessionBootstrapRepository: SessionBootstrapRepository
    @Inject lateinit var appUpdateManager: AppUpdateManager
    @Inject lateinit var inAppUpdatePromptCoordinator: InAppUpdatePromptCoordinator
    @Inject lateinit var appUpdateGate: AppUpdateGate
    @Inject lateinit var partnerWallpaperStatusReportCoordinator: PartnerWallpaperStatusReportCoordinator
    @Inject lateinit var whatsNewPrompter: WhatsNewPrompter
    @Inject lateinit var appAnalytics: AppAnalytics
    @Inject lateinit var wallpaperSetupTracker: WallpaperSetupTracker

    private val currentRoute = MutableStateFlow<String?>(null)
    private var pendingUpdatePromptVersionCode: Int? = null
    private lateinit var updateFlowResultLauncher: ActivityResultLauncher<IntentSenderRequest>

    private val flexibleUpdateListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            showUpdateReadySnackbar()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition {
            AppStartupGate.keepSplashVisible.value
        }
        super.onCreate(savedInstanceState)
        // A recreated activity gets its old intent back; that open was already counted.
        if (savedInstanceState == null) trackPushOpen(intent) else PushOpenIntent.consume(intent)
        AppShortcutActionController.dispatch(intent)
        handleInviteLink(intent)
        AppStartupGate.armTimeout(lifecycleScope)
        appUpdateGate.start()
        applyDebugUpdateStatus(intent)
        requestNotificationPermissionIfNeeded()
        enableEdgeToEdge()

        updateFlowResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                val versionCode = pendingUpdatePromptVersionCode
                pendingUpdatePromptVersionCode = null
                if (result.resultCode != Activity.RESULT_OK && versionCode != null) {
                    lifecycleScope.launch {
                        inAppUpdatePromptCoordinator.recordDeclined(
                            nowMs = System.currentTimeMillis(),
                            availableVersionCode = versionCode
                        )
                    }
                }
                maybeStartWhatsNewAutoPrompt()
            }

        lifecycleScope.launch {
            MulberrySnackbarController.actions.collect { actionKey ->
                if (actionKey == MulberrySnackbarController.ACTION_COMPLETE_IN_APP_UPDATE) {
                    appUpdateManager.completeUpdate()
                }
            }
        }

        lifecycleScope.launch {
            currentRoute
                .map { route -> route == AppRoute.CanvasHome.route || route == AppRoute.CanvasSurface.route }
                .distinctUntilChanged()
                .collect { isInMainApp ->
                    if (isInMainApp) {
                        maybeStartFlexibleUpdatePrompt(isManual = false)
                        maybeShowDownloadedUpdateSnackbar()
                    }
                }
        }

        // Values arriving after the canvas opened can make an update recommended.
        lifecycleScope.launch {
            appUpdateGate.status
                .map { status -> status == AppUpdateStatus.RECOMMENDED }
                .distinctUntilChanged()
                .filter { recommended -> recommended }
                .collect { maybeStartFlexibleUpdatePrompt(isManual = false) }
        }

        appUpdateManager.registerListener(flexibleUpdateListener)

        setContent {
            val updateStatus by appUpdateGate.status.collectAsState()
            MulberryTheme {
                // Below the minimum build, nothing else opens.
                if (updateStatus == AppUpdateStatus.REQUIRED) {
                    ReleaseStartupGateAfterFirstFrame()
                    AppUpdateRequiredScreen(onUpdate = ::startRequiredUpdate)
                } else {
                    MulberryApp(
                        onRouteChanged = { route ->
                            currentRoute.value = route
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        appUpdateManager.unregisterListener(flexibleUpdateListener)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        trackPushOpen(intent)
        AppShortcutActionController.dispatch(intent)
        handleInviteLink(intent)
        applyDebugUpdateStatus(intent)
    }

    fun triggerInAppUpdateManual() {
        maybeStartFlexibleUpdatePrompt(isManual = true)
    }

    override fun onStart() {
        super.onStart()
        AppForegroundState.setForeground(true)
        appUpdateGate.refresh()
        canvasSyncRepository.start()
        lifecycleScope.launch {
            fcmTokenRepository.syncTokenWithBackend()
        }
        lifecycleScope.launch {
            partnerWallpaperStatusReportCoordinator.reportNow(reason = "foreground_start")
        }
        installReferrerInboundInviteIngester.ingestIfNeededAsync()
        lifecycleScope.launch {
            wallpaperSetupTracker.onAppReturned()
        }
        if (isMainAppRoute(currentRoute.value)) {
            maybeStartFlexibleUpdatePrompt(isManual = false)
        }
    }

    override fun onStop() {
        AppForegroundState.setForeground(false)
        canvasSyncRepository.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (isMainAppRoute(currentRoute.value)) {
            maybeShowDownloadedUpdateSnackbar()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST_CODE
        )
    }

    private companion object {
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 3001
        const val TAG = "MulberryInAppUpdate"
    }

    private fun trackPushOpen(intent: Intent?) {
        val pushType = PushOpenIntent.consume(intent) ?: return
        val fromRecents = ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (!fromRecents) appAnalytics.track(AnalyticsEvent.PushOpened(pushType))
    }

    private fun handleInviteLink(intent: Intent?) {
        val code = normalizeInviteCode(intent?.data?.getQueryParameter("code")) ?: return
        InboundInviteActionController.notifyInviteReceived()
        lifecycleScope.launch {
            inboundInviteRepository.setPendingInvite(code, InboundInviteSource.AppLink)
        }
    }

    private fun isMainAppRoute(route: String?): Boolean = when (route) {
        AppRoute.CanvasHome.route,
        AppRoute.CanvasSurface.route,
        AppRoute.Settings.route,
        AppRoute.WallpaperCatalog.route,
        AppRoute.WallpaperStatus.route -> true
        else -> false
    }

    private fun shouldAttemptPlayUpdateFlow(): Boolean {
        val installer = runCatching {
            packageManager.getInstallerPackageName(packageName)
        }.getOrNull()
        return installer == "com.android.vending"
    }

    private fun showUpdateReadySnackbar() {
        MulberrySnackbarController.show(
            MulberrySnackbarRequest(
                message = "Update ready to install.",
                actionLabel = "Restart",
                actionKey = MulberrySnackbarController.ACTION_COMPLETE_IN_APP_UPDATE,
                isIndefinite = true
            )
        )
    }

    private fun maybeShowDownloadedUpdateSnackbar() {
        appUpdateManager
            .appUpdateInfo
            .addOnSuccessListener { appUpdateInfo ->
                if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                    showUpdateReadySnackbar()
                }
            }
    }

    private fun maybeStartFlexibleUpdatePrompt(isManual: Boolean) {
        if (!isMainAppRoute(currentRoute.value)) return
        // Unasked, only builds below the admin site's recommended build are nudged.
        if (!isManual && appUpdateGate.status.value != AppUpdateStatus.RECOMMENDED) {
            maybeStartWhatsNewAutoPrompt()
            return
        }
        // Implementation continues below using Play callbacks (kept outside coroutine for Task API).
        if (!shouldAttemptPlayUpdateFlow()) {
            maybeStartWhatsNewAutoPrompt()
            return
        }
        lifecycleScope.launch {
            val bootstrap = sessionBootstrapRepository.state.first()
            if (bootstrap.authStatus != AuthStatus.SIGNED_IN) return@launch
            if (!bootstrap.hasCompletedOnboarding) return@launch

            val nowMs = System.currentTimeMillis()
            appUpdateManager
                .appUpdateInfo
                .addOnSuccessListener { appUpdateInfo ->
                    if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                        showUpdateReadySnackbar()
                        maybeStartWhatsNewAutoPrompt()
                        return@addOnSuccessListener
                    }

                    if (appUpdateInfo.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) {
                        maybeStartWhatsNewAutoPrompt()
                        return@addOnSuccessListener
                    }
                    if (!appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                        maybeStartWhatsNewAutoPrompt()
                        return@addOnSuccessListener
                    }

                    val availableVersionCode = appUpdateInfo.availableVersionCode()
                    lifecycleScope.launch {
                        val shouldPrompt = inAppUpdatePromptCoordinator.shouldPromptForUpdate(
                            nowMs = nowMs,
                            availableVersionCode = availableVersionCode,
                            isManual = isManual
                        )
                        if (!shouldPrompt) {
                            maybeStartWhatsNewAutoPrompt()
                            return@launch
                        }

                        pendingUpdatePromptVersionCode = availableVersionCode
                        appUpdateManager.startUpdateFlowForResult(
                            appUpdateInfo,
                            updateFlowResultLauncher,
                            AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
                        )
                    }
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "Unable to check app update availability message=${error.message}", error)
                    maybeStartWhatsNewAutoPrompt()
                }
        }
    }

    /** Play's immediate update where Play installed the app and offers one; otherwise the store listing. */
    private fun startRequiredUpdate() {
        if (!shouldAttemptPlayUpdateFlow()) {
            openStoreListing()
            return
        }
        appUpdateManager
            .appUpdateInfo
            .addOnSuccessListener { appUpdateInfo ->
                val available = appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE ||
                    appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                if (available && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        updateFlowResultLauncher,
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                    )
                } else {
                    openStoreListing()
                }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Unable to start the required update message=${error.message}", error)
                openStoreListing()
            }
    }

    private fun openStoreListing() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
        runCatching { startActivity(market) }.onFailure { runCatching { startActivity(web) } }
    }

    /**
     * Debug builds show either update state on request:
     * `adb shell am start -n com.subhajit.mulberry.dev/com.subhajit.mulberry.MainActivity --es appUpdate required`
     * (or `recommended`).
     */
    private fun applyDebugUpdateStatus(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        when (intent?.getStringExtra("appUpdate")) {
            "required" -> appUpdateGate.forceForDebug(AppUpdateStatus.REQUIRED)
            "recommended" -> appUpdateGate.forceForDebug(AppUpdateStatus.RECOMMENDED)
        }
    }

    private fun maybeStartWhatsNewAutoPrompt() {
        if (!isMainAppRoute(currentRoute.value)) return
        lifecycleScope.launch {
            val bootstrap = sessionBootstrapRepository.state.first()
            if (bootstrap.authStatus != AuthStatus.SIGNED_IN) return@launch
            if (!bootstrap.hasCompletedOnboarding) return@launch
            whatsNewPrompter.maybeAutoPrompt(
                nowMs = System.currentTimeMillis(),
                currentVersionName = BuildConfig.VERSION_NAME,
                isEligibleToShow = true
            )
        }
    }

}
