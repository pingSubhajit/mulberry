package com.subhajit.mulberry.streak

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.subhajit.mulberry.app.di.ApplicationScope
import com.subhajit.mulberry.bootstrap.BootstrapRepository
import com.subhajit.mulberry.canvas.CanvasRuntime
import com.subhajit.mulberry.core.data.PreferenceStorage
import com.subhajit.mulberry.data.bootstrap.AuthStatus
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapState
import com.subhajit.mulberry.network.MulberryApiService
import com.subhajit.mulberry.network.StreakResponse
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the account's first drawing should do, given the pair's streak. */
enum class FirstDrawingDecision {
    /** The streak cannot grow with this drawing: celebrate now. */
    CELEBRATE,

    /** Today has no drawing yet, so this one grows the streak and its level-up celebrates. */
    LEAVE_TO_STREAK,

    /** The streak for today is not known yet. */
    UNKNOWN
}

/** The streak grows with the pair's first drawing of the day, by either partner. */
fun decideFirstDrawingCelebration(
    paired: Boolean,
    streak: StreakResponse?,
    today: String
): FirstDrawingDecision {
    if (!paired) return FirstDrawingDecision.CELEBRATE
    if (streak == null || streak.today != today) return FirstDrawingDecision.UNKNOWN
    return if (streak.hasActivityToday) {
        FirstDrawingDecision.CELEBRATE
    } else {
        FirstDrawingDecision.LEAVE_TO_STREAK
    }
}

/**
 * Whether the account's next drawing is its first. A server too old to say counts as drawn, so
 * nobody is celebrated by mistake; a drawing seen here counts until the server catches up.
 */
fun awaitsFirstDrawing(
    userId: String?,
    serverHasDrawn: Boolean?,
    drawnHereUserIds: Set<String>
): Boolean {
    if (userId == null) return false
    return serverHasDrawn == false && userId !in drawnHereUserIds
}

/**
 * The account's first drawing, on any device, celebrated once with the streak's confetti. The two
 * celebrations never add up: when the first drawing also grows the pair's streak, the streak's
 * level-up celebrates it and this stays quiet. Mirrors iOS's FirstDrawingModel.
 */
@Singleton
class FirstDrawingCelebrator @Inject constructor(
    private val sessionBootstrapRepository: SessionBootstrapRepository,
    private val bootstrapRepository: BootstrapRepository,
    private val canvasRuntime: CanvasRuntime,
    private val apiService: MulberryApiService,
    private val dataStore: DataStore<Preferences>,
    @ApplicationScope private val applicationScope: CoroutineScope
) {
    private val _confettiToken = MutableStateFlow<Long?>(null)

    /** Set while the first-drawing confetti plays. */
    val confettiToken: StateFlow<Long?> = _confettiToken.asStateFlow()

    private val mutex = Mutex()
    private var session: SessionBootstrapState = SessionBootstrapState()
    private var boundUserId: String? = null
    /** The pair's streak as it stood before the first drawing, to tell whether that drawing grows it. */
    private var knownStreak: StreakResponse? = null
    private var streakFetch: Job? = null
    private var celebrating: Job? = null

    init {
        applicationScope.launch {
            sessionBootstrapRepository.state.collect { state -> mutex.withLock { bind(state) } }
        }
        applicationScope.launch {
            canvasRuntime.localDrawings.collect { mutex.withLock { onLocalDrawing() } }
        }
    }

    private suspend fun bind(state: SessionBootstrapState) {
        val userId = state.userId.takeIf { state.authStatus == AuthStatus.SIGNED_IN }
        if (userId != boundUserId) {
            celebrating?.cancel()
            celebrating = null
            streakFetch?.cancel()
            streakFetch = null
            knownStreak = null
            _confettiToken.value = null
        }
        boundUserId = userId
        session = state
        if (state.isPaired() && awaitsFirstDrawing(userId, state.hasDrawn, drawnHereUserIds())) {
            fetchStreakIfStale()
        }
    }

    /** Looks at the streak ahead of the first drawing, so the drawing's own save can't hide it. */
    private fun fetchStreakIfStale() {
        val today = localDay()
        if (knownStreak?.today == today || streakFetch?.isActive == true) return
        streakFetch = applicationScope.launch {
            val streak = runCatching { apiService.getStreak(today) }.getOrNull() ?: return@launch
            mutex.withLock { knownStreak = streak }
        }
    }

    private suspend fun onLocalDrawing() {
        val userId = boundUserId ?: return
        if (!awaitsFirstDrawing(userId, session.hasDrawn, drawnHereUserIds())) return
        markDrawnHere(userId)
        val today = localDay()
        when (decideFirstDrawingCelebration(session.isPaired(), knownStreak, today)) {
            FirstDrawingDecision.CELEBRATE -> celebrate()
            FirstDrawingDecision.LEAVE_TO_STREAK,
            FirstDrawingDecision.UNKNOWN -> awaitStreakLevelUp(today)
        }
    }

    /**
     * The streak's level-up is due: look for it as the drawing reaches the server, and celebrate
     * here only if it doesn't come.
     */
    private fun awaitStreakLevelUp(today: String) {
        val bannerBefore = session.streakLevelUpBannerShownAtMs
        val streakDaysBefore = session.currentStreakDays
        celebrating?.cancel()
        celebrating = applicationScope.launch {
            for (waitMs in STREAK_CHECK_DELAYS_MS) {
                delay(waitMs)
                bootstrapRepository.refreshBootstrap()
                val state = sessionBootstrapRepository.state.first()
                if (
                    state.streakLevelUpBannerShownAtMs != bannerBefore ||
                    state.currentStreakDays > streakDaysBefore
                ) {
                    return@launch
                }
                val streak = runCatching { apiService.getStreak(today) }.getOrNull()
                if (streak != null && streak.today == today && streak.hasActivityToday) {
                    if (streak.currentStreakDays > streakDaysBefore) return@launch
                    break
                }
            }
            // The streak didn't celebrate this drawing, or couldn't be reached.
            mutex.withLock { celebrate() }
        }
    }

    private fun celebrate() {
        val token = System.nanoTime()
        _confettiToken.value = token
        celebrating = applicationScope.launch {
            delay(CONFETTI_DURATION_MS)
            _confettiToken.compareAndSet(token, null)
        }
    }

    private suspend fun drawnHereUserIds(): Set<String> =
        dataStore.data.first()[PreferenceStorage.firstDrawingUserIds].orEmpty()

    private suspend fun markDrawnHere(userId: String) {
        dataStore.edit { preferences ->
            val current = preferences[PreferenceStorage.firstDrawingUserIds].orEmpty()
            preferences[PreferenceStorage.firstDrawingUserIds] = current + userId
        }
    }

    private fun SessionBootstrapState.isPaired(): Boolean =
        pairingStatus == PairingStatus.PAIRED && pairSessionId != null

    private fun localDay(): String = LocalDate.now().toString()

    private companion object {
        /** How long to wait before each look at the streak, while the drawing reaches the server. */
        val STREAK_CHECK_DELAYS_MS = listOf(1_500L, 3_000L, 5_000L, 10_000L)
        const val CONFETTI_DURATION_MS = 4_000L
    }
}
