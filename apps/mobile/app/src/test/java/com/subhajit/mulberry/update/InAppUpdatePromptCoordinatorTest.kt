package com.subhajit.mulberry.update

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppUpdatePromptCoordinatorTest {

    @Test
    fun `manual mode always prompts and never mutates state`() = runTest {
        val store = FakeStore()
        val coordinator = InAppUpdatePromptCoordinator(store)

        val shouldPrompt = coordinator.shouldPromptForUpdate(
            nowMs = 1000L,
            availableVersionCode = 123,
            isManual = true
        )

        assertTrue(shouldPrompt)
        assertTrue(store.get() == InAppUpdatePromptState())
    }

    @Test
    fun `cooldown suppresses prompting for same version`() = runTest {
        val store = FakeStore(
            InAppUpdatePromptState(
                declinedVersionCode = 200,
                declinedAtMs = 10_000L
            )
        )
        val coordinator = InAppUpdatePromptCoordinator(store)

        val shouldPromptEarly = coordinator.shouldPromptForUpdate(
            nowMs = 10_000L + coordinator.cooldownMs() - 1,
            availableVersionCode = 200,
            isManual = false
        )
        assertFalse(shouldPromptEarly)

        val shouldPromptOnTime = coordinator.shouldPromptForUpdate(
            nowMs = 10_000L + coordinator.cooldownMs(),
            availableVersionCode = 200,
            isManual = false
        )
        assertTrue(shouldPromptOnTime)
    }

    @Test
    fun `newer update bypasses cooldown`() = runTest {
        val store = FakeStore(
            InAppUpdatePromptState(
                declinedVersionCode = 200,
                declinedAtMs = 10_000L
            )
        )
        val coordinator = InAppUpdatePromptCoordinator(store)

        val shouldPrompt = coordinator.shouldPromptForUpdate(
            nowMs = 10_000L + 1,
            availableVersionCode = 201,
            isManual = false
        )

        assertTrue(shouldPrompt)
    }

    @Test
    fun `records declines`() = runTest {
        val store = FakeStore()
        val coordinator = InAppUpdatePromptCoordinator(store)

        coordinator.recordDeclined(nowMs = 1234L, availableVersionCode = 88)

        assertTrue(
            store.get() == InAppUpdatePromptState(declinedVersionCode = 88, declinedAtMs = 1234L)
        )
    }

    private class FakeStore(
        initial: InAppUpdatePromptState = InAppUpdatePromptState()
    ) : InAppUpdatePromptStateStore {
        private val ref = AtomicReference(initial)

        override suspend fun get(): InAppUpdatePromptState = ref.get()

        override suspend fun updateAndGet(
            transform: (InAppUpdatePromptState) -> InAppUpdatePromptState
        ): InAppUpdatePromptState {
            while (true) {
                val current = ref.get()
                val updated = transform(current)
                if (ref.compareAndSet(current, updated)) return updated
            }
        }
    }
}

