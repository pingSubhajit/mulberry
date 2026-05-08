package com.subhajit.mulberry.widget.relationship

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetProvider
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.subhajit.mulberry.MainActivity
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.data.PreferenceStorage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class RelationshipTrackerWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        val action = intent.action ?: return
        if (!WIDGET_ACTIONS.contains(action)) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val widgetIds = appWidgetManager.getAppWidgetIds(
                    android.content.ComponentName(context, RelationshipTrackerWidgetProvider::class.java)
                )
                updateWidgets(context, appWidgetManager, widgetIds)
                scheduleDailyTick(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                updateWidgets(context, appWidgetManager, appWidgetIds)
                scheduleDailyTick(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                updateWidgets(context, appWidgetManager, intArrayOf(appWidgetId))
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onEnabled(context: Context) {
        scheduleDailyTick(context)
    }

    override fun onDisabled(context: Context) {
        cancelDailyTick(context)
    }

    private suspend fun updateWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        if (appWidgetIds.isEmpty()) return

        val dataStore = entryPoint(context).preferenceDataStore()
        val preferences = runCatching { dataStore.data.first() }.getOrElse { emptyPreferences() }

        val anniversaryString = preferences[PreferenceStorage.anniversaryDate]
        val model = RelationshipWidgetModelFactory.create(
            context = context,
            anniversaryStorageDate = anniversaryString,
            clock = Clock.systemDefaultZone()
        )

        appWidgetIds.forEach { appWidgetId ->
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val layoutRes = when (RelationshipWidgetSize.fromOptions(options)) {
                RelationshipWidgetSize.SMALL -> R.layout.widget_relationship_small
                RelationshipWidgetSize.MEDIUM -> R.layout.widget_relationship_medium
                RelationshipWidgetSize.LARGE -> R.layout.widget_relationship_large
            }

            val views = RemoteViews(context.packageName, layoutRes)

            views.setTextViewText(R.id.widget_relationship_primary, model.primaryText)
            if (layoutRes != R.layout.widget_relationship_small) {
                if (model.secondaryText.isNullOrBlank()) {
                    views.setViewVisibility(R.id.widget_relationship_secondary, View.GONE)
                } else {
                    views.setViewVisibility(R.id.widget_relationship_secondary, View.VISIBLE)
                    views.setTextViewText(R.id.widget_relationship_secondary, model.secondaryText)
                }
            }
            views.setTextViewText(R.id.widget_relationship_caption, model.captionText)

            if (layoutRes != R.layout.widget_relationship_small) {
                views.setImageViewResource(
                    R.id.widget_relationship_squee,
                    if (model.isCelebratory) R.drawable.widget_squee_celebratory else R.drawable.widget_squee_default
                )
            }

            views.setOnClickPendingIntent(
                android.R.id.background,
                PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    private fun scheduleDailyTick(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, RelationshipTrackerWidgetProvider::class.java)
            .setAction(ACTION_ALARM_TICK)

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val nextMidnight = LocalDate.now(zone)
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        val firstTick = maxOf(nextMidnight + ALARM_SKEW_MS, now + 10_000L)

        alarmManager.setInexactRepeating(
            AlarmManager.RTC,
            firstTick,
            AlarmManager.INTERVAL_DAY,
            pendingIntent
        )
    }

    private fun cancelDailyTick(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, RelationshipTrackerWidgetProvider::class.java).setAction(ACTION_ALARM_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    private fun entryPoint(context: Context): RelationshipWidgetEntryPoint =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            RelationshipWidgetEntryPoint::class.java
        )

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RelationshipWidgetEntryPoint {
        fun preferenceDataStore(): DataStore<Preferences>
    }

    private enum class RelationshipWidgetSize {
        SMALL,
        MEDIUM,
        LARGE;

        companion object {
            fun fromOptions(options: Bundle): RelationshipWidgetSize {
                val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
                return when {
                    minHeight >= 180 -> LARGE
                    minWidth in 1..199 -> SMALL
                    else -> MEDIUM
                }
            }
        }
    }

    private companion object {
        private const val ACTION_ALARM_TICK =
            "com.subhajit.mulberry.widget.relationship.action.ALARM_TICK"
        private const val ALARM_SKEW_MS = 5 * 60 * 1000L

        private val WIDGET_ACTIONS = setOf(
            ACTION_ALARM_TICK,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED
        )
    }
}

internal data class RelationshipWidgetModel(
    val primaryText: String,
    val secondaryText: String?,
    val captionText: String,
    val isCelebratory: Boolean
)

internal object RelationshipWidgetModelFactory {
    fun create(
        context: Context,
        anniversaryStorageDate: String?,
        clock: Clock
    ): RelationshipWidgetModel {
        val today = LocalDate.now(clock)

        val anniversary = anniversaryStorageDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (anniversary == null) {
            return RelationshipWidgetModel(
                primaryText = context.getString(R.string.widget_relationship_name),
                secondaryText = null,
                captionText = context.getString(R.string.widget_relationship_missing_date),
                isCelebratory = false
            )
        }

        val daysTogether = ChronoUnit.DAYS.between(anniversary, today).toInt().coerceAtLeast(0)
        val yearsCompleted = runCatching { anniversary.until(today).years }.getOrDefault(0).coerceAtLeast(0)
        val lastAnniversary = anniversary.plusYears(yearsCompleted.toLong())
        val daysIntoYear = ChronoUnit.DAYS.between(lastAnniversary, today).toInt().coerceAtLeast(0)

        val primary = if (yearsCompleted == 0) {
            context.resources.getQuantityString(R.plurals.widget_relationship_days, daysTogether, daysTogether)
        } else {
            context.resources.getQuantityString(R.plurals.widget_relationship_years, yearsCompleted, yearsCompleted)
        }

        val secondary = if (yearsCompleted == 0) {
            null
        } else {
            context.getString(
                R.string.widget_relationship_and_days,
                context.resources.getQuantityString(R.plurals.widget_relationship_days, daysIntoYear, daysIntoYear)
            )
        }

        val caption = buildCaption(context, anniversary, today, daysTogether)
        val isCelebratory = caption.startsWith("Wow") || caption.startsWith("Happy")

        return RelationshipWidgetModel(
            primaryText = primary,
            secondaryText = secondary,
            captionText = caption,
            isCelebratory = isCelebratory
        )
    }

    private fun buildCaption(
        context: Context,
        anniversary: LocalDate,
        today: LocalDate,
        daysTogether: Int
    ): String {
        val daysToNextAnniversary = daysUntilNextAnniversary(anniversary, today)
        if (daysToNextAnniversary == 0 && daysTogether > 0) {
            val years = runCatching { anniversary.until(today).years }.getOrDefault(0).coerceAtLeast(0)
            return if (years > 0) {
                context.getString(
                    R.string.widget_relationship_happy_anniversary_years,
                    context.resources.getQuantityString(R.plurals.widget_relationship_years, years, years)
                )
            } else {
                context.getString(R.string.widget_relationship_happy_anniversary)
            }
        }
        if (daysToNextAnniversary in 1..30) {
            return context.getString(
                R.string.widget_relationship_next_anniversary_in,
                context.resources.getQuantityString(
                    R.plurals.widget_relationship_days,
                    daysToNextAnniversary,
                    daysToNextAnniversary
                )
            )
        }

        val daysMilestone = milestoneHit(daysTogether, step = 100, startAt = 100)
        if (daysMilestone != null) {
            return context.getString(
                R.string.widget_relationship_wow_days_now,
                context.resources.getQuantityString(R.plurals.widget_relationship_days, daysMilestone, daysMilestone)
            )
        }

        val monthsTogether = runCatching { anniversary.until(today).toTotalMonths().toInt() }.getOrDefault(0)
        val isMonthAnniversary = monthsTogether > 0 && runCatching {
            anniversary.plusMonths(monthsTogether.toLong()) == today
        }.getOrDefault(false)
        if (isMonthAnniversary && monthsTogether <= 24) {
            return context.getString(
                R.string.widget_relationship_wow_months_now,
                context.resources.getQuantityString(
                    R.plurals.widget_relationship_months,
                    monthsTogether,
                    monthsTogether
                )
            )
        }

        val upcoming = nextMilestone(daysTogether, listOf(500, 700, 1000, 1500, 2000))
        if (upcoming != null) {
            val remaining = upcoming - daysTogether
            if (remaining in 1..30) {
                return context.getString(
                    R.string.widget_relationship_wow_days_to,
                    context.resources.getQuantityString(
                        R.plurals.widget_relationship_days,
                        remaining,
                        remaining
                    ),
                    upcoming
                )
            }
        }

        val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
        return context.getString(R.string.widget_relationship_together_since, anniversary.format(formatter))
    }

    private fun daysUntilNextAnniversary(anniversary: LocalDate, today: LocalDate): Int {
        val monthDay = MonthDay.from(anniversary)
        fun monthDayAtYearSafe(year: Int): LocalDate =
            runCatching { monthDay.atYear(year) }.getOrElse {
                // e.g., Feb 29 in a non-leap year: celebrate on Feb 28.
                LocalDate.of(year, 2, 28)
            }

        val candidate = monthDayAtYearSafe(today.year)
        val next = when {
            !candidate.isBefore(today) -> candidate
            else -> monthDayAtYearSafe(today.year + 1)
        }
        return ChronoUnit.DAYS.between(today, next).toInt().coerceAtLeast(0)
    }

    private fun milestoneHit(value: Int, step: Int, startAt: Int): Int? {
        if (value < startAt) return null
        if (step <= 0) return null
        return if (value % step == 0) value else null
    }

    private fun nextMilestone(current: Int, milestones: List<Int>): Int? =
        milestones.firstOrNull { it > current }
}
