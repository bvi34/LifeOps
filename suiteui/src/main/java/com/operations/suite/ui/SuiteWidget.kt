package com.operations.suite.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.operations.backupkit.AppId
import com.operations.suitekit.SuiteColors

/** One line of a suite widget: what, and when (or how much) on the right. */
data class SuiteWidgetRow(val text: String, val trailing: String = "", val urgent: Boolean = false)

/** The colours a suite widget is drawn in, taken from the appearance in force. */
data class SuiteWidgetColors(val accent: Color, val background: Color, val text: Color, val muted: Color, val urgent: Color)

/**
 * The one look every app's home-screen widget shares: a title in the app's accent, a few rows, and
 * a tap that opens the app. The colours follow the suite's appearance — its mode, and the app's
 * accent — as of the widget's last update, so a widget doesn't look like a different product from
 * the app it opens.
 */
object SuiteWidget {

    fun colors(context: Context, appId: AppId): SuiteWidgetColors {
        val appearance = SuiteAppearanceStore.get(context).state.value
        val dark = appearance.darkMode
        val background = if (dark) 0xFF1E1E1EL else 0xFFFFFFFFL
        return SuiteWidgetColors(
            accent = Color(SuiteColors.fitForMode(appearance.accentArgb(appId), dark)),
            background = Color(background),
            text = Color(SuiteColors.contrastOn(background)),
            muted = Color(if (dark) 0xFF9E9E9EL else 0xFF616161L),
            urgent = Color(if (dark) 0xFFFC8181L else 0xFFC53030L)
        )
    }

    /** How many rows fit the widget's default size without scrolling, which Glance can't do well. */
    const val MAX_ROWS = 5
}

@Composable
fun SuiteWidgetContent(
    title: String,
    rows: List<SuiteWidgetRow>,
    empty: String,
    colors: SuiteWidgetColors,
    onClick: Action
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(16.dp)
            .background(colors.background)
            .padding(12.dp)
            .clickable(onClick),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = title,
            style = TextStyle(color = ColorProvider(colors.accent), fontSize = 13.sp, fontWeight = FontWeight.Bold),
            maxLines = 1
        )
        Spacer(GlanceModifier.height(6.dp))
        if (rows.isEmpty()) {
            Text(text = empty, style = TextStyle(color = ColorProvider(colors.muted), fontSize = 12.sp))
        } else {
            rows.take(SuiteWidget.MAX_ROWS).forEach { row ->
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = row.text,
                        style = TextStyle(color = ColorProvider(colors.text), fontSize = 12.sp),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight()
                    )
                    if (row.trailing.isNotEmpty()) {
                        Spacer(GlanceModifier.width(8.dp))
                        Text(
                            text = row.trailing,
                            style = TextStyle(
                                color = ColorProvider(if (row.urgent) colors.urgent else colors.muted),
                                fontSize = 12.sp,
                                fontWeight = if (row.urgent) FontWeight.Bold else FontWeight.Normal
                            ),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
