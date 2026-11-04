package com.subhajit.mulberry.analytics

import android.util.Log
import com.subhajit.mulberry.app.AppForegroundState
import com.subhajit.mulberry.app.di.ApplicationScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface AppAnalytics {
    /** Records [event] now and sends it later. Never blocks, throws or shows anything. */
    fun track(event: AnalyticsEvent)
}

/**
 * Keeps app events on disk and sends them to `POST /analytics/events` in batches: on launch, a few
 * seconds after a new event, and when the app goes to the background.
 */
@Singleton
class AnalyticsQueue internal constructor(
    private val store: AnalyticsQueueStore,
    private val sender: AnalyticsEventSender,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val newId: () -> String
) : AppAnalytics {
    @Inject
    constructor(
        store: DataStoreAnalyticsQueueStore,
        sender: ApiAnalyticsEventSender,
        @ApplicationScope scope: CoroutineScope
    ) : this(
        store = store,
        sender = sender,
        scope = scope,
        nowMs = System::currentTimeMillis,
        newId = { UUID.randomUUID().toString() }
    )

    // One consumer keeps events on disk in the order they happened.
    private val incoming = Channel<AnalyticsEventRecord>(Channel.UNLIMITED)
    private val flushMutex = Mutex()
    private val scheduleLock = Any()
    private var scheduledFlush: Job? = null

    init {
        scope.launch {
            for (record in incoming) {
                quietly("store event") { store.append(record, MAX_QUEUED_EVENTS) }
                scheduleFlush()
            }
        }
    }

    override fun track(event: AnalyticsEvent) {
        incoming.trySend(
            AnalyticsEventRecord(
                id = newId().lowercase(Locale.US),
                event = event.name,
                occurredAt = formatUtc(nowMs()),
                properties = event.properties
            )
        )
    }

    /** Sends what an earlier run left behind, and sends again each time the app is backgrounded. */
    fun start(isForeground: Flow<Boolean> = AppForegroundState.isForeground) {
        flushNow()
        scope.launch {
            var wasForeground = false
            isForeground.collect { foreground ->
                if (wasForeground && !foreground) flushNow()
                wasForeground = foreground
            }
        }
    }

    fun flushNow() {
        scope.launch { flush() }
    }

    internal suspend fun flush() {
        flushMutex.withLock {
            quietly("send events") { sendQueuedBatches() }
        }
    }

    private suspend fun sendQueuedBatches() {
        while (true) {
            val batch = store.peek(BATCH_SIZE)
            if (batch.isEmpty()) return
            if (sender.send(batch) == AnalyticsSendResult.KEEP) return
            store.remove(batch.mapTo(HashSet()) { it.id })
        }
    }

    private fun scheduleFlush() {
        synchronized(scheduleLock) {
            if (scheduledFlush?.isActive == true) return
            scheduledFlush = scope.launch {
                delay(FLUSH_DELAY_MS)
                flush()
            }
        }
    }

    private suspend fun quietly(action: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "Couldn't $action", error)
        }
    }

    internal companion object {
        const val MAX_QUEUED_EVENTS = 200
        const val BATCH_SIZE = 50
        const val FLUSH_DELAY_MS = 3_000L
        private const val TAG = "MulberryAnalytics"

        // java.time needs API 26; the app supports 24.
        fun formatUtc(epochMs: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(epochMs))
    }
}
