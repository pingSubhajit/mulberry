package com.subhajit.mulberry.app.shortcut

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppShortcutAction(val intentAction: String) {
    ClearDoodles("com.subhajit.mulberry.action.CLEAR_DOODLES"),
    ChangeWallpaper("com.subhajit.mulberry.action.CHANGE_WALLPAPER"),
    OpenCanvas("com.subhajit.mulberry.action.OPEN_CANVAS"),
    ShowPairingConfirmation("com.subhajit.mulberry.action.SHOW_PAIRING_CONFIRMATION"),
    ShowPairingHub("com.subhajit.mulberry.action.SHOW_PAIRING_HUB"),
    ShowPartnerVisibilitySheet("com.subhajit.mulberry.action.SHOW_PARTNER_VISIBILITY_SHEET"),
    ShowSettings("com.subhajit.mulberry.action.SHOW_SETTINGS")
}

object AppShortcutActionController {
    private val _pendingAction = MutableStateFlow<AppShortcutAction?>(null)
    val pendingAction: StateFlow<AppShortcutAction?> = _pendingAction.asStateFlow()

    fun dispatch(intent: Intent?): Boolean {
        val action = AppShortcutAction.entries.firstOrNull {
            it.intentAction == intent?.action
        } ?: return false
        _pendingAction.value = action
        return true
    }

    fun markHandled(action: AppShortcutAction) {
        if (_pendingAction.value == action) {
            _pendingAction.value = null
        }
    }
}
