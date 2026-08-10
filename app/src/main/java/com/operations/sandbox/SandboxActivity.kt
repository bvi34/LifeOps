package com.operations.sandbox

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.operations.backupkit.AppId
import com.operations.sandbox.ui.theme.SandboxTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Operations Sandbox home: the single launcher entry point. It lists the hosted apps with a
 * checkbox each, opens either one, and drives a full backup (into one `.zip` via the system file
 * picker) or a restore from such a zip. Everything the buttons do is delegated to [BackupCenter];
 * this file is only wiring + Compose.
 */
class SandboxActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val center = BackupCenter(this, sandboxVersion = "1.0")
        setContent {
            SandboxTheme {
                SandboxHome(center)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SandboxHome(center: BackupCenter) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val allIds = remember { center.contributors.map { it.appId } }
    var selected by remember { mutableStateOf(allIds.toSet()) }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    // Full backup → system "create document" picker → stream the archive into the chosen file.
    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) {
            status = "Backup cancelled"
            return@rememberLauncherForActivityResult
        }
        val chosen = selected
        scope.launch {
            working = true
            status = "Backing up ${chosen.size} app(s)…"
            val result = runCatching {
                val out = context.contentResolver.openOutputStream(uri)
                    ?: error("Could not open the destination file")
                center.backup(chosen, out)
            }
            working = false
            status = result.fold(
                onSuccess = { "Backup saved — ${chosen.joinToString { it.defaultDisplayName }}." },
                onFailure = { "Backup failed: ${it.message}" }
            )
        }
    }

    // Restore → system "open document" picker → peek the manifest, then restore the apps that are
    // both selected here and present in the archive.
    val pickRestore = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            status = "Restore cancelled"
            return@rememberLauncherForActivityResult
        }
        val chosen = selected
        scope.launch {
            working = true
            status = "Reading archive…"
            val result = runCatching {
                val manifest = context.contentResolver.openInputStream(uri)?.use { center.peek(it) }
                    ?: error("This file isn't a readable Operations Sandbox archive")
                val present = manifest.apps.mapNotNull { AppId.fromKey(it.appId) }.toSet()
                val toRestore = chosen intersect present
                if (toRestore.isEmpty()) error("None of the selected apps are in this archive")
                context.contentResolver.openInputStream(uri)!!.use { center.restore(toRestore, it) }
                toRestore
            }
            working = false
            status = result.fold(
                onSuccess = { done ->
                    "Restored ${done.joinToString { it.defaultDisplayName }}. " +
                        "Fully close Operations Sandbox (swipe it from Recents) and reopen it so the " +
                        "restored data loads."
                },
                onFailure = { "Restore failed: ${it.message}" }
            )
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Operations Sandbox") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Apps", style = MaterialTheme.typography.titleMedium)

            center.contributors.forEach { contributor ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = contributor.appId in selected,
                            enabled = !working,
                            onCheckedChange = { on ->
                                selected = if (on) selected + contributor.appId
                                else selected - contributor.appId
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(contributor.displayName, style = MaterialTheme.typography.titleMedium)
                                // LifeOps is the suite's standard app; flag it in the hub.
                                if (contributor.appId == AppId.LIFEOPS) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "STANDARD",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                "Backup format v${contributor.dataVersion}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(onClick = { openApp(context, contributor.appId) }) {
                            Text("Open")
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Text("Backup & Restore", style = MaterialTheme.typography.titleMedium)
            Text(
                "Full Backup writes the selected apps into a single .zip. Restore reads that same " +
                    "zip back into the selected apps.",
                style = MaterialTheme.typography.bodySmall
            )

            Button(
                onClick = { createBackup.launch(defaultBackupName()) },
                enabled = selected.isNotEmpty() && !working,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Full Backup (${selected.size} selected)")
            }

            OutlinedButton(
                onClick = { pickRestore.launch(arrayOf("application/zip", "application/octet-stream")) },
                enabled = selected.isNotEmpty() && !working,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Restore from zip…")
            }

            if (working) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            status?.let { message ->
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Open a hosted app's UI in this same process by launching its (now non-launcher) activity. */
private fun openApp(context: Context, appId: AppId) {
    val target = when (appId) {
        AppId.LIFEOPS -> com.lifeops.app.MainActivity::class.java
        AppId.CITATION -> com.citation.app.MainActivity::class.java
        AppId.LOGISTICS -> com.logistics.app.MainActivity::class.java
        AppId.ADVISOR -> com.advisor.app.MainActivity::class.java
    }
    context.startActivity(Intent(context, target))
}

private fun defaultBackupName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    return "operations-backup-$stamp.zip"
}
