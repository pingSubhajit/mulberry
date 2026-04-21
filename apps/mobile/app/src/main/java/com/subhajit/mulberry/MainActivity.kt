package com.subhajit.mulberry

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.subhajit.mulberry.app.MulberryApp
import com.subhajit.mulberry.sync.CanvasSyncRepository
import com.subhajit.mulberry.ui.theme.MulberryTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var canvasSyncRepository: CanvasSyncRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    }

    override fun onStop() {
        canvasSyncRepository.stop()
        super.onStop()
    }
}
