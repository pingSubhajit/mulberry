package com.subhajit.mulberry.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.subhajit.mulberry.ui.theme.MulberryPrimary

/**
 * The full-screen editors' round tool button (sticker, text, photo and share): the selected one
 * is ringed in the accent colour.
 */
@Composable
internal fun EditorIconButton(
    iconRes: Int,
    contentDescription: String,
    selected: Boolean,
    tint: Color = Color.White,
    stateDescription: String? = null,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(Color.White.copy(alpha = 0.10f), CircleShape)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MulberryPrimary, CircleShape)
                } else {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button
            ) { onClick() }
            .semantics(mergeDescendants = true) {
                this.selected = selected
                stateDescription?.let { this.stateDescription = it }
            },
        contentAlignment = Alignment.Center
    ) {
        // Use Image for bitmap resources (e.g. the color icon); Icon for vectors.
        if (tint == Color.Unspecified) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                modifier = Modifier.size(22.dp)
            )
        } else {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** The editors' round top-bar button, as the camera's Close and the photo editor's Retake. */
@Composable
internal fun RoundCameraButton(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean = true,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(22.dp)
        )
    }
}
