package com.subhajit.mulberry.widget.relationship

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Singleton
class RelationshipWidgetAnniversaryObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionBootstrapRepository: SessionBootstrapRepository
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            sessionBootstrapRepository.state
                .map { state -> state.anniversaryDate }
                .distinctUntilChanged()
                .collect {
                    RelationshipWidgetUpdateRequester.requestUpdate(context)
                }
        }
    }
}

internal object RelationshipWidgetUpdateRequester {
    fun requestUpdate(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        requestUpdate(context, appWidgetManager, RelationshipTrackerWidgetProvider::class.java)
        requestUpdate(context, appWidgetManager, RelationshipTrackerSquareWidgetProvider::class.java)
    }

    private fun requestUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        providerClass: Class<*>
    ) {
        val componentName = ComponentName(context, providerClass)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
        if (widgetIds.isEmpty()) return

        val intent = Intent(context, providerClass).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
        }
        context.sendBroadcast(intent)
    }
}
