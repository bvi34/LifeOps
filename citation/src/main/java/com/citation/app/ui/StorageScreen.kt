package com.citation.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.citation.app.data.storageReport
import com.citation.core.manifest.Recoverability
import com.citation.core.manifest.StorageInventory
import com.citation.core.manifest.StorageReport

/**
 * The storage-visibility body. It shows every item's footprint and the reclaimable-vs-irreplaceable
 * split, and **nothing more** — no ceilings, no auto-eviction of favourites or owned files. It can
 * point out what's *safe* to prune (reclaimable, refetchable cache), but pruning is always your call.
 * Rendered content-only so the Settings tab can host it as a section.
 */
@Composable
fun StorageReportBody(vm: ReaderViewModel, modifier: Modifier = Modifier) {
    val report by produceState<StorageReport?>(initialValue = null) { value = vm.storageReport() }

    val r = report
    Column(modifier.fillMaxSize()) {
        if (r == null) {
            Text("Measuring…", Modifier.padding(16.dp))
            return@Column
        }

        // Aggregate summary.
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            SummaryRow("Total", StorageInventory.formatBytes(r.totalBytes), FontWeight.Bold)
            SummaryRow("Reclaimable (refetchable)", StorageInventory.formatBytes(r.reclaimableBytes))
            SummaryRow("Irreplaceable (won't auto-delete)", StorageInventory.formatBytes(r.irreplaceableBytes))
            Text(
                "Visibility only — Citation never auto-deletes favourites or owned files. You decide what to prune.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Divider()

        LazyColumn(Modifier.fillMaxSize()) {
            // Key on position, not label: two items can share a label (e.g. two serials that landed
            // with the same title), and a duplicate LazyColumn key throws — which crashed the whole
            // Settings screen the moment the second one scrolled into view.
            itemsIndexed(
                r.entries.sortedByDescending { it.bytes },
                key = { index, entry -> "$index:${entry.label}" }
            ) { _, entry ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
                        RecoverabilityBadge(entry.recoverability)
                    }
                    Text(
                        StorageInventory.formatBytes(entry.bytes),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, weight: FontWeight = FontWeight.Normal) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = weight, color = MaterialTheme.colorScheme.onBackground)
        Text(value, fontWeight = weight, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun RecoverabilityBadge(recoverability: Recoverability) {
    val (label, color) = when (recoverability) {
        Recoverability.RECLAIMABLE -> "Reclaimable" to Color(0xFF2E7D32)
        Recoverability.IRREPLACEABLE -> "Irreplaceable" to Color(0xFFB0653B)
    }
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
        Text(
            label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
