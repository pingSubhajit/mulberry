package com.subhajit.mulberry.streak

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subhajit.mulberry.app.di.ApplicationScope
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.network.LostStreakSeenRequest
import com.subhajit.mulberry.network.MulberryApiService
import com.subhajit.mulberry.network.StreakResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class StreakUiState(
    val isLoading: Boolean = false,
    val streak: StreakResponse? = null,
    val errorMessage: String? = null,
    /** The streak was just lost and this account hasn't watched its bolt break, here or elsewhere. */
    val breakPending: Boolean = false
)

/** Lost yesterday: the last activity was the day before, and there was a streak to lose. */
fun StreakResponse.isLostRecently(): Boolean {
    val lastDay = lastActivityDay ?: return false
    return currentStreakDays == 0 &&
        previousStreakDays > 0 &&
        LocalDate.parse(lastDay) == LocalDate.parse(today).minusDays(2)
}

@HiltViewModel
class StreakViewModel @Inject constructor(
    private val apiService: MulberryApiService,
    private val streakSimulationRepository: StreakSimulationRepository,
    private val sessionBootstrapRepository: SessionBootstrapRepository,
    private val lostStreakSeenRepository: LostStreakSeenRepository,
    @ApplicationScope private val applicationScope: CoroutineScope
) : ViewModel() {
    private val _uiState = MutableStateFlow(StreakUiState())
    val uiState = _uiState.asStateFlow()
    private var hasLoaded = false
    private var simulated = false

    fun load() {
        if (hasLoaded || _uiState.value.isLoading) return
        hasLoaded = true

        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            val simulation = streakSimulationRepository.simulation.first()
            if (simulation != null) {
                simulated = true
                // A simulated loss breaks every time the screen opens.
                _uiState.value = StreakUiState(
                    isLoading = false,
                    streak = simulation.streak,
                    errorMessage = null,
                    breakPending = simulation.streak.isLostRecently()
                )
                return@launch
            }
            val today = LocalDate.now().toString()
            runCatching { apiService.getStreak(today) }
                .onSuccess { streak ->
                    _uiState.value = StreakUiState(
                        isLoading = false,
                        streak = streak,
                        errorMessage = null,
                        breakPending = breakPending(streak)
                    )
                }
                .onFailure { error ->
                    _uiState.value = StreakUiState(
                        isLoading = false,
                        streak = null,
                        errorMessage = error.message ?: "Could not load streak"
                    )
                }
        }
    }

    /** The bolt broke on screen, or would have but for animations being off: it doesn't again. */
    fun breakShown() {
        val day = _uiState.value.streak?.lastActivityDay ?: return
        if (!_uiState.value.breakPending) return
        _uiState.value = _uiState.value.copy(breakPending = false)
        if (simulated) return
        applicationScope.launch {
            val userId = sessionBootstrapRepository.getCurrentSession()?.userId ?: return@launch
            lostStreakSeenRepository.markSeen(userId, day)
            runCatching { apiService.markLostStreakSeen(LostStreakSeenRequest(day)) }
        }
    }

    private suspend fun breakPending(streak: StreakResponse): Boolean {
        val lastDay = streak.lastActivityDay ?: return false
        val userId = sessionBootstrapRepository.getCurrentSession()?.userId
        val seenDay = userId?.let { lostStreakSeenRepository.seenDay(it) }
        // Shown here before the server heard: it hears now.
        if (seenDay == lastDay && streak.lostStreakSeen == false) {
            applicationScope.launch { runCatching { apiService.markLostStreakSeen(LostStreakSeenRequest(lastDay)) } }
        }
        return streak.breakPending(seenDay)
    }
}

/**
 * Whether the bolt should break: just lost, and this account hasn't watched it, on another device
 * (the server's word) or on this one ([seenDayHere]).
 */
fun StreakResponse.breakPending(seenDayHere: String?): Boolean =
    isLostRecently() && lostStreakSeen != true && seenDayHere != lastActivityDay
