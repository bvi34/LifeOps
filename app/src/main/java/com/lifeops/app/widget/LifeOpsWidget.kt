package com.lifeops.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.action.clickable
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.lifeops.app.LifeOpsApp
import com.lifeops.app.MainActivity

class LifeOpsWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as LifeOpsApp
        val tasks = try {
            app.database.taskDao().getTopPendingTitles(5)
        } catch (e: Exception) {
            emptyList()
        }

        provideContent {
            WidgetContent(taskTitles = tasks)
        }
    }
}

@Composable
private fun WidgetContent(taskTitles: List<String>) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "LifeOps – This Week",
            style = TextStyle(
                color = ColorProvider(Color(0xFFD0BCFF)),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(GlanceModifier.height(6.dp))
        if (taskTitles.isEmpty()) {
            Text(
                text = "No pending tasks",
                style = TextStyle(
                    color = ColorProvider(Color(0xFF9E9E9E)),
                    fontSize = 12.sp
                )
            )
        } else {
            taskTitles.forEach { title ->
                Text(
                    text = "• $title",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFFE0E0E0)),
                        fontSize = 12.sp
                    ),
                    modifier = GlanceModifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}
