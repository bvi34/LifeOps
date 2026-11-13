package com.lifeops.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import com.lifeops.app.MainActivity
import com.lifeops.app.LifeOpsApp
import com.operations.backupkit.AppId
import com.operations.suite.ui.SuiteWidget
import com.operations.suite.ui.SuiteWidgetContent
import com.operations.suite.ui.SuiteWidgetRow

/**
 * Your top pending tasks this week. Drawn with the suite's shared widget frame ([SuiteWidget]), in this app's accent; tapping
 * it opens the app. Refreshed every half hour by the system, and whenever the app is left.
 */
class LifeOpsWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val rows = runCatching { LifeOpsApp.get(context).database.taskDao().getTopPendingTitles(SuiteWidget.MAX_ROWS) }
            .getOrDefault(emptyList())
            .map { SuiteWidgetRow(it) }
        val colors = SuiteWidget.colors(context, AppId.LIFEOPS)
        provideContent {
            SuiteWidgetContent(
                title = "LifeOps – This Week",
                rows = rows,
                empty = "No pending tasks",
                colors = colors,
                onClick = actionStartActivity<MainActivity>()
            )
        }
    }

    companion object {
        /** Redraw every placed copy. Cheap when none is placed; never throws. */
        suspend fun refresh(context: Context) {
            runCatching { LifeOpsWidget().updateAll(context) }
        }
    }
}

class LifeOpsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LifeOpsWidget()
}
