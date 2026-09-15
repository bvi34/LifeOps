@file:OptIn(ExperimentalMaterial3Api::class)

package com.operations.sandbox.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.operations.backupkit.AppId
import com.operations.sandbox.shortcuts.SuiteShortcuts
import com.operations.suite.ui.SuiteAppearanceStore
import com.operations.suite.ui.accentArgb
import com.operations.suite.ui.suiteWallpaper
import com.operations.suitekit.SuiteAppInfo
import com.operations.suitekit.SuiteAppearance
import com.operations.suitekit.SuiteApps
import com.operations.suitekit.SuiteColors
import com.operations.suitekit.SuiteIconPaint
import com.operations.suitekit.SuitePalette
import com.operations.suitekit.SuitePreset
import com.operations.suitekit.SuiteScheme
import com.operations.suitekit.SuiteThemes
import com.operations.suitekit.SuiteWallpaper
import com.operations.suitekit.SuiteWallpapers
import com.operations.suitekit.WallpaperAngle
import com.operations.suitekit.WallpaperDesign
import com.operations.suitekit.WallpaperStyle
import kotlin.math.roundToInt
import com.operations.suite.ui.pickers.SuiteColorField

/** Which half of the gear is showing. */
enum class SettingsTab(val label: String) {
    APPEARANCE("Appearance"),
    BACKUPS("Backups"),
    UPDATES("Updates")
}

/**
 * The sandbox's gear: the two things that belong to the container rather than to any one app.
 *
 * **Appearance** is the suite's, not LifeOps'. One preset, one light/dark choice and one custom
 * palette paint all eight apps; under them each app carries an accent that is also chosen here. This
 * is the whole point of the screen — an app no longer decides what it looks like, so there is
 * exactly one place to look when something is the wrong colour. The launcher's own wallpaper is
 * chosen here too: it belongs to the container's home screen, so no hosted app is affected by it.
 *
 * **Backups** is the archive the sandbox has always driven: pick apps, write one zip, read it back —
 * and, under it, the same archive sent to the household's own Azure storage account on a schedule,
 * because the backup that saves somebody is the one nobody had to remember to take.
 *
 * **Updates** is the third thing that belongs to the container rather than to any app: the suite
 * ships as one sideloaded APK built from a git tag, so this is where it asks GitHub whether a newer
 * release exists and installs it. See [UpdatesTab].
 */
@Composable
fun SandboxSettingsScreen(
    backup: BackupController,
    cloud: CloudBackupController,
    updates: UpdateController,
    tab: SettingsTab,
    onTabChange: (SettingsTab) -> Unit,
    focusedApp: AppId?,
    onBack: () -> Unit
) {
    val store = SuiteAppearanceStore.get(LocalContext.current)
    val appearance by store.state.collectAsState()

    // The system pickers. They belong to this screen, but the work they start belongs to
    // [BackupController], which outlives it — backing out to the home screen mid-archive does not
    // cancel the archive.
    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> backup.backupTo(uri) }
    val pickRestore = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> backup.restoreFrom(uri) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sandbox settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to home")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab.ordinal) {
                SettingsTab.entries.forEach { entry ->
                    Tab(
                        selected = entry == tab,
                        onClick = { onTabChange(entry) },
                        text = { Text(entry.label) }
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (tab) {
                    SettingsTab.APPEARANCE -> AppearanceTab(appearance, store, focusedApp)
                    SettingsTab.BACKUPS -> BackupsTab(
                        backup = backup,
                        cloud = cloud,
                        onBackup = { createBackup.launch(defaultBackupName()) },
                        onRestore = { pickRestore.launch(RESTORE_MIME_TYPES) }
                    )
                    SettingsTab.UPDATES -> UpdatesTab(updates)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Appearance
// ---------------------------------------------------------------------------------------------

internal const val WALLPAPER_COLUMNS = 4

// ---------------------------------------------------------------------------------------------
// Backups
// ---------------------------------------------------------------------------------------------

// ---------------------------------------------------------------------------------------------

@Composable
internal fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
