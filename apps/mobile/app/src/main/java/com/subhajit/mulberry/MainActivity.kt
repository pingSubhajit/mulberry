package com.subhajit.mulberry

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.subhajit.mulberry.app.AppForegroundState
import com.subhajit.mulberry.app.MulberryApp
import com.subhajit.mulberry.app.bootstrap.AppStartupGate
import com.subhajit.mulberry.app.shortcut.AppShortcutActionController
import com.subhajit.mulberry.pairing.inbound.InboundInviteActionController
import com.subhajit.mulberry.pairing.inbound.InboundInviteRepository
import com.subhajit.mulberry.pairing.inbound.InboundInviteSource
import com.subhajit.mulberry.pairing.inbound.InstallReferrerInboundInviteIngester
import com.subhajit.mulberry.pairing.inbound.normalizeInviteCode
import com.subhajit.mulberry.sync.CanvasSyncRepository
import com.subhajit.mulberry.sync.FcmTokenRepository
import com.subhajit.mulberry.ui.theme.MulberryTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var canvasSyncRepository: CanvasSyncRepository
    @Inject lateinit var fcmTokenRepository: FcmTokenRepository
    @Inject lateinit var inboundInviteRepository: InboundInviteRepository
    @Inject lateinit var installReferrerInboundInviteIngester: InstallReferrerInboundInviteIngester

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition {
            AppStartupGate.keepSplashVisible.value
        }
        AppShortcutActionController.dispatch(intent)
        handleInviteLink(intent)
        super.onCreate(savedInstanceState)
        AppStartupGate.armTimeout(lifecycleScope)
        requestNotificationPermissionIfNeeded()
        enableEdgeToEdge()
        setContent {
            MulberryTheme {
                MulberryApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppShortcutActionController.dispatch(intent)
        handleInviteLink(intent)
    }

    override fun onStart() {
        super.onStart()
        AppForegroundState.setForeground(true)
        canvasSyncRepository.start()
        lifecycleScope.launch {
            fcmTokenRepository.syncTokenWithBackend()
        }
        installReferrerInboundInviteIngester.ingestIfNeeded()
    }

    override fun onStop() {
        AppForegroundState.setForeground(false)
        canvasSyncRepository.stop()
        super.onStop()
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
    }

    private fun handleInviteLink(intent: Intent?) {
        val code = normalizeInviteCode(intent?.data?.getQueryParameter("code")) ?: return
        InboundInviteActionController.notifyInviteReceived()
        lifecycleScope.launch {
            inboundInviteRepository.setPendingInvite(code, InboundInviteSource.AppLink)
        }
    }
}
