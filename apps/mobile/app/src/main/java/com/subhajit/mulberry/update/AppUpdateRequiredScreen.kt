package com.subhajit.mulberry.update

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subhajit.mulberry.R
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.wallpaper.ui.WallpaperPrimaryButton

/** Stands in for the whole app while this build is below the minimum. */
@Composable
fun AppUpdateRequiredScreen(onUpdate: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Image(painter = painterResource(R.drawable.brand_iconmark_color), contentDescription = null)
        Text(
            text = "Time to update",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
            style = TextStyle(fontFamily = PoppinsFontFamily, fontWeight = FontWeight.Bold, fontSize = 29.sp, lineHeight = 36.sp),
            modifier = Modifier
                .padding(top = 24.dp)
                .semantics { heading() }
        )
        Text(
            text = "This version of Mulberry is no longer supported.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(top = 12.dp)
                .widthIn(max = 420.dp)
        )
        Spacer(modifier = Modifier.weight(1f))
        WallpaperPrimaryButton(
            text = "Update",
            onClick = onUpdate,
            enabled = true,
            modifier = Modifier.widthIn(max = 480.dp)
        )
    }
}
