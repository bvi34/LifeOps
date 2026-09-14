@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The destructive half: exporting, restoring, and clearing what the app is holding.
 *
 * Kept together so that everything behind a "this cannot be undone" confirmation is in one file
 * rather than scattered between the sections it belongs to by subject.
 */

@Composable
internal fun DataActionsSection(
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onExportCsv: () -> Unit,
    onExportWellnessCsv: () -> Unit,
    onExportRingsCsv: () -> Unit,
    onExportRingsSvg: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onBackup, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Backup JSON")
                }
                OutlinedButton(onClick = onRestore, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Restore")
                }
            }
            OutlinedButton(onClick = onExportCsv, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Export Tasks CSV")
            }
            OutlinedButton(onClick = onExportWellnessCsv, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Export Wellness CSV")
            }
            Text(
                "Growth Record",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onExportRingsCsv, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Rings CSV")
                }
                OutlinedButton(onClick = onExportRingsSvg, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Rings SVG")
                }
            }
        }
    }
}

@Composable
internal fun FoodDatabaseSection(
    foodItemCount: Int,
    isImporting: Boolean,
    status: String?,
    foodCsvPicked: Boolean,
    foodNutrientCsvPicked: Boolean,
    foodPortionCsvPicked: Boolean,
    onPickFoodCsv: () -> Unit,
    onPickFoodNutrientCsv: () -> Unit,
    onPickFoodPortionCsv: () -> Unit,
    onImport: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "$foodItemCount foods loaded",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Import the USDA FoodData Central bulk CSV download (food.csv + food_nutrient.csv, " +
                    "food_portion.csv optional) to populate food search for the daily intake log.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            OutlinedButton(onClick = onPickFoodCsv, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (foodCsvPicked) "food.csv selected" else "Pick food.csv")
            }
            OutlinedButton(onClick = onPickFoodNutrientCsv, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (foodNutrientCsvPicked) "food_nutrient.csv selected" else "Pick food_nutrient.csv")
            }
            OutlinedButton(onClick = onPickFoodPortionCsv, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (foodPortionCsvPicked) "food_portion.csv selected" else "Pick food_portion.csv (optional)")
            }
            Button(
                onClick = onImport,
                enabled = foodCsvPicked && foodNutrientCsvPicked && !isImporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isImporting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isImporting) "Importing…" else "Import")
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
internal fun RestoreDialog(
    json: String,
    error: String?,
    onJsonChange: (String) -> Unit,
    onPickFile: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore from Backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Pick backup file (.json)")
                }
                HorizontalDivider()
                Text(
                    "Or paste backup JSON below:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                OutlinedTextField(
                    value = json,
                    onValueChange = onJsonChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 240.dp),
                    placeholder = { Text("Paste backup JSON here…") }
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = json.isNotBlank()) { Text("Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
