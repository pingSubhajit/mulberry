package com.subhajit.mulberry.sync

import com.subhajit.mulberry.app.AppForegroundState
import com.subhajit.mulberry.data.bootstrap.AppSession
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawReminderNotificationHandlerTest {
    @Test
    fun skipsWhenUnpaired() = runBlocking {
        AppForegroundState.setForeground(false)
        val repository = TestSessionBootstrapRepository(
            SessionBootstrapState(pairingStatus = PairingStatus.UNPAIRED)
        )
        val handler = DrawReminderNotificationHandler(repository)

        val should = handler.shouldNotify(
            DrawReminderPushPayload(pairSessionId = "pair-1", partnerDisplayName = "Partner", reminderCount = 0)
        )
        assertFalse(should)
    }

    @Test
    fun skipsWhenPairSessionDoesNotMatch() = runBlocking {
        AppForegroundState.setForeground(false)
        val repository = TestSessionBootstrapRepository(
            SessionBootstrapState(pairingStatus = PairingStatus.PAIRED, pairSessionId = "pair-1")
        )
        val handler = DrawReminderNotificationHandler(repository)

        val should = handler.shouldNotify(
            DrawReminderPushPayload(pairSessionId = "pair-2", partnerDisplayName = "Partner", reminderCount = 0)
        )
        assertFalse(should)
    }

    @Test
    fun notifiesWhenPairedAndMatches() = runBlocking {
        AppForegroundState.setForeground(false)
        val repository = TestSessionBootstrapRepository(
            SessionBootstrapState(pairingStatus = PairingStatus.PAIRED, pairSessionId = "pair-1")
        )
        val handler = DrawReminderNotificationHandler(repository)

        val should = handler.shouldNotify(
            DrawReminderPushPayload(pairSessionId = "pair-1", partnerDisplayName = "Partner", reminderCount = 0)
        )
        assertTrue(should)
    }

    @Test
    fun skipsWhenForeground() = runBlocking {
        AppForegroundState.setForeground(true)
        val repository = TestSessionBootstrapRepository(
            SessionBootstrapState(pairingStatus = PairingStatus.PAIRED, pairSessionId = "pair-1")
        )
        val handler = DrawReminderNotificationHandler(repository)

        val should = handler.shouldNotify(
            DrawReminderPushPayload(pairSessionId = "pair-1", partnerDisplayName = "Partner", reminderCount = 0)
        )
        assertFalse(should)
    }
}

private class TestSessionBootstrapRepository(initial: SessionBootstrapState) : SessionBootstrapRepository {
    private val bootstrapStateFlow = MutableStateFlow(initial)
    private val sessionStateFlow = MutableStateFlow<AppSession?>(null)

    override val state: Flow<SessionBootstrapState> = bootstrapStateFlow.asStateFlow()
    override val session: Flow<AppSession?> = sessionStateFlow.asStateFlow()

    override suspend fun getCurrentSession(): AppSession? = sessionStateFlow.value
    override suspend fun cacheBootstrap(state: SessionBootstrapState) {
        bootstrapStateFlow.value = state
    }

    override suspend fun cacheSession(session: AppSession?) {
        sessionStateFlow.value = session
    }

    override suspend fun setWallpaperConfigured(configured: Boolean) = Unit
    override suspend fun seedDemoSession() = Unit
    override suspend fun reset() = Unit
}
