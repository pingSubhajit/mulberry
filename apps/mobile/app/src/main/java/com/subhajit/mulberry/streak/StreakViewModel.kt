package com.subhajit.mulberry.streak

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subhajit.mulberry.network.MulberryApiService
import com.subhajit.mulberry.network.StreakResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class StreakUiState(
    val isLoading: Boolean = false,
    val streak: StreakResponse? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class StreakViewModel @Inject constructor(
    private val apiService: MulberryApiService,
    private val streakSimulationRepository: StreakSimulationRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(StreakUiState())
    val uiState = _uiState.asStateFlow()
    private var hasLoaded = false

    fun load() {
        if (hasLoaded || _uiState.value.isLoading) return
        hasLoaded = true

        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            val simulation = streakSimulationRepository.simulation.first()
            if (simulation != null) {
                _uiState.value = StreakUiState(
                    isLoading = false,
                    streak = simulation.streak,
                    errorMessage = null
                )
                return@launch
            }
            val today = LocalDate.now().toString()
            runCatching { apiService.getStreak(today) }
                .onSuccess { streak ->
                    _uiState.value = StreakUiState(
                        isLoading = false,
                        streak = streak,
                        errorMessage = null
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
}
