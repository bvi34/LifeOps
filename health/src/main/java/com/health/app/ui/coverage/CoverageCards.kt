package com.health.app.ui.coverage

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.health.app.data.model.CoverageCard
import com.health.app.data.model.InsurancePlan
import com.health.app.data.store.CardImageStore
import com.health.app.logic.CoverageKind
import com.health.app.logic.CoverageStatus
import com.health.app.logic.DirectoryOutcome
import com.health.app.logic.Insurance
import com.health.app.logic.PlanType
import com.health.app.ui.common.SectionCard
import com.health.app.ui.common.formatStamp

/**
 * The wallet: one row per card a person is on, worst verdict first.
 */

@Composable
internal fun CardsList(
    cards: List<CoverageCard>,
    plans: List<InsurancePlan>,
    personName: String?,
    images: CardImageStore,
    probingPlanId: String?,
    onView: (CoverageCard) -> Unit,
    onExport: (CoverageCard) -> Unit,
    onEditPlan: (InsurancePlan) -> Unit,
    onJoinPlan: (InsurancePlan) -> Unit,
    onRemove: (CoverageCard) -> Unit,
    onProbe: (InsurancePlan) -> Unit,
    onAdd: () -> Unit
) {
    // Policies the household holds that this person is not on. Worth showing rather than hiding:
    // adding a child to the family plan already in the app is one tap, and re-typing the carrier,
    // group number and phone numbers for them would be four minutes and three typos.
    val notOn = plans.filter { plan -> !plan.archived && cards.none { it.plan.id == plan.id } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (cards.isEmpty() && notOn.isEmpty()) {
            item {
                EmptyState(
                    title = "No cards yet",
                    body = "Add the insurance card ${personName ?: "this person"} carries — the " +
                        "carrier, the member number, and the numbers on the back. Photograph it and " +
                        "Health can produce a PDF of the card whenever somebody asks for one.",
                    action = "Add a card",
                    onAction = onAdd
                )
            }
        }

        items(cards, key = { it.membership.id }) { card ->
            CoverageCardRow(
                card = card,
                images = images,
                probing = probingPlanId == card.plan.id,
                onView = { onView(card) },
                onExport = { onExport(card) },
                onEditPlan = { onEditPlan(card.plan) },
                onProbe = { onProbe(card.plan) },
                onRemove = { onRemove(card) }
            )
        }

        if (notOn.isNotEmpty()) {
            item {
                SectionCard(title = "Also in the household") {
                    Text(
                        "${personName ?: "This person"} isn't on ${
                            if (notOn.size == 1) "this policy" else "these policies"
                        } yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    notOn.forEach { plan ->
                        ListItem(
                            headlineContent = { Text(plan.displayName) },
                            supportingContent = {
                                Text(plan.coverageKind.label, style = MaterialTheme.typography.bodySmall)
                            },
                            trailingContent = {
                                TextButton(onClick = { onJoinPlan(plan) }) { Text("Add to card") }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverageCardRow(
    card: CoverageCard,
    images: CardImageStore,
    probing: Boolean,
    onView: () -> Unit,
    onExport: () -> Unit,
    onEditPlan: () -> Unit,
    onProbe: () -> Unit,
    onRemove: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        card.plan.carrierName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    val subtitle = listOfNotNull(
                        card.plan.planName?.ifBlank { null },
                        card.plan.planType.takeIf { it != PlanType.OTHER }?.label,
                        card.plan.coverageKind.takeIf { it != CoverageKind.MEDICAL }?.label
                    ).joinToString(" · ")
                    if (subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                CoverageBadge(card.coverage.status)
            }

            // Masked in the list, in full on the card. The list is what somebody else can read over
            // your shoulder; the card is the thing you opened on purpose.
            card.membership.maskedMemberId?.let { masked ->
                Text("Member $masked", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                card.coverage.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            DirectoryLine(plan = card.plan, probing = probing, onProbe = onProbe)

            if (card.hasPhotos) {
                CardThumbnails(card, images)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = onView) { Text("Card") }
                TextButton(onClick = onExport) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("PDF")
                }
                TextButton(onClick = onEditPlan) { Text("Plan") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}

/** What Health knows about the plan's directory — and the one button that changes it. */
@Composable
private fun DirectoryLine(plan: InsurancePlan, probing: Boolean, onProbe: () -> Unit) {
    val text = when {
        !plan.hasDirectory ->
            "No provider directory recorded. Add its address to check doctors against this plan."
        plan.directoryStatus == DirectoryOutcome.NOT_CONFIGURED ->
            "Directory address recorded — not checked yet."
        plan.directoryStatus == DirectoryOutcome.REACHABLE ->
            "Directory reachable" + (plan.directoryCheckedAt?.let { ", checked ${formatStamp(it)}" } ?: "")
        else -> plan.directoryDetail ?: plan.directoryStatus.label
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (probing) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else if (plan.hasDirectory) {
            TextButton(onClick = onProbe) { Text("Test") }
        }
    }
}

@Composable
private fun CardThumbnails(card: CoverageCard, images: CardImageStore) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOfNotNull(card.frontImagePath, card.backImagePath).forEach { path ->
            rememberCardImage(images, path)?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = "Insurance card photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.height(52.dp).weight(1f)
                )
            }
        }
    }
}

@Composable
private fun CoverageBadge(status: CoverageStatus) {
    val color = when (status) {
        CoverageStatus.ENDED -> MaterialTheme.colorScheme.error
        CoverageStatus.ENDING_SOON, CoverageStatus.NOT_STARTED -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.small, color = color.copy(alpha = 0.12f)) {
        Text(
            status.label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
