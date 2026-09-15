package com.health.app.ui.coverage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.health.app.data.model.CoverageCard
import com.health.app.data.store.CardImageStore
import com.health.app.logic.Insurance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One card in full — both faces, the photos of it, and what is printed on each.
 */

/**
 * The card itself, both faces, with whatever photographs are attached and the button that turns the
 * whole thing into a PDF.
 *
 * The typed fields come first even when there are photographs, and the export does the same: a
 * photograph is authoritative and hard to read, while typed fields are legible and selectable, and
 * the person at the desk wants the second thing. The photograph is the evidence behind it.
 */
@Composable
internal fun CardSheet(
    card: CoverageCard,
    images: CardImageStore,
    onDismiss: () -> Unit,
    onExport: (CoverageCard) -> Unit,
    onAttach: (android.net.Uri, front: Boolean, toPlan: Boolean) -> Unit,
    onClearImage: (front: Boolean, fromPlan: Boolean) -> Unit
) {
    var pickingFront by remember { mutableStateOf(true) }
    // A photo attached to the policy is the one envelope everybody on it shares; one attached to the
    // membership is this person's own card. Most households want the first, so it is the default.
    var attachToPlan by remember { mutableStateOf(true) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onAttach(it, pickingFront, attachToPlan) }
    }

    fun pick(front: Boolean) {
        pickingFront = front
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(card.plan.carrierName) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                card.layout.front.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                CardFaceBlock("Front", card.layout.front.fields.map { it.label to it.value })
                if (card.layout.back.fields.isNotEmpty()) {
                    CardFaceBlock("Back", card.layout.back.fields.map { it.label to it.value })
                }

                HorizontalDivider()
                Text("Photographs", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Saved on the device when you attach them, so the PDF can be produced later " +
                        "without asking for the picture again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = attachToPlan, onCheckedChange = { attachToPlan = it })
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (attachToPlan) "One card for everybody on this policy"
                        else "${card.memberName}'s own card",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                CardPhotoRow(
                    label = "Front",
                    path = card.frontImagePath,
                    images = images,
                    onPick = { pick(true) },
                    onClear = { onClearImage(true, attachToPlan) }
                )
                CardPhotoRow(
                    label = "Back",
                    path = card.backImagePath,
                    images = images,
                    onPick = { pick(false) },
                    onClear = { onClearImage(false, attachToPlan) }
                )

                Text(
                    Insurance.CARD_DISCLAIMER,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onExport(card) }) { Text("Export PDF") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun CardFaceBlock(title: String, rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun CardPhotoRow(
    label: String,
    path: String?,
    images: CardImageStore,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val bitmap = rememberCardImage(images, path)
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "$label of the card",
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(56.dp).weight(1f)
            )
        } else {
            Text(
                "$label — no photo",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
        TextButton(onClick = onPick) { Text(if (bitmap == null) "Attach" else "Replace") }
        if (bitmap != null) TextButton(onClick = onClear) { Text("Remove") }
    }
}

/**
 * Decode a stored card photo off the main thread and hold it for as long as the path is the same.
 *
 * `BitmapFactory` on the composition thread is a dropped frame per card, and the store is on disk
 * rather than in the database precisely so these can be large.
 */
@Composable
internal fun rememberCardImage(images: CardImageStore, path: String?): ImageBitmap? {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = if (path == null) null
        else withContext(Dispatchers.IO) { images.load(path)?.asImageBitmap() }
    }
    return bitmap
}
