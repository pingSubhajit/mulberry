package com.subhajit.elaris.data.bootstrap

import kotlinx.coroutines.flow.Flow

interface SessionBootstrapRepository {
    val state: Flow<SessionBootstrapState>

    suspend fun completeOnboarding()

    suspend fun setPairingStatus(status: PairingStatus)

    suspend fun setWallpaperConfigured(configured: Boolean)

    suspend fun setSessionDisplayState(state: SessionDisplayState)

    suspend fun seedDemoSession()

    suspend fun reset()
}
