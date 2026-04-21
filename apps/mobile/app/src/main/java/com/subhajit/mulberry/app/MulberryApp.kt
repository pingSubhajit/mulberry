package com.subhajit.mulberry.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.subhajit.mulberry.navigation.MulberryNavHost

@Composable
fun MulberryApp() {
    Surface(modifier = Modifier.fillMaxSize()) {
        MulberryNavHost()
    }
}
