package com.operations.sandbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.operations.sandbox.update.logic.AvailableRelease

/**
 * The Updates tab: where a sideloaded suite finds out it has fallen behind.
 *
 * There is no store in the loop — the APK is built by GitHub Actions from a tag and published as a
 * release — so the only thing that can tell this app a newer build exists is a request to GitHub.
 * The shell makes one at launch, at most every six hours, and the answer is a line on the home
 * screen; everything after that is manual. Download is a tap and install is another, because the
 * last one hands the screen to the system installer and that should never be a surprise.
 */
@Composable
internal fun UpdatesTab(updates: UpdateController) {
    val state = updates.state

    SectionCard(
        title = "This build",
        subtitle = "Updates are published as GitHub releases and installed from the APK attached to " +
            "them. Nothing is ever downloaded or installed without you tapping for it."
    ) {
        Text("Version ${updates.installedVersion}", style = MaterialTheme.typography.bodyLarge)
        Text(
            // A build made on a developer machine carries no tag, so it says so rather than
            // claiming a version number it doesn't have.
            if (updates.installedVersion.startsWith("0.0.0")) {
                "A local developer build — any published release will look newer than this."
            } else {
                "Installed from release v${updates.installedVersion}."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { updates.check() },
            enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state is UpdateState.Checking) "Checking…" else "Check for updates")
        }
        if (state is UpdateState.Checking) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Check at launch", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "One request to GitHub when the sandbox opens, no more than once every six " +
                        "hours. Turn it off and this screen's button is the only thing that checks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = updates.autoCheck, onCheckedChange = { updates.autoCheck = it })
        }
    }

    when (state) {
        is UpdateState.UpToDate -> SectionCard(title = "Up to date") {
            Text(
                "Version ${updates.installedVersion} is the newest release published.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        is UpdateState.Available -> ReleaseCard(
            release = state.release,
            actions = {
                Button(onClick = { updates.download(state.release) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Download ${state.release.tag}" + sizeSuffix(state.release))
                }
            },
            updates = updates
        )

        is UpdateState.Downloading -> ReleaseCard(
            release = state.release,
            actions = {
                if (state.progress < 0f) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Downloading…", style = MaterialTheme.typography.bodySmall)
                } else {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Downloading — ${(state.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { updates.cancelDownload() }) { Text("Cancel") }
            },
            updates = updates
        )

        is UpdateState.ReadyToInstall -> ReleaseCard(
            release = state.release,
            actions = {
                Button(onClick = { updates.install(state.apk) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Install ${state.release.tag}")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Android will ask you to confirm, and may first ask you to allow Operations " +
                        "Sandbox to install apps. Your data is kept — this installs over the top.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            updates = updates
        )

        is UpdateState.Failed -> SectionCard(title = "Couldn't check") {
            Text(state.message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { updates.check() }) { Text("Try again") }
                TextButton(onClick = { updates.dismiss() }) { Text("Dismiss") }
            }
        }

        UpdateState.Idle, UpdateState.Checking -> Unit
    }

    GitHubTokenCard(updates)
}

/**
 * Where the GitHub access token goes, and why there has to be one.
 *
 * The repository is private, and a private repository's releases are a 404 to an anonymous
 * request — indistinguishable, from the app's side, from a repository that has never published
 * anything. So without a token the updater is not merely restricted, it is silently useless, and
 * that is worth a card explaining itself rather than a mysterious failure.
 *
 * The token is stored encrypted on this phone and sent only to api.github.com. It is deliberately
 * *not* baked into the APK: an APK is a file that gets copied about, and a credential inside one is
 * a credential published. Once saved it is never displayed again — the field shows whether one
 * exists, not what it is.
 */
@Composable
private fun GitHubTokenCard(updates: UpdateController) {
    var draft by remember { mutableStateOf("") }

    SectionCard(
        title = "GitHub access",
        subtitle = "The repository is private, so checking for releases needs a personal access " +
            "token with read access to its contents. It is stored encrypted on this phone and " +
            "sent only to api.github.com."
    ) {
        if (updates.hasToken) {
            Text("A token is saved.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            label = { Text(if (updates.hasToken) "Replace the token" else "Paste a token") },
            placeholder = { Text("github_pat_…") },
            // A credential typed in a room with other people in it.
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    updates.saveToken(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank()
            ) {
                Text("Save")
            }
            if (updates.hasToken) {
                OutlinedButton(onClick = { updates.clearToken() }) { Text("Remove") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Make one at github.com/settings/tokens — a fine-grained token, this repository only, " +
                "with Contents: Read-only. Nothing else is needed. If the repository is ever made " +
                "public, remove the token; the updater works without one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One release, its notes, and whatever action applies to it right now.
 *
 * The notes are the release body straight from GitHub — plain text, not rendered markdown, and
 * height-capped with its own scroll so a long changelog can't push the action button off the page.
 */
@Composable
private fun ReleaseCard(
    release: AvailableRelease,
    updates: UpdateController,
    actions: @Composable ColumnScope.() -> Unit
) {
    SectionCard(
        title = "${release.tag} is available",
        subtitle = "You have ${updates.installedVersion}."
    ) {
        if (release.notes.isNotBlank()) {
            Text(
                release.notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
            )
            Spacer(Modifier.height(12.dp))
        }
        actions()
        if (release.htmlUrl.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { updates.openReleasePage(release) }) { Text("View on GitHub") }
        }
    }
}

/** " · 84 MB", or nothing when GitHub didn't report a size. */
private fun sizeSuffix(release: AvailableRelease): String {
    if (release.apkSizeBytes <= 0) return ""
    val megabytes = release.apkSizeBytes.toDouble() / (1024 * 1024)
    return " · ${"%.0f".format(megabytes)} MB"
}
