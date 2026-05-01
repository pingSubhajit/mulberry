package com.subhajit.mulberry.app.snackbar

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class MulberrySnackbarRequest(
    val message: String,
    val actionLabel: String? = null,
    val actionKey: String? = null,
    val isIndefinite: Boolean = false
)

object MulberrySnackbarController {
    const val ACTION_COMPLETE_IN_APP_UPDATE = "complete_in_app_update"

    private val _requests = MutableSharedFlow<MulberrySnackbarRequest>(extraBufferCapacity = 1)
    val requests: SharedFlow<MulberrySnackbarRequest> = _requests.asSharedFlow()

    private val _actions = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val actions: SharedFlow<String> = _actions.asSharedFlow()

    fun show(request: MulberrySnackbarRequest): Boolean = _requests.tryEmit(request)

    suspend fun emitAction(actionKey: String) {
        _actions.emit(actionKey)
    }
}

