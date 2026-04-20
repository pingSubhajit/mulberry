package com.subhajit.elaris.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.subhajit.elaris.navigation.ElarisNavHost

@Composable
fun ElarisApp() {
    Surface(modifier = Modifier.fillMaxSize()) {
        ElarisNavHost()
    }
}
