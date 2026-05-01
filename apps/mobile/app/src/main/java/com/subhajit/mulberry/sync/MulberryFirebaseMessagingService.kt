package com.subhajit.mulberry.sync

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.subhajit.mulberry.app.AppForegroundState
import com.subhajit.mulberry.app.InAppSnackbarBus
import com.subhajit.mulberry.bootstrap.BootstrapRepository
import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.wallpaper.CanvasSnapshotRenderer
import com.subhajit.mulberry.wallpaper.WallpaperCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

@AndroidEntryPoint
class MulberryFirebaseMessagingService : FirebaseMessagingService() {
    @Inject lateinit var fcmTokenRepository: FcmTokenRepository
    @Inject lateinit var backgroundCanvasSyncScheduler: BackgroundCanvasSyncScheduler
    @Inject lateinit var canvasNudgeNotificationHandler: CanvasNudgeNotificationHandler
    @Inject lateinit var drawReminderNotificationHandler: DrawReminderNotificationHandler
    @Inject lateinit var bootstrapRepository: BootstrapRepository
    @Inject lateinit var canvasSyncRepository: CanvasSyncRepository
    @Inject lateinit var drawingRepository: DrawingRepository
    @Inject lateinit var canvasSnapshotRenderer: CanvasSnapshotRenderer
    @Inject lateinit var backgroundCanvasSyncCoordinator: BackgroundCanvasSyncCoordinator
    @Inject lateinit var wallpaperCoordinator: WallpaperCoordinator

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

        val disconnectedPayload = PairingDisconnectedPushPayloadParser.parse(message.data)
        if (disconnectedPayload != null) {
            Log.i(
                TAG,
                "Received pairing disconnected push pairSessionId=${disconnectedPayload.pairSessionId} " +
                    "actorUserId=${disconnectedPayload.actorUserId}"
            )
            if (!AppForegroundState.isForeground.value) {
                PairingNotificationPresenter.showPartnerUnpaired(this, disconnectedPayload)
            }
            return
        }

        val nudgePayload = CanvasNudgePushPayloadParser.parse(message.data)
        if (nudgePayload != null) {
            Log.i(
                TAG,
                "Received canvas nudge push pairSessionId=${nudgePayload.pairSessionId} " +
                    "latestRevision=${nudgePayload.latestRevision}"
            )
            serviceScope.launch {
                canvasNudgeNotificationHandler.handleNudge(this@MulberryFirebaseMessagingService, nudgePayload)
            }
            return
        }

        val drawReminderPayload = DrawReminderPushPayloadParser.parse(message.data)
        if (drawReminderPayload != null) {
            Log.i(
                TAG,
                "Received draw reminder push pairSessionId=${drawReminderPayload.pairSessionId} " +
                    "reminderCount=${drawReminderPayload.reminderCount}"
            )
            serviceScope.launch {
                drawReminderNotificationHandler.handleReminder(
                    this@MulberryFirebaseMessagingService,
                    drawReminderPayload
                )
            }
            return
        }

        val canvasModePayload = CanvasModeChangedPushPayloadParser.parse(message.data)
        if (canvasModePayload != null) {
            Log.i(
                TAG,
                "Received canvas mode changed push pairSessionId=${canvasModePayload.pairSessionId} " +
                    "mode=${canvasModePayload.canvasMode}"
            )
            serviceScope.launch {
                handleCanvasModeChanged(canvasModePayload)
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

    private suspend fun handleCanvasModeChanged(payload: CanvasModeChangedPushPayload) {
        val actorName = payload.actorDisplayName.takeIf { it.isNotBlank() } ?: "Your partner"
        val modeName = payload.canvasMode.displayName

        bootstrapRepository.refreshBootstrap()
        canvasSyncRepository.reset()
        drawingRepository.resetAllDrawingState()
        canvasSnapshotRenderer.clearSnapshots()
        canvasSyncRepository.start()

        val refreshed = bootstrapRepository.cachedState.first()
        backgroundCanvasSyncCoordinator.syncToLatestSnapshot(
            pairSessionId = refreshed.pairSessionId ?: payload.pairSessionId,
            latestRevisionHint = null
        )
        wallpaperCoordinator.ensureSnapshotCurrent()
        wallpaperCoordinator.notifyWallpaperUpdatedIfSelected()

        if (AppForegroundState.isForeground.value) {
            InAppSnackbarBus.show("$actorName switched canvas mode to $modeName.")
        } else {
            CanvasModeChangedNotificationPresenter.show(this, payload)
        }
    }
}
