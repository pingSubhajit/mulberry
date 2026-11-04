package com.subhajit.mulberry.analytics

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsQueueTest {
    private val store = InMemoryQueueStore()
    private val sender = ScriptedSender()

    @Test
    fun `stamps each event with a lowercase id and a utc time when it happens`() = runTest {
        val queue = queue(nowMs = 1_793_793_600_123L, id = "6F9619FF-8B86-D011-B42D-00C04FC964FF")

        queue.track(AnalyticsEvent.PushOpened(PushType.REACTION))
        runCurrent()

        assertEquals(
            AnalyticsEventRecord(
                id = "6f9619ff-8b86-d011-b42d-00c04fc964ff",
                event = "mulberry_push_opened",
                occurredAt = "2026-11-04T12:00:00.123Z",
                properties = mapOf("push_type" to "REACTION")
            ),
            store.events.single()
        )
    }

    @Test
    fun `sends a few seconds after a new event`() = runTest {
        val queue = queue()

        queue.track(AnalyticsEvent.OnboardingStepViewed(OnboardingStep.WELCOME))
        advanceTimeBy(AnalyticsQueue.FLUSH_DELAY_MS - 1)
        assertTrue(sender.batches.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, sender.batches.single().size)
        assertTrue(store.events.isEmpty())
    }

    @Test
    fun `sends in batches of at most 50`() = runTest {
        val queue = queue()
        repeat(120) { store.events += record("e$it") }

        queue.flush()

        assertEquals(listOf(50, 50, 20), sender.batches.map { it.size })
        assertEquals("e0", sender.batches.first().first().id)
        assertTrue(store.events.isEmpty())
    }

    @Test
    fun `a 4xx reply, including 401, drops the batch`() = runTest {
        sender.results += AnalyticsSendResult.DROP
        val queue = queue()
        store.events += record("a")

        queue.flush()

        assertTrue(store.events.isEmpty())
        assertEquals(AnalyticsSendResult.DROP, ApiAnalyticsEventSender.resultForStatus(401))
        assertEquals(AnalyticsSendResult.DROP, ApiAnalyticsEventSender.resultForStatus(400))
        assertEquals(AnalyticsSendResult.DROP, ApiAnalyticsEventSender.resultForStatus(202))
    }

    @Test
    fun `a 5xx reply or network error keeps the batch for the next try`() = runTest {
        val queue = queue()
        store.events += listOf(record("a"), record("b"))

        sender.results += AnalyticsSendResult.KEEP
        queue.flush()
        assertEquals(listOf("a", "b"), store.events.map { it.id })

        sender.failure = IOException("offline")
        queue.flush()
        assertEquals(listOf("a", "b"), store.events.map { it.id })

        sender.failure = null
        queue.flush()
        assertTrue(store.events.isEmpty())
        assertEquals(listOf("a", "a", "a"), sender.batches.map { it.first().id })
        assertEquals(AnalyticsSendResult.KEEP, ApiAnalyticsEventSender.resultForStatus(500))
        assertEquals(AnalyticsSendResult.KEEP, ApiAnalyticsEventSender.resultForStatus(503))
    }

    @Test
    fun `sends on launch and when the app goes to the background`() = runTest {
        val queue = queue()
        val foreground = MutableStateFlow(false)
        store.events += record("left-from-last-run")

        queue.start(foreground)
        runCurrent()
        assertEquals(listOf("left-from-last-run"), sender.batches.flatten().map { it.id })

        foreground.value = true
        runCurrent()
        store.events += record("while-open")
        foreground.value = false
        runCurrent()

        assertEquals(listOf("left-from-last-run", "while-open"), sender.batches.flatten().map { it.id })
    }

    @Test
    fun `events arriving while a batch is in flight stay queued`() = runTest {
        val queue = queue()
        store.events += record("a")
        sender.onSend = { store.events += record("late") }
        sender.results += listOf(AnalyticsSendResult.DROP, AnalyticsSendResult.KEEP)

        queue.flush()

        assertEquals(listOf("late"), store.events.map { it.id })
    }

    private fun TestScope.queue(
        nowMs: Long = 0L,
        id: String = "00000000-0000-0000-0000-000000000000"
    ) = AnalyticsQueue(
        store = store,
        sender = sender,
        scope = backgroundScope,
        nowMs = { nowMs },
        newId = { id }
    )

    private fun record(id: String) = AnalyticsEventRecord(
        id = id,
        event = "mulberry_onboarding_step_viewed",
        occurredAt = "2026-11-04T10:00:00.000Z",
        properties = mapOf("step" to "welcome")
    )

    private class InMemoryQueueStore : AnalyticsQueueStore {
        val events = mutableListOf<AnalyticsEventRecord>()

        override suspend fun append(record: AnalyticsEventRecord, capacity: Int) {
            events += record
            while (events.size > capacity) events.removeAt(0)
        }

        override suspend fun peek(limit: Int): List<AnalyticsEventRecord> = events.take(limit)

        override suspend fun remove(ids: Set<String>) {
            events.removeAll { it.id in ids }
        }
    }

    private class ScriptedSender : AnalyticsEventSender {
        val batches = mutableListOf<List<AnalyticsEventRecord>>()
        val results = ArrayDeque<AnalyticsSendResult>()
        var failure: Exception? = null
        var onSend: (() -> Unit)? = null

        override suspend fun send(events: List<AnalyticsEventRecord>): AnalyticsSendResult {
            batches += events
            onSend?.invoke()
            onSend = null
            failure?.let { throw it }
            return results.removeFirstOrNull() ?: AnalyticsSendResult.DROP
        }
    }
}
