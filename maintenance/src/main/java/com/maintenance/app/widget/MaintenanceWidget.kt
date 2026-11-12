package com.maintenance.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import com.maintenance.app.MainActivity
import com.maintenance.app.MaintenanceApp
import com.maintenance.app.logic.Docket
import com.maintenance.app.logic.DueStatus
import kotlinx.coroutines.flow.first
import com.operations.backupkit.AppId
import com.operations.suite.ui.SuiteWidget
import com.operations.suite.ui.SuiteWidgetContent
import com.operations.suite.ui.SuiteWidgetRow
import com.operations.suitekit.SuiteWhen

/**
 * Upkeep, cover and recalls that are overdue or due soon, across everything you own. Drawn with the suite's shared widget frame ([SuiteWidget]), in this app's accent; tapping
 * it opens the app. Refreshed every half hour by the system, and whenever the app is left.
 */
class MaintenanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = java.time.LocalDate.now()
        val zone = java.time.ZoneId.systemDefault()
        val rows = runCatching {
            Docket.pressing(MaintenanceApp.get(context).repository.board.observeDocket().first())
        }.getOrDefault(emptyList()).map { entry ->
            val due = entry.dueAt?.let {
                SuiteWhen.dayLabel(java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate(), today)
            }.orEmpty()
            SuiteWidgetRow("${entry.assetName} · ${entry.title}", due, urgent = entry.status == DueStatus.OVERDUE)
        }
        val colors = SuiteWidget.colors(context, AppId.MAINTENANCE)
        provideContent {
            SuiteWidgetContent(
                title = "Maintenance – due",
                rows = rows,
                empty = "Nothing pressing",
                colors = colors,
                onClick = actionStartActivity<MainActivity>()
            )
        }
    }

    companion object {
        /** Redraw every placed copy. Cheap when none is placed; never throws. */
        suspend fun refresh(context: Context) {
            runCatching { MaintenanceWidget().updateAll(context) }
        }
    }
}

class MaintenanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MaintenanceWidget()
}
