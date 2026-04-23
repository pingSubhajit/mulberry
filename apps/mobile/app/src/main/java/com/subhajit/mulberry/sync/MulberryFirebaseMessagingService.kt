package com.subhajit.mulberry.sync

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.subhajit.mulberry.app.AppForegroundState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MulberryFirebaseMessagingService : FirebaseMessagingService() {
    @Inject lateinit var fcmTokenRepository: FcmTokenRepository
    @Inject lateinit var backgroundCanvasSyncScheduler: BackgroundCanvasSyncScheduler

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        Log.i(TAG, "Received refreshed FCM token")
        serviceScope.launch {
            fcmTokenRepository.registerToken(token)
                .onFailure { error ->
                    Log.w(TAG, "Unable to register refreshed FCM token", error)
                }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val pairingPayload = PairingConfirmedPushPayloadParser.parse(message.data)
        if (pairingPayload != null) {
            Log.i(
                TAG,
                "Received pairing confirmation push pairSessionId=${pairingPayload.pairSessionId} " +
                    "actorUserId=${pairingPayload.actorUserId}"
            )
            if (!AppForegroundState.isForeground.value) {
                PairingNotificationPresenter.showPartnerJoined(this, pairingPayload)
            }
            return
        }

        val payload = BackgroundCanvasSyncPayloadParser.parse(message.data) ?: return
        Log.i(
            TAG,
            "Received canvas update push pairSessionId=${payload.pairSessionId} " +
                "latestRevision=${payload.latestRevision}"
        )
        backgroundCanvasSyncScheduler.enqueueCanvasUpdated(
            pairSessionId = payload.pairSessionId,
            latestRevision = payload.latestRevision
        )
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MulberryFcm"
    }
}
