package com.logistics.app.ui.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.logistics.app.data.model.MealLog
import com.logistics.app.data.repository.PantryRepository
import com.logistics.app.ui.pantry.formatQty
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class HistoryViewModel(private val repo: PantryRepository) : ViewModel() {

    val meals: StateFlow<List<MealLog>> =
        repo.observeMealHistory().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var status by mutableStateOf<String?>(null)
        private set

    /** Re-log a past meal, deducting its lines from the pantry again (clamped to what's on hand). */
    fun remake(meal: MealLog) = viewModelScope.launch {
        val deducted = repo.remakeMeal(meal)
        status = if (deducted > 0) {
            "Re-logged \"${meal.mealName}\" — deducted $deducted item(s)."
        } else {
            "Nothing left on those shelves to deduct for \"${meal.mealName}\"."
        }
    }

    fun clearStatus() { status = null }

    class Factory(private val repo: PantryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HistoryViewModel(repo) as T
    }
}

@Composable
fun HistoryScreen(vm: HistoryViewModel) {
    val meals by vm.meals.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.status) {
        vm.status?.let { snackbar.showSnackbar(it); vm.clearStatus() }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        if (meals.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No meals logged yet.\n\nLog a meal from the pantry and it lands here — ready to make again in one tap.",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "hdr") {
                    Text(
                        "Past meals — tap Make again to re-deduct the same items.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                items(meals, key = { it.id }) { meal ->
                    MealHistoryCard(meal = meal, onRemake = { vm.remake(meal) })
                }
            }
        }
    }
}

@Composable
private fun MealHistoryCard(meal: MealLog, onRemake: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(meal.mealName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${friendlyTime(meal.loggedAt)} · ${meal.itemCount} item(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(onClick = onRemake) {
                    Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Make again")
                }
            }
            Spacer(Modifier.height(6.dp))
            meal.lines.forEach { line ->
                Text(
                    "• ${formatQty(line.amount)} ${line.unit} — ${line.name}" +
                        if (!line.available) " (no longer stocked)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (line.available) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private val timeFmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

/** Render an ISO-8601 instant in the device's local zone; fall back to the raw string if unparseable
 *  (e.g. a legacy row with an odd stamp). */
private fun friendlyTime(iso: String): String =
    runCatching {
        Instant.parse(iso).atZone(ZoneId.systemDefault()).format(timeFmt)
    }.getOrDefault(iso)
