@file:OptIn(ExperimentalMaterial3Api::class)

package com.operations.sandbox.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.operations.backupkit.AppId
import com.operations.suite.ui.SuiteAppearanceStore
import com.operations.suite.ui.SuiteIcons
import com.operations.suite.ui.accentArgb
import com.operations.suitekit.SuiteAppearance
import com.operations.suitekit.SuiteApps
import com.operations.suitekit.SuiteColors
import com.operations.suitekit.SuitePalette
import com.operations.suitekit.SuitePreset
import com.operations.suitekit.SuiteThemes

/** Which half of the gear is showing. */
enum class SettingsTab(val label: String) { APPEARANCE("Appearance"), BACKUPS("Backups") }

/**
 * The sandbox's gear: the two things that belong to the container rather than to any one app.
 *
 * **Appearance** is the suite's, not LifeOps'. One preset, one light/dark choice and one custom
 * palette paint all six apps; under them each app carries an accent that is also chosen here. This
 * is the whole point of the screen — an app no longer decides what it looks like, so there is
 * exactly one place to look when something is the wrong colour.
 *
 * **Backups** is the archive the sandbox has always driven: pick apps, write one zip, read it back.
 */
@Composable
fun SandboxSettingsScreen(
    backup: BackupController,
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
                        onBackup = { createBackup.launch(defaultBackupName()) },
                        onRestore = { pickRestore.launch(RESTORE_MIME_TYPES) }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Appearance
// ---------------------------------------------------------------------------------------------

@Composable
private fun AppearanceTab(
    appearance: SuiteAppearance,
    store: SuiteAppearanceStore,
    focusedApp: AppId?
) {
    SectionCard(
        title = "Suite theme",
        subtitle = "One look for the whole suite. Every app follows this — there is no per-app theme."
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (appearance.darkMode) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (appearance.darkMode) "Dark mode" else "Light mode",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = appearance.darkMode,
                onCheckedChange = { store.darkMode = it }
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Colour preset",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SuitePreset.entries.forEach { preset ->
                PresetCard(
                    preset = preset,
                    selected = appearance.preset == preset,
                    appearance = appearance,
                    onClick = { store.preset = preset },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (appearance.preset == SuitePreset.CUSTOM) {
            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Text(
                "Custom colours",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(8.dp))
            CustomPaletteEditor(appearance.palette) { store.palette = it }
        }
    }

    SectionCard(
        title = "App colours",
        subtitle = "Each app's identity. Home icons always wear it; the switch decides whether the " +
            "app's own screens are tinted with it too."
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Tint each app with its colour",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = appearance.appAccentsEnabled,
                onCheckedChange = { store.appAccentsEnabled = it }
            )
        }
        Spacer(Modifier.height(4.dp))

        SuiteApps.all.forEach { info ->
            // `key` gives each row its own identity, so the open/closed state below belongs to the
            // app it was opened on rather than to the position it happens to sit in.
            key(info.appId) {
                AppAccentRow(
                    appId = info.appId,
                    label = info.label,
                    tagline = info.tagline,
                    appearance = appearance,
                    startExpanded = focusedApp == info.appId,
                    onPick = { hex -> store.setAccent(info.appId, hex) },
                    onReset = { store.resetAccent(info.appId) }
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { store.resetAllAccents() }) {
            Text("Reset every app to its original colour")
        }
    }
}

/** One app's colour: a swatch to see it by, and — when opened — the ways to change it. */
@Composable
private fun AppAccentRow(
    appId: AppId,
    label: String,
    tagline: String,
    appearance: SuiteAppearance,
    startExpanded: Boolean,
    onPick: (String) -> Unit,
    onReset: () -> Unit
) {
    var expanded by rememberSaveable(startExpanded) { mutableStateOf(startExpanded) }
    val argb = appearance.accentArgb(appId)

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppGlyph(icon = SuiteIcons.forApp(appId), argb = argb, size = 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    tagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                SuiteColors.toHex(argb),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (expanded) {
            Column(Modifier.padding(start = 50.dp, bottom = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SUGGESTED_ACCENTS.forEach { suggestion ->
                        Swatch(
                            argb = suggestion,
                            selected = suggestion == argb,
                            onClick = { onPick(SuiteColors.toHex(suggestion)) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HexField(
                        label = "Hex",
                        value = SuiteColors.toHex(argb),
                        onCommit = onPick,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onReset) { Text("Reset") }
                }
            }
        }
    }
}

/** The palette the accent picker offers before anyone reaches for a hex code. */
private val SUGGESTED_ACCENTS: List<Long> = listOf(
    0xFF6200EEL, // purple
    0xFF2C7A7BL, // teal
    0xFF5A5ABFL, // indigo
    0xFF2F855AL, // green
    0xFF4A5568L, // slate
    0xFF9333EAL, // violet
    0xFFB7791FL, // amber
    0xFFC53030L  // red
)

@Composable
private fun Swatch(argb: Long, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = Color(SuiteColors.contrastOn(argb)),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun CustomPaletteEditor(palette: SuitePalette, onChange: (SuitePalette) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HexField("Primary", palette.primary) { onChange(palette.copy(primary = it)) }
        HexField("Secondary", palette.secondary) { onChange(palette.copy(secondary = it)) }
        HexField("Tertiary", palette.tertiary) { onChange(palette.copy(tertiary = it)) }
        HexField("Dark background", palette.darkBackground) { onChange(palette.copy(darkBackground = it)) }
        HexField("Light background", palette.lightBackground) { onChange(palette.copy(lightBackground = it)) }
    }
}

/**
 * A hex colour field that only reports a value the suite can actually paint with. Half-typed text
 * stays local to the field, so editing `#2C7A7B` never flashes the whole suite through the colours
 * `#2`, `#2C`, `#2C7` happen to parse as.
 */
@Composable
private fun HexField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onCommit: (String) -> Unit
) {
    var text by remember(value) { mutableStateOf(value) }
    val parsed = remember(text) { normalizedHex(text) }

    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            normalizedHex(typed)?.let(onCommit)
        },
        label = { Text(label) },
        singleLine = true,
        isError = parsed == null,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        leadingIcon = {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(SuiteColors.parseHex(parsed ?: value)))
            )
        }
    )
}

