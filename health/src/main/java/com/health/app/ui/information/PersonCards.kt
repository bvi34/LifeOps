package com.health.app.ui.information

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.app.data.model.Profile
import com.health.app.logic.TempUnit
import com.health.app.logic.Temperature
import com.health.app.ui.common.*

/**
 * The top of the Information tab: who this person is, what is normal for them, and how temperatures
 * are shown.
 *
 * These four inherit what used to be the People tab. Health no longer keeps a household screen — the
 * People app owns the household and Health is a peer on its sync seam — so what is left here is only
 * what Health itself owns: a person's usual temperature and their medical note, neither of which is
 * ever published, plus the °C/°F choice that decides how both are read.
 */

// --- the person, and what is normal for them -------------------------------------------------------
//
// These four sit at the top of the Information tab because they are the context every other number in
// Health is read against. They also inherit what used to be the People tab: Health no longer keeps its
// own household screen, because the household is the People app's to own and two places to edit it
// would be two answers to "who lives here".

/**
 * Who this person is, as the household directory has them.
 *
 * Everything on this card is **read-only here on purpose**. Name, relationship and birth date come
 * over the People seam and are edited in People; showing them with an edit field would invite
 * somebody to change a name in Health and find it changed back on the next sync round, which is a
 * worse experience than not offering it.
 *
 * The birth date gets its own line when it is missing, because it is the one field here that is
 * load-bearing rather than decorative: it is what makes the fever thresholds age-aware, and a profile
 * without one silently gets the adult rules.
 */
@Composable
internal fun AboutPersonCard(person: Profile, onEdit: () -> Unit) {
    val ageLabel = person.ageLabelAt(System.currentTimeMillis())

    SectionCard(
        title = person.name,
        trailing = { TextButton(onClick = onEdit) { Text("Edit") } }
    ) {
        val descriptor = listOfNotNull(
            person.relationship?.trim()?.ifBlank { null },
            ageLabel
        ).joinToString(" · ")
        if (descriptor.isNotBlank()) {
            Text(descriptor, style = MaterialTheme.typography.bodyMedium)
        }

        if (person.birthDate.isNullOrBlank()) {
            Text(
                "No birth date. Health will use the adult fever thresholds for them — the ones for a " +
                    "six-week-old are very different, so it is worth adding in People.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        Text(
            "Their name, relationship and birth date belong to the household directory — change them " +
                "in People and Health follows. Their usual temperature and the note below are " +
                "Health's own and never leave it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The person's own normal — the baseline every reading is implicitly compared against, and the note
 * you would want in front of you at 3am.
 *
 * Neither field is published over the People seam and neither has a column anywhere else in the
 * suite, so this is the only screen in the household that can change them. The note in particular is
 * refused by the packet mapper explicitly: People has a field called `note` too, and it means "likes
 * hiking, hates crowds".
 */
@Composable
internal fun NormalForThemCard(person: Profile, unit: TempUnit, onEdit: () -> Unit) {
    SectionCard(
        title = "What's normal for them",
        trailing = { TextButton(onClick = onEdit) { Text("Edit") } }
    ) {
        val baseline = person.baselineTempC
        if (baseline == null) {
            Text(
                "No usual temperature recorded. Health will band readings against the published " +
                    "thresholds, which is the right default — but some people simply run at 36.4, and " +
                    "a 37.6 means more for them than the general rule suggests.",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Text(
                "Usually ${Temperature.format(baseline, unit)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Recorded as their own normal, so a reading can be read against them rather than " +
                    "only against the population.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val notes = person.notes?.trim().orEmpty()
        if (notes.isNotBlank()) {
            HorizontalDivider()
            Text("Notes", style = MaterialTheme.typography.labelMedium)
            Text(notes, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * °C or °F.
 *
 * It lives on this tab rather than in a settings screen because choosing the unit and recording that
 * somebody runs at 36.4 are the same act — saying how temperatures should read for this household —
 * and Health has no settings screen otherwise. Readings are always stored in Celsius and converted
 * for display, so changing this never rewrites anything already recorded, and the card says so.
 */
@Composable
internal fun DisplayUnitCard(unit: TempUnit, onSelect: (TempUnit) -> Unit) {
    SectionCard(title = "Display") {
        Text("Show temperatures in", style = MaterialTheme.typography.bodySmall)
        ChoiceRow(
            options = TempUnit.entries,
            selected = unit,
            onSelect = onSelect,
            label = { if (it == TempUnit.CELSIUS) "Celsius (°C)" else "Fahrenheit (°F)" }
        )
        Text(
            "Readings are always stored in Celsius and converted for display, so changing this never " +
                "rewrites anything already recorded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The two fields Health owns outright.
 *
 * Deliberately **not** a profile editor: there is no name, no relationship and no birth date on it,
 * because those are the directory's and offering them here would mean an edit that silently reverts
 * on the next sync round. The dialog says where to go for them instead of pretending.
 *
 * The baseline is typed in whatever unit the household is using and converted on the way in, because
 * somebody who reads temperatures in Fahrenheit does not know their child's normal in Celsius.
 */
@Composable
internal fun HealthDetailsDialog(
    profile: Profile,
    unit: TempUnit,
    onDismiss: () -> Unit,
    onConfirm: (baselineC: Double?, notes: String?) -> Unit
) {
    var baseline by remember {
        mutableStateOf(profile.baselineTempC?.let { Temperature.formatBare(it, unit) }.orEmpty())
    }
    var notes by remember { mutableStateOf(profile.notes.orEmpty()) }

    val parsedBaseline = Temperature.parseToCelsius(baseline, unit)
    val baselineInvalid = baseline.isNotBlank() && parsedBaseline == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(profile.name) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DecimalField(
                    value = baseline,
                    onValueChange = { baseline = it },
                    label = "Their usual temperature (${unit.symbol})",
                    isError = baselineInvalid,
                    supportingText = if (baselineInvalid) "That isn't a temperature Health can read"
                    else "Optional. Leave it empty and readings are banded against the published thresholds.",
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    supportingText = {
                        Text("Allergies, conditions, the doctor's number — whatever you'd want in front of you at 3am")
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "Neither of these ever leaves Health — they are not published to People, LifeOps " +
                        "or anywhere else. Name, relationship and birth date are the directory's: " +
                        "change those in People.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !baselineInvalid,
                onClick = {
                    onConfirm(
                        if (baseline.isBlank()) null else parsedBaseline,
                        notes.trim().ifBlank { null }
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
