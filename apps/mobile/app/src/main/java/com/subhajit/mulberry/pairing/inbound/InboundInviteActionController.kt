package com.subhajit.mulberry.pairing.inbound

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object InboundInviteActionController {
    private val _pendingAction = MutableStateFlow(false)
    val pendingAction: StateFlow<Boolean> = _pendingAction.asStateFlow()

    fun notifyInviteReceived() {
        _pendingAction.value = true
    }

    fun markHandled() {
        _pendingAction.value = false
    }
}

