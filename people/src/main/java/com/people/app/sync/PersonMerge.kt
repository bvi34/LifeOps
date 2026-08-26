package com.people.app.sync

/**
 * What happens when both peers have edited the same person.
 *
 * The rule, in one sentence: **the newer record wins field by field, but a blank never beats a
 * value.**
 *
 * Whole-record last-write-wins would be simpler and is what most sync seams reach for first. It is
 * also wrong here, and wrong in a way that shows up immediately: LifeOps mints people from calendar
 * attendees, so it holds an email and usually nothing else; you type birth dates and phone numbers
 * into People. Under record-LWW, whichever app you touched last silently erases the other's half of
 * the person. Merging per field, with the newer record given precedence only where it actually says
 * something, keeps both halves — and still converges, because every peer applies the same rule to
 * the same pair and gets the same answer.
 *
 * Deletion is the other place a naive rule does real damage, so it is asymmetric on purpose: a
 * [PersonPacket.deleted] packet **archives** the local row rather than removing it. A peer can
 * withdraw a person from its own list without deciding that the household's whole record of them
 * should stop existing. Un-deleting is just a later packet with `deleted = false`.
 */
object PersonMerge {

    /**
     * Merge [incoming] into [local]. Returns the record to store — which may be [local] unchanged,
     * in which case [changed] is false and the caller can skip the write (and, importantly, skip
     * stamping a new version that would echo back across the seam forever).
     */
    fun merge(local: PersonPacket, incoming: PersonPacket): Result {
        // A tombstone archives; it never removes, and it never rolls back a newer local edit.
        if (incoming.deleted && incoming.updatedAt >= local.updatedAt) {
            val archived = local.copy(archived = true, updatedAt = maxOf(local.updatedAt, incoming.updatedAt))
            return Result(archived, changed = archived != local)
        }

        val newer = if (incoming.updatedAt > local.updatedAt) incoming else local
        val older = if (newer === incoming) local else incoming

        val merged = PersonPacket(
            // Identity converges on the lower key, and this is the only rule here that is about
            // agreement rather than content.
            //
            // Two peers that bound by email or name are holding the same human under two invented
            // keys. Leaving each with its own works only for as long as the *name* keeps matching —
            // rename the person on one side and the next round binds nothing and creates a
            // duplicate. Simply adopting the incoming key doesn't fix it either: both sides would
            // adopt the other's and swap, round after round. Taking the lower of the two is
            // deterministic, so both peers reach the same key from either direction and stay there.
            personKey = minOf(local.personKey, incoming.personKey),
            name = pick(newer.name.ifBlank { null }, older.name.ifBlank { null }) ?: local.name,
            relationship = pick(newer.relationship, older.relationship),
            birthDate = pick(newer.birthDate, older.birthDate),
            email = pick(newer.email, older.email),
            phone = pick(newer.phone, older.phone),
            note = pick(newer.note, older.note),
            // The household flag follows the same rule as the text fields rather than `archived`'s,
            // because unlike `archived` it is not a state every peer holds an answer to: LifeOps has
            // no column for it and publishes null. Under "the newer record's answer is the answer",
            // any LifeOps edit would un-flag the person and Health would stop being offered them.
            household = pickFlag(newer.household, older.household),
            // Archived is a state, not a field to fill in: the newer record's answer is the answer.
            archived = newer.archived,
            updatedAt = maxOf(local.updatedAt, incoming.updatedAt),
            deleted = false
        )
        return Result(merged, changed = merged != local)
    }

    data class Result(val merged: PersonPacket, val changed: Boolean)

    /** The preferred value if it says anything, else the fallback if *it* does, else null. */
    private fun pick(preferred: String?, fallback: String?): String? =
        preferred?.trim()?.takeIf { it.isNotEmpty() } ?: fallback?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The same rule for a flag: the preferred record's answer if it has one, else the fallback's.
     * `false` is an answer — only `null` ("no opinion") defers.
     */
    private fun pickFlag(preferred: Boolean?, fallback: Boolean?): Boolean? = preferred ?: fallback
}
