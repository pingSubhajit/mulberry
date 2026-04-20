package com.subhajit.elaris.data.bootstrap

import kotlinx.coroutines.flow.Flow

interface SessionBootstrapRepository {
    val state: Flow<SessionBootstrapState>
    val session: Flow<AppSession?>

    suspend fun getCurrentSession(): AppSession?

    suspend fun cacheBootstrap(state: SessionBootstrapState)
    suspend fun cacheSession(session: AppSession?)
    suspend fun setWallpaperConfigured(configured: Boolean)

    suspend fun seedDemoSession()

    suspend fun reset()
}
