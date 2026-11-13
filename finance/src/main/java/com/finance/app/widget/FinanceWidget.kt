package com.finance.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import com.finance.app.MainActivity
import com.finance.app.FinanceApp
import com.operations.suitekit.SuiteMoney
import com.operations.backupkit.AppId
import com.operations.suite.ui.SuiteWidget
import com.operations.suite.ui.SuiteWidgetContent
import com.operations.suite.ui.SuiteWidgetRow
import com.operations.suitekit.SuiteWhen

/**
 * Unpaid bills due in the next month. Shows payees and amounts, never balances. Drawn with the suite's shared widget frame ([SuiteWidget]), in this app's accent; tapping
 * it opens the app. Refreshed every half hour by the system, and whenever the app is left.
 */
class FinanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = java.time.LocalDate.now()
        val rows = runCatching { FinanceApp.get(context).repository.bills() }
            .getOrDefault(emptyList())
            .filter { it.paidOn == null && !it.dueDate.isAfter(today.plusDays(31)) }
            .sortedBy { it.dueDate }
            .map { bill ->
                SuiteWidgetRow(
                    bill.payee,
                    "${SuiteWhen.dayLabel(bill.dueDate, today)} · ${SuiteMoney.format(bill.amountCents, cents = false)}",
                    urgent = !bill.dueDate.isAfter(today)
                )
            }
        val colors = SuiteWidget.colors(context, AppId.FINANCE)
        provideContent {
            SuiteWidgetContent(
                title = "Finance – bills due",
                rows = rows,
                empty = "No unpaid bills in the next month",
                colors = colors,
                onClick = actionStartActivity<MainActivity>()
            )
        }
    }

    companion object {
        /** Redraw every placed copy. Cheap when none is placed; never throws. */
        suspend fun refresh(context: Context) {
            runCatching { FinanceWidget().updateAll(context) }
        }
    }
}

class FinanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FinanceWidget()
}
