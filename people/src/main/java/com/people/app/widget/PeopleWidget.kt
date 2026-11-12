package com.people.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import com.people.app.MainActivity
import com.people.app.PeopleApp
import kotlinx.coroutines.flow.first
import com.operations.backupkit.AppId
import com.operations.suite.ui.SuiteWidget
import com.operations.suite.ui.SuiteWidgetContent
import com.operations.suite.ui.SuiteWidgetRow
import com.operations.suitekit.SuiteWhen

/**
 * Birthdays and the other dates coming up for the people in your household. Drawn with the suite's shared widget frame ([SuiteWidget]), in this app's accent; tapping
 * it opens the app. Refreshed every half hour by the system, and whenever the app is left.
 */
class PeopleWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = java.time.LocalDate.now()
        val rows = runCatching {
            PeopleApp.get(context).repository.observeUpcoming(withinDays = 60).first()
        }.getOrDefault(emptyList()).map { (_, date) ->
            val label = date.turning?.let { "${date.label} (${it})" } ?: date.label
            SuiteWidgetRow(label, SuiteWhen.dayLabel(date.next, today), urgent = date.isToday)
        }
        val colors = SuiteWidget.colors(context, AppId.PEOPLE)
        provideContent {
            SuiteWidgetContent(
                title = "People – coming up",
                rows = rows,
                empty = "Nothing in the next two months",
                colors = colors,
                onClick = actionStartActivity<MainActivity>()
            )
        }
    }

    companion object {
        /** Redraw every placed copy. Cheap when none is placed; never throws. */
        suspend fun refresh(context: Context) {
            runCatching { PeopleWidget().updateAll(context) }
        }
    }
}

class PeopleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PeopleWidget()
}
