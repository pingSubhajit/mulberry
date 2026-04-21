package com.subhajit.mulberry.sync

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
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
        serviceScope.launch {
            fcmTokenRepository.registerToken(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = BackgroundCanvasSyncPayloadParser.parse(message.data) ?: return
        backgroundCanvasSyncScheduler.enqueueCanvasUpdated(
            pairSessionId = payload.pairSessionId,
            latestRevision = payload.latestRevision
        )
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
