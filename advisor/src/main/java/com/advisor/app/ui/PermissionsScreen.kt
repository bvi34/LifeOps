package com.advisor.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.advisor.app.logic.SourceApp

/**
 * The permission gate, made visible. One switch per hosted app; Advisor reads an app's data only
 * while its switch is on. Denied-by-default is the whole point — the user opts each app in, and can
 * revoke at any time, which the next question honours immediately (denied apps are never loaded).
 */
@Composable
fun PermissionsScreen(vm: AdvisorViewModel, modifier: Modifier = Modifier) {
    val permissions by vm.permissions.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Data access", style = MaterialTheme.typography.titleMedium)
        Text(
            "Advisor is offline and reads only what you allow here. Each app stays off until you " +
                "turn it on; turning it off stops Advisor from reading that app on the next question.",
            style = MaterialTheme.typography.bodySmall
        )

        SourceApp.entries.forEach { app ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(app.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(describe(app), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = permissions.isGranted(app),
                        onCheckedChange = { on -> vm.setPermission(app, on) }
                    )
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        Text("Model", style = MaterialTheme.typography.titleMedium)
        Text(vm.model.label(), style = MaterialTheme.typography.bodyMedium)
        Text(
            "The assistant is a placeholder: it retrieves and cites your own records but does not " +
                "yet run a language model. A small local model (~2–4B parameters, Q4 GGUF) is the " +
                "intended drop-in and would run fully on-device.",
            style = MaterialTheme.typography.bodySmall
        )

        OutlinedButton(onClick = { vm.clearConversation() }) {
            Text("Clear conversation")
        }
    }
}

private fun describe(app: SourceApp): String = when (app) {
    SourceApp.LIFEOPS -> "Tasks, aspects, projects and milestones."
    SourceApp.CITATION -> "Your library and reading notes."
    SourceApp.LOGISTICS -> "Pantry stock and grocery list."
}
