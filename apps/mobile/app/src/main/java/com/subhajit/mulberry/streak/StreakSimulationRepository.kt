package com.subhajit.mulberry.streak

import com.subhajit.mulberry.data.bootstrap.SessionBootstrapState
import com.subhajit.mulberry.network.StreakResponse
import com.subhajit.mulberry.network.StreakWeekDayResponse
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StreakSimulationPreset(
    val title: String,
    val description: String
) {
    NO_STREAK(
        title = "No streak",
        description = "No activity this week."
    ),
    ACTIVE_3_PENDING(
        title = "3 day streak, today pending",
        description = "Three consecutive days done, today still open."
    ),
    ACTIVE_4_DONE(
        title = "4 day streak, today done",
        description = "Today is already counted in the streak."
    ),
    LOST_3_DAY_STREAK(
        title = "Lost 3 day streak",
        description = "Missed yesterday, so the streak is now lost."
    ),
    ACTIVE_7_DONE(
        title = "7 day streak, today done",
        description = "A full week of consecutive activity."
    )
}

data class StreakSimulation(
    val preset: StreakSimulationPreset,
    val streak: StreakResponse
)

interface StreakSimulationRepository {
    val simulation: StateFlow<StreakSimulation?>

    suspend fun setSimulation(preset: StreakSimulationPreset?)
}

@Singleton
class InMemoryStreakSimulationRepository @Inject constructor() : StreakSimulationRepository {
    private val _simulation = MutableStateFlow<StreakSimulation?>(null)
    override val simulation: StateFlow<StreakSimulation?> = _simulation.asStateFlow()

    override suspend fun setSimulation(preset: StreakSimulationPreset?) {
        _simulation.value = preset?.toSimulation(LocalDate.now())
    }
}

fun SessionBootstrapState.withDisplayStreakSimulation(
    simulation: StreakSimulation?
): SessionBootstrapState {
    return if (simulation == null) {
        this
    } else {
        copy(currentStreakDays = simulation.streak.currentStreakDays)
    }
}

private fun StreakSimulationPreset.toSimulation(today: LocalDate): StreakSimulation {
    val streak = when (this) {
        StreakSimulationPreset.NO_STREAK -> StreakResponse(
            today = today.toString(),
            currentStreakDays = 0,
            previousStreakDays = 0,
            hasActivityToday = false,
            lastActivityDay = null,
            week = buildWeek(today, emptySet())
        )
        StreakSimulationPreset.ACTIVE_3_PENDING -> {
            val activeDays = setOf(today.minusDays(3), today.minusDays(2), today.minusDays(1))
            StreakResponse(
                today = today.toString(),
                currentStreakDays = 3,
                previousStreakDays = 0,
                hasActivityToday = false,
                lastActivityDay = today.minusDays(1).toString(),
                week = buildWeek(today, activeDays)
            )
        }
        StreakSimulationPreset.ACTIVE_4_DONE -> {
            val activeDays = (0L..3L).map { today.minusDays(it) }.toSet()
            StreakResponse(
                today = today.toString(),
                currentStreakDays = 4,
                previousStreakDays = 0,
                hasActivityToday = true,
                lastActivityDay = today.toString(),
                week = buildWeek(today, activeDays)
            )
        }
        StreakSimulationPreset.LOST_3_DAY_STREAK -> {
            val activeDays = setOf(today.minusDays(4), today.minusDays(3), today.minusDays(2))
            StreakResponse(
                today = today.toString(),
                currentStreakDays = 0,
                previousStreakDays = 3,
                hasActivityToday = false,
                lastActivityDay = today.minusDays(2).toString(),
                week = buildWeek(today, activeDays)
            )
        }
        StreakSimulationPreset.ACTIVE_7_DONE -> {
            val activeDays = (0L..6L).map { today.minusDays(it) }.toSet()
            StreakResponse(
                today = today.toString(),
                currentStreakDays = 7,
                previousStreakDays = 0,
                hasActivityToday = true,
                lastActivityDay = today.toString(),
                week = buildWeek(today, activeDays)
            )
        }
    }
    return StreakSimulation(preset = this, streak = streak)
}

private fun buildWeek(
    today: LocalDate,
    activeDays: Set<LocalDate>
): List<StreakWeekDayResponse> {
    val startOfWeek = today.minusDays((today.dayOfWeek.value % 7).toLong())
    return (0L..6L).map { offset ->
        val day = startOfWeek.plusDays(offset)
        StreakWeekDayResponse(
            day = day.toString(),
            hasActivity = day in activeDays
        )
    }
}
