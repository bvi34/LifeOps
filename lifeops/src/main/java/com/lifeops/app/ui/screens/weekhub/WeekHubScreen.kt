@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.weekhub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.ForecastPeriod
import com.lifeops.app.ui.components.LocalGlobalSearch
import com.lifeops.app.util.WeatherGlyph
import com.lifeops.app.ui.screens.collection.BookViewModel
import com.lifeops.app.ui.screens.collection.CollectionScreen
import com.lifeops.app.ui.screens.collection.FutureOperationViewModel
import com.lifeops.app.ui.screens.collection.RecipeViewModel
import com.lifeops.app.ui.screens.dailyplan.DailyPlanScreen
import com.lifeops.app.ui.screens.dailyplan.DailyPlanViewModel
import com.lifeops.app.ui.screens.thisweek.ThisWeekScreen
import com.lifeops.app.ui.screens.thisweek.ThisWeekViewModel
import com.lifeops.app.util.DateUtil
import java.time.LocalDate

enum class WeekHubTab(val label: String) {
    TASK_MANAGER("Tasks"),
    DAILY_PLAN("Daily Plan"),
    COLLECTION("Collection")
}

/** Shell over the three sub-views. Tasks is the default landing tab. Daily Plan stays
 *  week-scoped (re-keys off [selectedWeekStart] since its data is plain date-stamped); Collection
 *  is a standalone reference library (recipes, books, future operations) with no week concept, so
 *  it never re-keys off the selected week. Task Manager keeps showing the actual current week
 *  regardless (see its own ViewModel), since browsing past/future weeks of tasks would mean
 *  either fabricating Week rows for dates that were never closed into (breaking the "exactly one
 *  open week" invariant the close-week flow relies on) or a much larger query rewrite — out of
 *  scope here. */
@Composable
fun WeekHubScreen(
    dailyPlanViewModel: DailyPlanViewModel,
    taskManagerViewModel: ThisWeekViewModel,
    recipeViewModel: RecipeViewModel,
    bookViewModel: BookViewModel,
    futureOperationViewModel: FutureOperationViewModel,
    onOpenRecipe: (String) -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenFutureOperation: (String) -> Unit,
    onOpenOperation: (String) -> Unit = {},
    onOpenPerson: (String) -> Unit = {},
    onOpenCounter: (String) -> Unit = {},
    onOpenTask: (String) -> Unit = {},
    sharedText: String? = null,
    onImportShared: (String) -> Unit = {}
) {
    var selectedWeekStart by rememberSaveable { mutableStateOf(DateUtil.currentWeekStart().toString()) }
    var selectedTab by rememberSaveable { mutableStateOf(WeekHubTab.TASK_MANAGER) }

    LaunchedEffect(selectedWeekStart) {
        dailyPlanViewModel.setWeekStartDate(selectedWeekStart)
    }
    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) onImportShared(sharedText)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selectedTab != WeekHubTab.COLLECTION) {
            WeekNavRow(
                weekStartDate = selectedWeekStart,
                onPrevious = { selectedWeekStart = LocalDate.parse(selectedWeekStart).minusWeeks(1).toString() },
                onNext = { selectedWeekStart = LocalDate.parse(selectedWeekStart).plusWeeks(1).toString() },
                onToday = { selectedWeekStart = DateUtil.currentWeekStart().toString() }
            )
        }
        // Tab selection + search: the three sub-views on the left, the app-wide search on the
        // right. Search is the only header action kept here — the old filter/sort/planning controls
        // were removed in favour of the always-on event banner and a clean, fixed task list.
        val onGlobalSearch = LocalGlobalSearch.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WeekHubTab.entries.forEach { tab ->
                val selected = selectedTab == tab
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { selectedTab = tab }
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(
                        modifier = Modifier
                            .height(2.dp)
                            .width(if (selected) 18.dp else 0.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp))
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onGlobalSearch) {
                Icon(Icons.Default.Search, contentDescription = "Search")
            }
        }
        // Weekly forecast fills the space between the tabs and the task list on the Tasks tab.
        if (selectedTab == WeekHubTab.TASK_MANAGER) {
            val taskState by taskManagerViewModel.uiState.collectAsStateWithLifecycle()
            if (taskState.weeklyForecast.isNotEmpty()) {
                WeeklyForecastStrip(
                    periods = taskState.weeklyForecast,
                    locationName = taskState.weatherLocationName
                )
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                WeekHubTab.TASK_MANAGER -> ThisWeekScreen(
                    taskManagerViewModel,
                    onOpenOperation = onOpenOperation,
                    onOpenPerson = onOpenPerson,
                    onOpenCounter = onOpenCounter,
                    onOpenTask = onOpenTask
                )
                WeekHubTab.DAILY_PLAN -> DailyPlanScreen(dailyPlanViewModel)
                WeekHubTab.COLLECTION -> CollectionScreen(
                    recipeViewModel = recipeViewModel,
                    bookViewModel = bookViewModel,
                    futureOperationViewModel = futureOperationViewModel,
                    onOpenRecipe = onOpenRecipe,
                    onOpenBook = onOpenBook,
                    onOpenFutureOperation = onOpenFutureOperation
                )
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

/** Compact, horizontally-scrollable weekly forecast — one cell per daytime period, drawn from the
 *  tracked location's cached report. Sits in the gap between the tabs and the task list. */
@Composable
private fun WeeklyForecastStrip(periods: List<ForecastPeriod>, locationName: String?) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Weekly forecast",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                locationName?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                periods.forEachIndexed { index, period ->
                    ForecastDayCell(period = period, isFirst = index == 0)
                }
            }
        }
    }
}

@Composable
private fun ForecastDayCell(period: ForecastPeriod, isFirst: Boolean) {
    val rain = period.precipitationProbabilityPct ?: 0
    Column(
        modifier = Modifier.width(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            if (isFirst) "Today" else forecastDayLabel(period),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isFirst) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isFirst) 0.9f else 0.6f),
            maxLines = 1,
            textAlign = TextAlign.Center
        )
        Text(WeatherGlyph.forShortForecast(period.shortForecast), style = MaterialTheme.typography.titleMedium)
        Text(
            "${period.temperatureF}°",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            if (rain > 0) "$rain%" else " ",
            style = MaterialTheme.typography.labelSmall,
            color = if (rain >= 40) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            maxLines = 1
        )
    }
}

/** Short day-of-week label ("Sat") parsed from the period's ISO start time; falls back to the
 *  forecast period name if the timestamp can't be parsed. */
private fun forecastDayLabel(period: ForecastPeriod): String =
    runCatching {
        java.time.OffsetDateTime.parse(period.startTime)
            .dayOfWeek
            .getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
    }.getOrNull() ?: period.name.take(3)
