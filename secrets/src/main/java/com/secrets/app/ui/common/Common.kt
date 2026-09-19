package com.secrets.app.ui.common

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.operations.suitekit.SuiteApps
import com.operations.vaultkit.SecretOwner
import com.operations.vaultkit.SecretStrength
import com.operations.vaultkit.VaultItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Copying a secret, done properly.
 *
 * Android's clipboard is a shared buffer: the system shows a toast every time something reads it,
 * and a password left in it until the next copy is a password sitting in a place other apps can
 * reach. So this does two things beyond the obvious:
 *
 *  - marks the clip **sensitive**, which stops the system's paste preview showing the password on
 *    screen in a floating bubble;
 *  - **clears it** after [afterSeconds], comparing before it does — if the household copied
 *    something else in the meantime, clearing would throw away their work rather than their
 *    password.
 *
 * Clearing is a best effort and honestly labelled: an app that read the clipboard in the meantime
 * has it, and nothing here can take that back. It closes the window, it does not undo a paste.
 */
object SecretClipboard {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun copy(context: Context, label: String, value: String, afterSeconds: Int) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText(label, value).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        manager.setPrimaryClip(clip)

        if (afterSeconds <= 0) return
        scope.launch {
            delay(afterSeconds * 1000L)
            val current = manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            if (current == value) {
                // An empty clip rather than nothing at all: some versions keep showing the last clip
                // in the paste bar if the clipboard is simply left alone.
                manager.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }
}

/**
 * A secret, shown the way a vault should show one: masked, revealable, copyable, and never selectable
 * as plain text by default.
 *
 * The reveal is per-field and resets whenever the row leaves composition, which is a small thing
 * with a real effect — a password revealed on one item does not stay revealed when the next one is
 * opened, so the screenshot somebody takes of item three does not contain item two's password.
 */
@Composable
fun SecretValue(
    value: String,
    label: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyRevealed: Boolean = false
) {
    var revealed by remember(value) { mutableStateOf(initiallyRevealed) }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = if (revealed) value else mask(value),
                // Monospace when revealed so that `l`, `1` and `I` are three different characters —
                // which is the whole difference between reading a password off a screen and typing
                // it wrong three times.
                fontFamily = if (revealed) FontFamily.Monospace else FontFamily.Default,
                style = MaterialTheme.typography.bodyLarge
            )
        }
        IconButton(onClick = { revealed = !revealed }) {
            Icon(
                imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = if (revealed) "Hide" else "Reveal"
            )
        }
        IconButton(onClick = onCopy) {
            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
        }
    }
}

/** A fixed-width mask, so the length of a password is not readable from the width of the dots. */
fun mask(value: String): String = if (value.isEmpty()) "—" else "••••••••••••"

/**
 * The strength bar.
 *
 * Coloured in three steps rather than a gradient, because a gradient invites the reading that
 * "slightly greener" means "slightly safer", and the underlying estimate is nowhere near that
 * precise (see [SecretStrength], which says so at length).
 */
@Composable
fun StrengthBar(secret: String, modifier: Modifier = Modifier) {
    val bits = remember(secret) { SecretStrength.bits(secret) }
    val rating = remember(bits) { SecretStrength.rate(bits) }
    val colour = when (rating) {
        SecretStrength.Rating.EMPTY -> MaterialTheme.colorScheme.outline
        SecretStrength.Rating.WEAK -> MaterialTheme.colorScheme.error
        SecretStrength.Rating.FAIR -> Color(0xFFB7791F)
        SecretStrength.Rating.STRONG, SecretStrength.Rating.EXCELLENT -> MaterialTheme.colorScheme.primary
    }

    Column(modifier = modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { SecretStrength.fraction(bits) },
            color = colour,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = rating.name.lowercase().replaceFirstChar(Char::uppercaseChar),
                style = MaterialTheme.typography.labelMedium,
                color = colour
            )
            Text(
                text = "${bits.toInt()} bits · ${SecretStrength.crackTime(bits)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * What to call the app a mirrored item belongs to.
 *
 * Resolved through [SuiteApps] rather than from the item's stored string, so an app renamed in the
 * catalogue is renamed here too — and an item whose owning app no longer exists still shows
 * something rather than a blank.
 */
fun ownerLabel(item: VaultItem): String? {
    val key = item.managedBy ?: return null
    val owner = SecretOwner.fromKey(key) ?: return key.replaceFirstChar(Char::uppercaseChar)
    // An app is named as the home screen names it, so "Finance" on a tile and "Finance" on a vault
    // row are visibly the same thing. The shell has no tile, so it answers with its own name.
    val appId = owner.appId ?: return owner.displayName
    return SuiteApps.of(appId).label
}
