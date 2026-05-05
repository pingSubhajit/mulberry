package com.subhajit.mulberry.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PartnerVisibilitySheetController {
    private val _pendingAction = MutableStateFlow(false)
    val pendingAction: StateFlow<Boolean> = _pendingAction.asStateFlow()

    fun requestOpen() {
        _pendingAction.value = true
    }

    fun markHandled() {
        _pendingAction.value = false
    }
}

