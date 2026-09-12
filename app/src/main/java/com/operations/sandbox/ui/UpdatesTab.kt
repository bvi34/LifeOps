package com.operations.sandbox.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
                if (state.signingConflict) {
                    SigningConflictActions(updates, state)
                } else {
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
                }
            },
            updates = updates
        )

        is UpdateState.Failed -> SectionCard(title = state.title) {
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
 * Where the GitHub access token goes, and when there needs to be one.
 *
 * A *private* repository's releases are a 404 to an anonymous request, so the updater cannot see
 * them without a token. A public one needs nothing, and this card used to claim otherwise — it
 * stated flatly that the repository was private, which turned the token into a step everyone
 * assumed was mandatory and the first thing blamed whenever a check came back empty. It is offered
 * here, not demanded.
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
        subtitle = "Only needed if the repository is private — a public one's releases are " +
            "readable without any credentials. A token also raises GitHub's rate limit. It is " +
            "stored encrypted on this phone and sent only to api.github.com."
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
                "with Contents: Read-only. Nothing else is needed. A token cannot help when a " +
                "release simply has no APK attached to it; that is fixed by the release workflow, " +
                "not from here.",
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

/**
 * What to offer instead of an Install button that the system installer is certain to refuse.
 *
 * Android will not replace an app with one signed by a different key, and a developer build (debug
 * keystore) and a release (the workflow's keystore) are exactly that pair. The system's own words
 * for it — "App not installed as package conflicts with an existing package" — name neither the
 * cause nor the way out, and arrive after a 90 MB download, so the card says both here.
 *
 * The order of the steps is not arbitrary, and the first one is the one that is easy to get wrong:
 * the downloaded APK sits in this app's cache and is deleted along with it, and on a private
 * repository it cannot simply be re-downloaded in a browser afterwards — the asset needs the token
 * that was uninstalled with the app. So the file is saved out first, and only then is anything
 * uninstalled.
 */
@Composable
private fun SigningConflictActions(updates: UpdateController, state: UpdateState.ReadyToInstall) {
    val saveApk = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri -> updates.saveApkTo(uri, state.apk) }

    Text(
        "This APK can't be installed over the build you are running: they are signed with " +
            "different keys, and Android refuses that swap — it is what \"package conflicts with " +
            "an existing package\" means. Nothing is wrong with the release. " +
            if (updates.installedVersion.startsWith("0.0.0")) {
                "The build installed here was made on a machine, so it carries the debug key, " +
                    "where everything the release workflow publishes carries the release keystore's."
            } else {
                "Both are real releases, so the signing keystore must have changed since " +
                    "${updates.installedVersion} was built."
            },
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(12.dp))
    Text(
        "Moving across costs the app's data unless you carry it over, in this order:\n" +
            "1. Save the APK below — it has to leave this app before the app goes.\n" +
            "2. Back up on the Backups tab, to a file outside the app.\n" +
            "3. Uninstall Operations Sandbox.\n" +
            "4. Open the saved APK from Files and install it.\n" +
            "5. Reopen the sandbox and restore the backup.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { saveApk.launch("operations-sandbox-${state.release.tag}.apk") },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Save the APK…")
    }
    updates.saveStatus?.let { status ->
        Spacer(Modifier.height(8.dp))
        Text(status, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(4.dp))
    // Left reachable on purpose: this reads the certificates rather than asking the installer, and
    // if it has somehow read them wrong, the user should still be able to try the thing they came
    // here to do.
    TextButton(onClick = { updates.install(state.apk) }) { Text("Try installing anyway") }
}

/** " · 84 MB", or nothing when GitHub didn't report a size. */
private fun sizeSuffix(release: AvailableRelease): String {
    if (release.apkSizeBytes <= 0) return ""
    val megabytes = release.apkSizeBytes.toDouble() / (1024 * 1024)
    return " · ${"%.0f".format(megabytes)} MB"
}
