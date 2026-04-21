package com.subhajit.mulberry

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.subhajit.mulberry.app.MulberryApp
import com.subhajit.mulberry.app.bootstrap.AppStartupGate
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

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition {
            AppStartupGate.keepSplashVisible.value
        }
        super.onCreate(savedInstanceState)
        AppStartupGate.armTimeout(lifecycleScope)
        enableEdgeToEdge()
        setContent {
            MulberryTheme {
                MulberryApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        canvasSyncRepository.start()
        lifecycleScope.launch {
            fcmTokenRepository.syncTokenWithBackend()
        }
    }

    override fun onStop() {
        canvasSyncRepository.stop()
        super.onStop()
    }
}
