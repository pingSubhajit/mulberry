package com.subhajit.mulberry.sync

import android.content.Context
import android.util.Log
import com.subhajit.mulberry.app.AppForegroundState
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class DrawReminderNotificationHandler @Inject constructor(
    private val sessionBootstrapRepository: SessionBootstrapRepository
) {
    suspend fun shouldNotify(payload: DrawReminderPushPayload): Boolean {
        if (AppForegroundState.isForeground.value) return false

        val bootstrap = sessionBootstrapRepository.state.first()
        if (bootstrap.pairingStatus != PairingStatus.PAIRED) return false
        val activePairSessionId = bootstrap.pairSessionId ?: return false
        if (payload.pairSessionId == null || payload.pairSessionId != activePairSessionId) return false

        return true
    }

    suspend fun handleReminder(context: Context, payload: DrawReminderPushPayload): Boolean {
        val should = runCatching { shouldNotify(payload) }.getOrDefault(false)
        if (!should) return false
        DrawReminderNotificationPresenter.show(context, payload)
        return true
    }

    suspend fun debugEvaluateAndLog(payload: DrawReminderPushPayload): Boolean {
        val should = shouldNotify(payload)
        Log.i(TAG, "Draw reminder gating shouldNotify=$should reminderCount=${payload.reminderCount}")
        return should
    }

    private companion object {
        const val TAG = "MulberryDrawReminder"
    }
}

