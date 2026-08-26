package com.health.app.ui.coverage

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.health.app.data.model.CareRole
import com.health.app.data.model.CareTeamMember
import com.health.app.data.model.InsurancePlan
import com.health.app.data.model.Provider
import com.health.app.logic.CoverageKind
import com.health.app.logic.Insurance
import com.health.app.logic.PlanType
import com.health.app.logic.ProviderDirectory
import com.health.app.ui.common.ChoiceRow

/**
 * The Care tab's forms.
 *
 * Two conventions run through all of them, and both come from what these records actually are:
 *
 *  - **Almost nothing is required.** A carrier's name for a policy, a name for a doctor, and that is
 *    the whole list. Cards differ, households copy down what they can find, and a form that demands
 *    a payer id before it will save is a form that gets abandoned with the card still in the drawer.
 *  - **Nothing is computed from what is typed.** These forms record what is printed on a card and
 *    what a receptionist said. They do not work out what is covered, what it costs, or whether a
 *    doctor is in network — the last of those is the directory's answer, kept as evidence with a
 *    date on it, and never a field somebody can tick.
 */

/**
 * Add or edit a policy — and, when it is new, put the person on it in the same gesture, which is what
 * actually happens when a card comes out of an envelope.
 *
 * The directory address gets a field of its own with an explanation, because it is the one thing here
 * a household will not find printed in an obvious place, and it is the thing the whole network-check
 * half of the tab depends on.
 */
