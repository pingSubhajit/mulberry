package com.subhajit.mulberry

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.subhajit.mulberry.canvas.CanvasRuntime
import com.subhajit.mulberry.canvas.CanvasRuntimeEvent
import com.subhajit.mulberry.sync.CanvasSyncRepository
import com.subhajit.mulberry.ui.theme.MulberryTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ClearDoodlesShortcutActivity : ComponentActivity() {

    @Inject lateinit var canvasRuntime: CanvasRuntime
    @Inject lateinit var canvasSyncRepository: CanvasSyncRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)
        setContent {
            MulberryTheme {
                ClearDoodlesShortcutDialog(
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

    override fun onStart() {
        super.onStart()
        canvasSyncRepository.start()
    }
}

@Composable
private fun ClearDoodlesShortcutDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_clear_canvas_title)) },
        text = { Text(stringResource(R.string.home_clear_canvas_body)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm
            ) {
                Text(stringResource(R.string.home_clear_canvas_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_clear_canvas_cancel))
            }
        }
    )
}
