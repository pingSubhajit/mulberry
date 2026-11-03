package com.subhajit.mulberry.data.bootstrap

import kotlinx.coroutines.flow.Flow

interface SessionBootstrapRepository {
    val state: Flow<SessionBootstrapState>
    val session: Flow<AppSession?>

    suspend fun getCurrentSession(): AppSession?

    suspend fun cacheBootstrap(state: SessionBootstrapState)
    suspend fun cacheSession(session: AppSession?)
    suspend fun setWallpaperConfigured(configured: Boolean)
    suspend fun setPartnerWallpaperStatus(status: PartnerWallpaperStatus?)

    /** Stores the pair's new anniversary if this device is still in [pairSessionId]; returns
     * whether it did. */
    suspend fun setAnniversaryDate(pairSessionId: String, anniversaryDate: String): Boolean

    /** Forgets the pair and its anniversary if `pairSessionId` is still this device's pair. */
    suspend fun endPair(pairSessionId: String): Boolean

    suspend fun hasShownBrushToolGuide(userId: String): Boolean
    suspend fun markBrushToolGuideShown(userId: String)

    suspend fun setStreakLevelUpBanner(streakDays: Int, shownAtMs: Long, expiresAtMs: Long)
    suspend fun dismissStreakLevelUpBanner(dismissedAtMs: Long)
    suspend fun clearStreakLevelUpBanner()

    suspend fun seedDemoSession()

    suspend fun reset()
}
