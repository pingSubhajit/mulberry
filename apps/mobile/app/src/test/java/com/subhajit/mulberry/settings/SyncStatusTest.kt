package com.subhajit.mulberry.settings

import com.subhajit.mulberry.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncStatusTest {
    @Test
    fun `unpaired accounts have nothing to sync with`() {
        assertEquals(SyncStatus.NotPaired, SyncStatus.of(paired = false, syncState = SyncState.Connected))
    }

    @Test
    fun `paired accounts show the canvas connection as the iPhone does`() {
        assertEquals(SyncStatus.Connected, SyncStatus.of(paired = true, syncState = SyncState.Connected))
        assertEquals(SyncStatus.Connecting, SyncStatus.of(paired = true, syncState = SyncState.Connecting))
        assertEquals(SyncStatus.Updating, SyncStatus.of(paired = true, syncState = SyncState.Recovering))
        assertEquals(SyncStatus.Offline, SyncStatus.of(paired = true, syncState = SyncState.Disconnected))
        assertEquals(SyncStatus.Offline, SyncStatus.of(paired = true, syncState = SyncState.Error("closed")))
    }
}
