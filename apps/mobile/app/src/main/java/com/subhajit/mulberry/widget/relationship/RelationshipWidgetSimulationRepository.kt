package com.subhajit.mulberry.widget.relationship

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.subhajit.mulberry.core.data.PreferenceStorage
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class RelationshipWidgetSimulationPreset(
    val title: String,
    val description: String
) {
    MISSING_DATE(
        title = "Missing anniversary",
        description = "Shows the setup fallback state."
    ),
    DAYS_ONLY(
        title = "Under one year",
        description = "Shows the days-only primary count."
    ),
    YEARS_AND_DAYS(
        title = "Years and days",
        description = "Shows the standard multi-year layout."
    ),
    ANNIVERSARY_SOON(
        title = "Anniversary soon",
        description = "Shows the next-anniversary countdown copy."
    ),
    ANNIVERSARY_TODAY(
        title = "Anniversary today",
        description = "Shows the happy-anniversary celebratory state."
    ),
    DAYS_MILESTONE(
        title = "Days milestone",
        description = "Shows the completed-days milestone copy."
    ),
    MONTHS_MILESTONE(
        title = "Months milestone",
        description = "Shows the completed-months milestone copy."
    ),
    UPCOMING_BIG_MILESTONE(
        title = "Upcoming 1000 days",
        description = "Shows the milestone countdown copy."
    );

    fun anniversaryDateFor(today: LocalDate): String? = when (this) {
        MISSING_DATE -> null
        DAYS_ONLY -> today.minusDays(123).toString()
        YEARS_AND_DAYS -> today.minusYears(2).minusDays(256).toString()
        ANNIVERSARY_SOON -> today.minusYears(2).plusDays(15).toString()
        ANNIVERSARY_TODAY -> today.minusYears(2).toString()
        DAYS_MILESTONE -> today.minusDays(700).toString()
        MONTHS_MILESTONE -> today.minusMonths(15).toString()
        UPCOMING_BIG_MILESTONE -> today.minusDays(986).toString()
    }
}

interface RelationshipWidgetSimulationRepository {
    val preset: Flow<RelationshipWidgetSimulationPreset?>
    suspend fun setPreset(preset: RelationshipWidgetSimulationPreset?)
}

@Singleton
class DataStoreRelationshipWidgetSimulationRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : RelationshipWidgetSimulationRepository {
    override val preset: Flow<RelationshipWidgetSimulationPreset?> = dataStore.data.map { preferences ->
        preferences[PreferenceStorage.relationshipWidgetSimulationPreset]
            ?.let { raw -> runCatching { RelationshipWidgetSimulationPreset.valueOf(raw) }.getOrNull() }
    }

    override suspend fun setPreset(preset: RelationshipWidgetSimulationPreset?) {
        dataStore.edit { preferences ->
            if (preset == null) {
                preferences.remove(PreferenceStorage.relationshipWidgetSimulationPreset)
            } else {
                preferences[PreferenceStorage.relationshipWidgetSimulationPreset] = preset.name
            }
        }
    }
}