/** `#RGB`/`#RRGGBB`/`#AARRGGBB` → a canonical hex string, or null while it is still being typed. */
private fun normalizedHex(text: String): String? {
    val raw = text.trim().removePrefix("#")
    if (raw.length !in setOf(3, 6, 8)) return null
    if (raw.any { it.digitToIntOrNull(16) == null }) return null
    return SuiteColors.toHex(SuiteColors.parseHex(raw))
}

@Composable
private fun PresetCard(
    preset: SuitePreset,
    selected: Boolean,
    appearance: SuiteAppearance,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Each card previews the scheme it would actually apply — resolved by the same code that paints
    // the apps, so what a preset looks like here is what the suite becomes.
    val preview = remember(preset, appearance.darkMode, appearance.palette) {
        SuiteThemes.scheme(preset, appearance.darkMode, appearance.palette)
    }
    OutlinedCard(
        onClick = onClick,
        modifier = modifier,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf(preview.primary, preview.secondary, preview.tertiary).forEach { colour ->
                    Box(Modifier.size(12.dp).clip(CircleShape).background(Color(colour)))
                }
            }
            Text(
                preset.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Backups
// ---------------------------------------------------------------------------------------------

@Composable
private fun BackupsTab(
    backup: BackupController,
    onBackup: () -> Unit,
    onRestore: () -> Unit
) {
    SectionCard(
        title = "What to include",
        subtitle = "Both buttons act on the apps ticked here."
    ) {
        backup.apps.forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !backup.working) {
                        backup.setSelected(entry.appId, entry.appId !in backup.selected)
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = entry.appId in backup.selected,
                    enabled = !backup.working,
                    onCheckedChange = { on -> backup.setSelected(entry.appId, on) }
                )
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.displayName, style = MaterialTheme.typography.bodyLarge)
                        // LifeOps is the suite's standard app; flag it here as the hub always has.
                        if (entry.appId == AppId.LIFEOPS) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "STANDARD",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        "Backup format v${entry.dataVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    SectionCard(
        title = "Backup & restore",
        subtitle = "Full Backup writes the selected apps into a single .zip. Restore reads that same " +
            "zip back into the selected apps."
    ) {
        Button(
            onClick = onBackup,
            enabled = backup.selected.isNotEmpty() && !backup.working,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Full Backup (${backup.selected.size} selected)")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onRestore,
            enabled = backup.selected.isNotEmpty() && !backup.working,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Restore from zip…")
        }
        if (backup.working) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        backup.status?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun SectionCard(
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
