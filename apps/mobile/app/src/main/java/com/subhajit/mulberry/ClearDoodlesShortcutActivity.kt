package com.subhajit.mulberry

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subhajit.mulberry.canvas.CanvasRuntime
import com.subhajit.mulberry.canvas.CanvasRuntimeEvent
import com.subhajit.mulberry.core.ui.mulberryTapScale
import com.subhajit.mulberry.data.bootstrap.CanvasMode
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.drawing.model.DrawingOperationType
import com.subhajit.mulberry.sync.CanvasKeys
import com.subhajit.mulberry.sync.CanvasSyncRepository
import com.subhajit.mulberry.sync.SyncOperationPayload
import com.subhajit.mulberry.sync.newClientOperation
import com.subhajit.mulberry.app.shortcut.AppShortcutAction
import com.subhajit.mulberry.ui.theme.MulberryTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

@AndroidEntryPoint
class ClearDoodlesShortcutActivity : ComponentActivity() {

    @Inject lateinit var canvasRuntime: CanvasRuntime
    @Inject lateinit var canvasSyncRepository: CanvasSyncRepository
    @Inject lateinit var sessionBootstrapRepository: SessionBootstrapRepository
    @Inject lateinit var drawingRepository: DrawingRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)
        setContent {
            val bootstrapState by sessionBootstrapRepository.state.collectAsStateWithLifecycle(
                initialValue = com.subhajit.mulberry.data.bootstrap.SessionBootstrapState()
            )
            val isDedicated = bootstrapState.canvasMode == CanvasMode.DEDICATED
            val action = intent?.action
            MulberryTheme {
                when (action) {
                    AppShortcutAction.ClearPartnerWallpaper.intentAction -> {
                        ClearShortcutConfirmationDialog(
                            titleRes = R.string.shortcut_clear_partner_wallpaper_title,
                            bodyRes = R.string.shortcut_clear_partner_wallpaper_body,
                            onDismiss = ::finish,
                            onConfirm = {
                                lifecycleScope.launch {
                                    canvasRuntime.submitAndAwait(CanvasRuntimeEvent.ClearCanvas)
                                    finish()
                                }
                            }
                        )
                    }
                    AppShortcutAction.ClearMyWallpaper.intentAction -> {
                        ClearShortcutConfirmationDialog(
                            titleRes = R.string.shortcut_clear_my_wallpaper_title,
                            bodyRes = R.string.shortcut_clear_my_wallpaper_body,
                            onDismiss = ::finish,
                            onConfirm = {
                                lifecycleScope.launch {
                                    val bootstrap = sessionBootstrapRepository.state.first()
                                    val partnerUserId = bootstrap.partnerUserId
                                    if (!partnerUserId.isNullOrBlank()) {
                                        val canvasKey = CanvasKeys.user(partnerUserId)
                                        drawingRepository.clearCanvas(canvasKey)
                                        canvasSyncRepository.queueLocalOperation(
                                            newClientOperation(
                                                type = DrawingOperationType.CLEAR_CANVAS,
                                                canvasKey = canvasKey,
                                                strokeId = null,
                                                payload = SyncOperationPayload.ClearCanvas
                                            )
                                        )
                                    }
                                    finish()
                                }
                            }
                        )
                    }
                    else -> {
                        if (isDedicated) {
                            ClearDedicatedShortcutDialog(
                                onDismiss = ::finish,
                                onClearPartnerWallpaper = {
                                    lifecycleScope.launch {
                                        canvasRuntime.submitAndAwait(CanvasRuntimeEvent.ClearCanvas)
                                        finish()
                                    }
                                },
                                onClearMyWallpaper = {
                                    lifecycleScope.launch {
                                        val bootstrap = sessionBootstrapRepository.state.first()
                                        val partnerUserId = bootstrap.partnerUserId
                                        if (!partnerUserId.isNullOrBlank()) {
                                            val canvasKey = CanvasKeys.user(partnerUserId)
                                            drawingRepository.clearCanvas(canvasKey)
                                            canvasSyncRepository.queueLocalOperation(
                                                newClientOperation(
                                                    type = DrawingOperationType.CLEAR_CANVAS,
                                                    canvasKey = canvasKey,
                                                    strokeId = null,
                                                    payload = SyncOperationPayload.ClearCanvas
                                                )
                                            )
                                        }
                                        finish()
                                    }
                                }
                            )
                        } else {
                            ClearShortcutConfirmationDialog(
                                titleRes = R.string.home_clear_canvas_title,
                                bodyRes = R.string.home_clear_canvas_body,
                                onDismiss = ::finish,
                                onConfirm = {
                                    lifecycleScope.launch {
                                        canvasRuntime.submitAndAwait(CanvasRuntimeEvent.ClearCanvas)
                                        finish()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        canvasSyncRepository.start()
    }
}

@Composable
private fun ClearShortcutConfirmationDialog(
    titleRes: Int,
    bodyRes: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = { Text(stringResource(bodyRes)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.mulberryTapScale()
            ) {
                Text(stringResource(R.string.home_clear_canvas_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.mulberryTapScale()) {
                Text(stringResource(R.string.home_clear_canvas_cancel))
            }
        }
    )
}

@Composable
private fun ClearDedicatedShortcutDialog(
    onDismiss: () -> Unit,
    onClearPartnerWallpaper: () -> Unit,
    onClearMyWallpaper: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_clear_canvas_title_dedicated)) },
        text = { Text(stringResource(R.string.home_clear_canvas_body_dedicated)) },
        confirmButton = {
            androidx.compose.foundation.layout.Column {
                TextButton(onClick = onClearPartnerWallpaper, modifier = Modifier.mulberryTapScale()) {
                    Text(stringResource(R.string.home_clear_partner_wallpaper))
                }
                TextButton(onClick = onClearMyWallpaper, modifier = Modifier.mulberryTapScale()) {
                    Text(stringResource(R.string.home_clear_my_wallpaper))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.mulberryTapScale()) {
                Text(stringResource(R.string.home_clear_canvas_cancel))
            }
        }
    )
}
