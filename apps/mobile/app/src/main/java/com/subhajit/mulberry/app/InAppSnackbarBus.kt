package com.subhajit.mulberry.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object InAppSnackbarBus {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    fun show(message: String) {
        _messages.tryEmit(message)
    }
}

