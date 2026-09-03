package com.maintenance.app.ui.asset

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maintenance.app.logic.AssetKind
import com.maintenance.app.logic.SchedulePack

/**
 * One schedule on offer, with where its numbers came from.
 *
 * The source line is not decoration. A transcribed schedule is a claim about somebody's vehicle or
 * somebody's house, and the honest thing is to say where it came from and that nobody has checked
 * it — an interval you can see the provenance of is one you might correct, and every item becomes an
 * ordinary editable plan the moment it is applied.
 *
 * Shared by the vehicle and the home sections because the offer is identical in both: here is a
 * list somebody else wrote, here is who wrote it, and it is yours the moment you take it.
 */
@Composable
fun PackRow(pack: SchedulePack, onApply: () -> Unit, applied: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(pack.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(
            pack.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            if (pack.provisional) "${pack.source} — ${pack.fit.kind.unchecked()}" else pack.source,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Already applied is still offered, because a later build can add items to a pack and a
        // second apply adds only what is missing. What changes is what the button admits to doing.
        TextButton(onClick = onApply) { Text(if (applied) "Apply again" else "Apply") }
    }
}

/** What "nobody has checked this" means for the kind of thing the pack is about. */
private fun AssetKind.unchecked(): String = when (this) {
    AssetKind.VEHICLE -> "not yet checked against a manual"
    else -> "a starting point, not a schedule anybody wrote for this address"
}
