package com.subhajit.mulberry.analytics

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DataStoreAnalyticsQueueStoreTest {
    @Test
    fun `events survive a restart`() = runTest {
        val file = File.createTempFile("mulberry-analytics-queue", ".preferences_pb")
        val firstJob = Job()
        val first = store(CoroutineScope(StandardTestDispatcher(testScheduler) + firstJob), file)
        first.append(record("a"), capacity = 200)
        first.append(record("b"), capacity = 200)
        firstJob.cancelAndJoin()

        val second = store(backgroundScope, file)

        assertEquals(listOf(record("a"), record("b")), second.peek(limit = 50))
    }

    @Test
    fun `keeps at most the capacity, dropping the oldest first`() = runTest {
        val store = newStore()

        (1..205).forEach { store.append(record("e$it"), capacity = 200) }

        val kept = store.peek(limit = 500)
        assertEquals(200, kept.size)
        assertEquals("e6", kept.first().id)
        assertEquals("e205", kept.last().id)
    }

    @Test
    fun `peek returns the oldest events and remove drops only the given ids`() = runTest {
        val store = newStore()
        listOf("a", "b", "c").forEach { store.append(record(it), capacity = 200) }

        assertEquals(listOf("a", "b"), store.peek(limit = 2).map { it.id })

        store.remove(setOf("a", "b"))

        assertEquals(listOf("c"), store.peek(limit = 50).map { it.id })
    }

    private fun TestScope.newStore(): DataStoreAnalyticsQueueStore =
        store(backgroundScope, File.createTempFile("mulberry-analytics-queue", ".preferences_pb"))

    private fun store(scope: CoroutineScope, file: File) = DataStoreAnalyticsQueueStore(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    )

    private fun record(id: String) = AnalyticsEventRecord(
        id = id,
        event = "mulberry_push_opened",
        occurredAt = "2026-11-04T10:00:00.000Z",
        properties = mapOf("push_type" to "CANVAS_NUDGE")
    )
}
