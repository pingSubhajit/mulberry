package com.subhajit.mulberry

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.subhajit.mulberry.app.MulberryApp
import com.subhajit.mulberry.ui.theme.MulberryTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MulberryTheme {
                MulberryApp()
            }
        }
    }
}