@Composable
fun PlanDialog(
    existing: InsurancePlan? = null,
    personName: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (PlanDraft, MembershipDraft?) -> Unit = { _, _ -> },
    onSave: (InsurancePlan) -> Unit = {},
    onArchive: () -> Unit = {},
    onDelete: () -> Unit = {}
) {
    val editing = existing != null

    var carrier by remember { mutableStateOf(existing?.carrierName.orEmpty()) }
    var planName by remember { mutableStateOf(existing?.planName.orEmpty()) }
    var kind by remember { mutableStateOf(existing?.coverageKind ?: CoverageKind.MEDICAL) }
    var type by remember { mutableStateOf(existing?.planType ?: PlanType.OTHER) }
    var group by remember { mutableStateOf(existing?.groupNumber.orEmpty()) }
    var payerId by remember { mutableStateOf(existing?.payerId.orEmpty()) }
    var rxBin by remember { mutableStateOf(existing?.rxBin.orEmpty()) }
    var rxPcn by remember { mutableStateOf(existing?.rxPcn.orEmpty()) }
    var rxGroup by remember { mutableStateOf(existing?.rxGroup.orEmpty()) }
    var memberPhone by remember { mutableStateOf(existing?.memberServicesPhone.orEmpty()) }
    var nursePhone by remember { mutableStateOf(existing?.nurseLinePhone.orEmpty()) }
    var effective by remember { mutableStateOf(existing?.effectiveDate.orEmpty()) }
    var ends by remember { mutableStateOf(existing?.endDate.orEmpty()) }
    var directoryUrl by remember { mutableStateOf(existing?.directoryUrl.orEmpty()) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }

    // The membership half, shown only when the plan is new — an edit is about the policy, and
    // burying somebody's member number in a form titled after the carrier is how it gets changed by
    // accident.
    var memberId by remember { mutableStateOf("") }
    var personCode by remember { mutableStateOf("") }
    var subscriber by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }
    var primary by remember { mutableStateOf(true) }

    val datesInvalid = (effective.isNotBlank() && Insurance.parseDate(effective) == null) ||
        (ends.isNotBlank() && Insurance.parseDate(ends) == null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing) "Plan" else "Add a card") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Field(carrier, { carrier = it }, "Insurer", required = true)
                Field(planName, { planName = it }, "Plan name (optional)")

                Text("What it covers", style = MaterialTheme.typography.labelMedium)
                ChoiceRow(CoverageKind.entries, kind, { kind = it }, { it.label })

                Text("Plan type", style = MaterialTheme.typography.labelMedium)
                ChoiceRow(PlanType.entries, type, { type = it }, { it.label })

                Field(group, { group = it }, "Group number")
                Field(payerId, { payerId = it }, "Payer ID")

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(effective, { effective = it }, "Effective (YYYY-MM-DD)", modifier = Modifier.weight(1f))
                    Field(ends, { ends = it }, "Ends (optional)", modifier = Modifier.weight(1f))
                }
                if (datesInvalid) {
                    Text(
                        "Dates go in as YYYY-MM-DD. Anything else is left off the card rather than guessed at.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Text("The back of the card", style = MaterialTheme.typography.labelMedium)
                Field(memberPhone, { memberPhone = it }, "Member services", keyboard = KeyboardType.Phone)
                Field(nursePhone, { nursePhone = it }, "Nurse line", keyboard = KeyboardType.Phone)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(rxBin, { rxBin = it }, "Rx BIN", modifier = Modifier.weight(1f))
                    Field(rxPcn, { rxPcn = it }, "Rx PCN", modifier = Modifier.weight(1f))
                }
                Field(rxGroup, { rxGroup = it }, "Rx Group")

                HorizontalDivider()
                Text("Provider directory", style = MaterialTheme.typography.labelMedium)
                Text(
                    "The address of the insurer's published provider directory — its FHIR endpoint. " +
                        "Look for \"provider directory API\", \"Plan-Net\" or \"interoperability\" on " +
                        "the insurer's site; it is a public address, and Health asks it about doctors " +
                        "only, never about anybody in this household.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Field(directoryUrl, { directoryUrl = it }, "Directory address (optional)", keyboard = KeyboardType.Uri)

                Field(note, { note = it }, "Note (optional)")

                if (!editing) {
                    HorizontalDivider()
                    Text(
                        "${personName ?: "This person"}'s details on this card",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Field(memberId, { memberId = it }, "Member ID")
                    Field(personCode, { personCode = it }, "Person code / dependent number")
                    Field(subscriber, { subscriber = it }, "Subscriber, if not them")
                    Field(relationship, { relationship = it }, "Relationship to subscriber")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = primary, onCheckedChange = { primary = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Primary coverage", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Text(
                    Insurance.CARD_DISCLAIMER,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = carrier.isNotBlank() && !datesInvalid,
                onClick = {
                    val draft = PlanDraft(
                        carrierName = carrier,
                        planName = planName,
                        coverageKind = kind,
                        planType = type,
                        groupNumber = group,
                        payerId = payerId,
                        rxBin = rxBin,
                        rxPcn = rxPcn,
                        rxGroup = rxGroup,
                        memberServicesPhone = memberPhone,
                        nurseLinePhone = nursePhone,
                        effectiveDate = effective,
                        endDate = ends,
                        directoryUrl = directoryUrl,
                        note = note
                    )
                    if (existing != null) {
                        onSave(
                            existing.copy(
                                carrierName = draft.carrierName,
                                planName = draft.planName,
                                coverageKind = draft.coverageKind,
                                planType = draft.planType,
                                groupNumber = draft.groupNumber,
                                payerId = draft.payerId,
                                rxBin = draft.rxBin,
                                rxPcn = draft.rxPcn,
                                rxGroup = draft.rxGroup,
                                memberServicesPhone = draft.memberServicesPhone,
                                nurseLinePhone = draft.nurseLinePhone,
                                effectiveDate = draft.effectiveDate,
                                endDate = draft.endDate,
                                directoryUrl = draft.directoryUrl,
                                note = draft.note
                            )
                        )
                    } else {
                        onConfirm(
                            draft,
                            MembershipDraft(
                                memberId = memberId,
                                personCode = personCode,
                                subscriberName = subscriber,
                                relationshipToSubscriber = relationship,
                                primaryCoverage = primary
                            )
                        )
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (editing) {
                    TextButton(onClick = onArchive) {
                        Text(if (existing?.archived == true) "Restore" else "Archive")
                    }
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

/** Put somebody on a policy the household already holds — one tap instead of retyping the card. */
@Composable
fun MembershipDialog(
    plan: InsurancePlan,
    personName: String,
    onDismiss: () -> Unit,
    onConfirm: (MembershipDraft) -> Unit
) {
    var memberId by remember { mutableStateOf("") }
    var personCode by remember { mutableStateOf("") }
    var subscriber by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }
    var effective by remember { mutableStateOf("") }
    var primary by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$personName on ${plan.carrierName}") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "The policy's own details — group number, phone numbers, directory — are already " +
                        "recorded. This is just $personName's number on it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Field(memberId, { memberId = it }, "Member ID")
                Field(personCode, { personCode = it }, "Person code / dependent number")
                Field(subscriber, { subscriber = it }, "Subscriber, if not them")
                Field(relationship, { relationship = it }, "Relationship to subscriber")
                // A child added to a family plan mid-year is covered from the day they were added,
                // not from the policy's January — so the member's own date wins where it is given.
                Field(effective, { effective = it }, "Covered from (optional, YYYY-MM-DD)")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = primary, onCheckedChange = { primary = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Primary coverage", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    MembershipDraft(
                        memberId = memberId,
                        personCode = personCode,
                        subscriberName = subscriber,
                        relationshipToSubscriber = relationship,
                        effectiveDate = effective,
                        primaryCoverage = primary
                    )
                )
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Add or edit a doctor.
 *
 * The NPI gets its own explanation because it is the field that decides whether a network check can
 * be definitive or is only ever a guess at a name — and because it is checked as it is typed, which
 * saves somebody discovering a transposed digit as a mysterious "not listed".
 */
@Composable
fun ProviderDialog(
    existing: Provider? = null,
    personName: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (ProviderDraft, CareRole) -> Unit = { _, _ -> },
    onSave: (Provider) -> Unit = {},
    onDelete: () -> Unit = {}
) {
    val editing = existing != null

    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var npi by remember { mutableStateOf(existing?.npi.orEmpty()) }
    var specialty by remember { mutableStateOf(existing?.specialty.orEmpty()) }
    var practice by remember { mutableStateOf(existing?.practiceName.orEmpty()) }
    var phone by remember { mutableStateOf(existing?.phone.orEmpty()) }
    var address by remember { mutableStateOf(existing?.addressLine.orEmpty()) }
    var website by remember { mutableStateOf(existing?.website.orEmpty()) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    var role by remember { mutableStateOf(CareRole.PRIMARY) }

    val npiBad = npi.isNotBlank() && !ProviderDirectory.isValidNpi(npi)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing) "Doctor" else "Add a doctor") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Field(name, { name = it }, "Name", required = true)
                Field(specialty, { specialty = it }, "Specialty")
                Field(practice, { practice = it }, "Practice or group")
                Field(phone, { phone = it }, "Phone", keyboard = KeyboardType.Phone)
                Field(address, { address = it }, "Address")
                Field(website, { website = it }, "Website", keyboard = KeyboardType.Uri)

                Field(
                    npi,
                    { npi = it },
                    "NPI (optional)",
                    keyboard = KeyboardType.Number,
                    isError = npiBad,
                    supporting = when {
                        npiBad -> "That isn't a valid NPI — the ten digits don't pass their own check digit."
                        else -> "The ten-digit national identifier, on prescriptions and after-visit " +
                            "summaries. With it, a directory check is exact; without it, a common " +
                            "surname can only ever come back as \"couldn't tell them apart\"."
                    }
                )

                if (!editing) {
                    Text(
                        "What they are to ${personName ?: "this person"}",
                        style = MaterialTheme.typography.labelMedium
                    )
                    ChoiceRow(CareRole.entries, role, { role = it }, { it.label })
                }

                Field(note, { note = it }, "Note (optional)")
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !npiBad,
                onClick = {
                    if (existing != null) {
                        onSave(
                            existing.copy(
                                name = name,
                                npi = npi,
                                specialty = specialty,
                                practiceName = practice,
                                phone = phone,
                                addressLine = address,
                                website = website,
                                note = note
                            )
                        )
                    } else {
                        onConfirm(
                            ProviderDraft(
                                name = name,
                                npi = npi,
                                specialty = specialty,
                                practiceName = practice,
                                phone = phone,
                                addressLine = address,
                                website = website,
                                note = note
                            ),
                            role
                        )
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (editing) TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

/** Which policy to check against, when the household holds more than one that could answer. */
@Composable
fun PickPlanDialog(
    plans: List<InsurancePlan>,
    onDismiss: () -> Unit,
    onPick: (InsurancePlan) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check against which plan?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (plans.isEmpty()) {
                    Text(
                        "None of the household's plans has a provider-directory address recorded yet. " +
                            "Add one to a plan, or record what the office told you by phone.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                plans.forEach { plan ->
                    ListItem(
                        headlineContent = { Text(plan.displayName) },
                        supportingContent = {
                            Text(plan.coverageKind.label, style = MaterialTheme.typography.bodySmall)
                        },
                        trailingContent = {
                            TextButton(onClick = { onPick(plan) }) { Text("Check") }
                        }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/**
 * Write down what somebody was told on the phone.
 *
 * Recorded in the same history as the directory's own answers, and labelled as what it is: the
 * verdict will read "confirmed by phone", never "in network". Somebody was told something once, quite
 * possibly by a person reading the same directory — worth dating and keeping, and not a published
 * listing.
 */
@Composable
fun RecordByPhoneDialog(
    member: CareTeamMember,
    plans: List<InsurancePlan>,
    onDismiss: () -> Unit,
    onConfirm: (InsurancePlan?, inNetwork: Boolean, note: String?) -> Unit
) {
    var plan by remember { mutableStateOf(plans.firstOrNull()) }
    var inNetwork by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What were you told?") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "About ${member.provider.name}. This is kept as somebody's word with a date on " +
                        "it — it never becomes a published listing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (plans.isNotEmpty()) {
                    Text("Which plan", style = MaterialTheme.typography.labelMedium)
                    ChoiceRow(plans, plan ?: plans.first(), { plan = it }, { it.carrierName })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = inNetwork, onCheckedChange = { inNetwork = it })
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (inNetwork) "Told they are in network" else "Told they are not in network",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Field(note, { note = it }, "Who said so, and when (optional)")
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(plan, inNetwork, note) }) { Text("Record") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The one text field these forms use.
 *
 * Written once because there are around thirty of them and a screen where half the labels sit above
 * the box and half inside it is a screen that looks like two people wrote it.
 */
@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    isError: Boolean = false,
    supporting: String? = null,
    keyboard: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(if (required) "$label *" else label) },
        singleLine = true,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.fillMaxWidth()
    )
}
