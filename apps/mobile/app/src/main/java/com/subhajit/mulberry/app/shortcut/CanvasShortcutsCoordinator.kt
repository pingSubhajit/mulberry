package com.subhajit.mulberry.app.shortcut

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import com.subhajit.mulberry.ClearDoodlesShortcutActivity
import com.subhajit.mulberry.R
import com.subhajit.mulberry.app.di.ApplicationScope
import com.subhajit.mulberry.data.bootstrap.CanvasMode
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Singleton
class CanvasShortcutsCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionBootstrapRepository: SessionBootstrapRepository,
    @ApplicationScope private val applicationScope: CoroutineScope
) {
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            applicationScope.launch {
                sessionBootstrapRepository.state
                    .map { state ->
                        Triple(state.canvasMode, state.partnerUserId, state.pairingStatus)
                    }
                    .distinctUntilChanged()
                    .collect { (canvasMode, partnerUserId, pairingStatus) ->
                        updateShortcuts(canvasMode, partnerUserId, pairingStatus)
                    }
            }
        }
    }

    private fun updateShortcuts(
        canvasMode: CanvasMode,
        partnerUserId: String?,
        pairingStatus: PairingStatus
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val shortcutManager = context.getSystemService(ShortcutManager::class.java) ?: return
        val dedicatedEnabled = canvasMode == CanvasMode.DEDICATED &&
            pairingStatus == PairingStatus.PAIRED &&
            !partnerUserId.isNullOrBlank()

        val shortcuts = if (dedicatedEnabled) {
            listOf(
                buildClearShortcut(
                    id = DYNAMIC_CLEAR_PARTNER_SHORTCUT_ID,
                    shortLabelRes = R.string.shortcut_clear_partner_wallpaper_short_label,
                    longLabelRes = R.string.shortcut_clear_partner_wallpaper_long_label,
                    action = AppShortcutAction.ClearPartnerWallpaper.intentAction
                ),
                buildClearShortcut(
                    id = DYNAMIC_CLEAR_MY_SHORTCUT_ID,
                    shortLabelRes = R.string.shortcut_clear_my_wallpaper_short_label,
                    longLabelRes = R.string.shortcut_clear_my_wallpaper_long_label,
                    action = AppShortcutAction.ClearMyWallpaper.intentAction
                )
            )
        } else {
            listOf(
                buildClearShortcut(
                    // Never use the legacy manifest shortcut id ("clear_doodles") here.
                    id = DYNAMIC_CLEAR_SHARED_SHORTCUT_ID,
                    shortLabelRes = R.string.shortcut_clear_doodles_short_label,
                    longLabelRes = R.string.shortcut_clear_doodles_long_label,
                    action = AppShortcutAction.ClearDoodles.intentAction
                )
            )
        }

        runCatching {
            shortcutManager.dynamicShortcuts = shortcuts
        }.onFailure { error ->
            Log.w(TAG, "Unable to update dynamic shortcuts", error)
        }
    }

    private fun buildClearShortcut(
        id: String,
        shortLabelRes: Int,
        longLabelRes: Int,
        action: String
    ): ShortcutInfo {
        val intent = Intent(context, ClearDoodlesShortcutActivity::class.java).apply {
            this.action = action
        }
        return ShortcutInfo.Builder(context, id)
            .setShortLabel(context.getString(shortLabelRes))
            .setLongLabel(context.getString(longLabelRes))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_clear_doodles))
            .setIntent(intent)
            .build()
    }

    private companion object {
        const val DYNAMIC_CLEAR_SHARED_SHORTCUT_ID = "clear_canvas"
        const val DYNAMIC_CLEAR_PARTNER_SHORTCUT_ID = "clear_partner_wallpaper"
        const val DYNAMIC_CLEAR_MY_SHORTCUT_ID = "clear_my_wallpaper"
        const val TAG = "MulberryShortcuts"
    }
}
