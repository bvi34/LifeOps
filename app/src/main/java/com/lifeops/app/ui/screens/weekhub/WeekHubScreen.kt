@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.weekhub

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeops.app.ui.screens.dailyplan.DailyPlanScreen
import com.lifeops.app.ui.screens.dailyplan.DailyPlanViewModel
import com.lifeops.app.ui.screens.thisweek.ThisWeekScreen
import com.lifeops.app.ui.screens.thisweek.ThisWeekViewModel
import com.lifeops.app.ui.screens.weeklymenu.WeeklyMenuScreen
import com.lifeops.app.ui.screens.weeklymenu.WeeklyMenuViewModel
import com.lifeops.app.util.DateUtil
import java.time.LocalDate

enum class WeekHubTab(val label: String) {
    WEEKLY_MENU("Weekly Menu"),
    DAILY_PLAN("Daily Plan"),
    TASK_MANAGER("Tasks")
}

/** Shell over the three week-scoped sub-views. Owns the one "selected week" value all three
 *  stay in sync with — Weekly Menu and Daily Plan re-key off it directly since their data is
 *  plain date-stamped; Task Manager keeps showing the actual current week regardless (see its
 *  own ViewModel), since browsing past/future weeks of tasks would mean either fabricating Week
 *  rows for dates that were never closed into (breaking the "exactly one open week" invariant
 *  the close-week flow relies on) or a much larger query rewrite — out of scope here. */
@Composable
fun WeekHubScreen(
    weeklyMenuViewModel: WeeklyMenuViewModel,
    dailyPlanViewModel: DailyPlanViewModel,
    taskManagerViewModel: ThisWeekViewModel,
    sharedText: String? = null,
    onImportShared: (String) -> Unit = {}
) {
    var selectedWeekStart by rememberSaveable { mutableStateOf(DateUtil.currentWeekStart().toString()) }
    var selectedTab by rememberSaveable { mutableStateOf(WeekHubTab.WEEKLY_MENU) }

    LaunchedEffect(selectedWeekStart) {
        weeklyMenuViewModel.setWeekStartDate(selectedWeekStart)
        dailyPlanViewModel.setWeekStartDate(selectedWeekStart)
    }
    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) onImportShared(sharedText)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        WeekNavRow(
            weekStartDate = selectedWeekStart,
            onPrevious = { selectedWeekStart = LocalDate.parse(selectedWeekStart).minusWeeks(1).toString() },
            onNext = { selectedWeekStart = LocalDate.parse(selectedWeekStart).plusWeeks(1).toString() },
            onToday = { selectedWeekStart = DateUtil.currentWeekStart().toString() }
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            WeekHubTab.entries.forEachIndexed { index, tab ->
                SegmentedButton(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = WeekHubTab.entries.size)
                ) {
                    Text(tab.label)
                }
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                WeekHubTab.WEEKLY_MENU -> WeeklyMenuScreen(weeklyMenuViewModel)
                WeekHubTab.DAILY_PLAN -> DailyPlanScreen(dailyPlanViewModel)
                WeekHubTab.TASK_MANAGER -> ThisWeekScreen(taskManagerViewModel)
            }
        }
    }
}

@Composable
private fun WeekNavRow(
    weekStartDate: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit
) {
    val weekEndDate = LocalDate.parse(weekStartDate).plusDays(6).toString()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous week")
        }
        Text(
            "${DateUtil.formatDate(weekStartDate)} – ${DateUtil.formatDate(weekEndDate)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickableToday(onToday)
        )
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next week")
        }
    }
}

private fun Modifier.clickableToday(onToday: () -> Unit): Modifier =
    this.clickable(onClick = onToday)
