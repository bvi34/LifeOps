package com.maintenance.app.ui.asset

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maintenance.app.data.model.AssetDetail
import com.maintenance.app.logic.HomeLookup
import com.maintenance.app.logic.SchedulePacks
import com.maintenance.app.ui.common.SectionCard

/**
 * What a house's own fields say it should be doing.
 *
 * This is the home's answer to [VehicleSection], and the difference between them is the whole point
 * of the section. A vehicle sends eleven characters to a decoder and gets a specification back; **a
 * house sends nothing anywhere.** There is no request about a home that is a question about a model
 * rather than a question about the household — an address is not a specification, it is where these
 * people live — so the reading happens here, on the device, out of fields the household already
 * filled in for its own reasons.
 *
 * What it reads is small and it is enough: the ZIP out of the address says what winter does here,
 * the year says whether the house predates a few things worth having looked at, the sentence about
 * what the place has finds the systems that carry schedules of their own, and a loan against the
 * asset means there is paperwork owed as well as work.
 *
 * Everything is **offered rather than applied**, in the same shape the VIN decode uses: the
 * schedules that fit are listed, the whole catalogue is one press away for when the guess is wrong,
 * and nothing goes onto anybody's week until a button is pressed. A plan puts a task on a person's
 * week, and inventing those unasked is how an app stops being trusted.
 */
@Composable
fun HomeSection(vm: AssetDetailViewModel, detail: AssetDetail) {
    val lookup by vm.lookup.collectAsStateWithLifecycle()
    val asset = detail.asset

    val facts = remember(asset.attributes, detail.loans.size) {
        HomeLookup.read(
            address = asset.attribute("address"),
            structure = asset.attribute("structure"),
            yearBuilt = asset.attribute("yearBuilt"),
            features = asset.attribute("features"),
            hasMortgage = detail.loans.isNotEmpty()
        )
    }
    val fitting = remember(facts) { SchedulePacks.forHome(facts) }
    // Which packs this house has already taken, so a row can say so rather than looking untouched.
    val taken = detail.plans.mapNotNull { it.plan.sourcePack }.toSet()

    var showEverything by rememberSaveable { mutableStateOf(false) }

    SectionCard(title = "What this house needs doing") {
        Text(
            "Worked out on this device, from the address, the type of home, the year and what you " +
                "ticked that it has. A house has no VIN to look up and its address is not a model " +
                "number, so nothing here is asked of anybody.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (facts.descriptor.isNotBlank()) {
            Text(facts.descriptor, style = MaterialTheme.typography.bodyMedium)
        }
        if (facts.detail.isNotBlank()) {
            Text(
                facts.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // The climate is a guess from a ZIP code and says so — see `logic/HomeLookup.climateOf`.
        facts.climate?.let { climate ->
            Text(
                "${climate.detail} — by the ZIP code, which is a coarse guess. If it is wrong, the " +
                    "whole catalogue is below.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        facts.note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (facts.structure == null) {
            Text(
                "No type of home picked yet. It is what says whether the gutters are yours to clear " +
                    "and whether there are piers under this that settle — pick one in Edit and the " +
                    "list below changes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (facts.features.isEmpty()) {
            Text(
                "Nothing ticked in \"What it has\" yet — septic, a well, gas, solar, a pool and the " +
                    "rest. Each one brings a schedule with it, and they turn up here the moment it " +
                    "is ticked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider()

        val shown = if (showEverything) SchedulePacks.homePacks else fitting
        Text(
            if (showEverything) "Every home schedule" else "Schedules that fit this house",
            style = MaterialTheme.typography.labelLarge
        )
        Column {
            shown.forEach { pack ->
                PackRow(
                    pack = pack,
                    applied = pack.id in taken,
                    onApply = { vm.applyPack(pack) }
                )
            }
        }

        lookup.applied?.let { application ->
            Text(
                "${application.pack.label}: ${application.summary}.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        TextButton(onClick = { showEverything = !showEverything }) {
            Text(if (showEverything) "Only the ones that fit" else "Show every home schedule")
        }
    }
}
