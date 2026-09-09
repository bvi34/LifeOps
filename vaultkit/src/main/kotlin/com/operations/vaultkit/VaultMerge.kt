package com.operations.vaultkit

/**
 * What to do when there are two vaults and there should be one.
 *
 * This exists for exactly one situation, and it is the situation this whole app was asked for: a
 * backup is restored onto a phone that already has a vault. The suite's other apps answer that by
 * swapping the file in wholesale, which is right for them — a restored `finance.db` replaces a
 * `finance.db` and the worst case is losing the transactions since the backup, which the next
 * refresh fetches again. It is *not* right here. A wholesale swap of a vault silently destroys every
 * password added since the backup was taken, and there is no provider to re-fetch them from.
 *
 * So a restore never overwrites a live vault (see `SecretsBackupContributor`); it hands the archived
 * one to the household to merge, and this is the merge.
 *
 * ## The rule
 *
 * Item by item, keyed on id, newest [VaultItem.updatedAt] wins — with one asymmetry: **a tombstone
 * only wins if it is genuinely newer**. Deleting is a decision; restoring an old copy of a vault
 * should not undo yesterday's deletion, and merging a vault where something was deleted should not
 * silently delete an item edited afterwards on this phone. Both directions fall out of comparing
 * timestamps and keeping the tombstone as a row rather than as an absence.
 *
 * Ties — the same id, the same millisecond, different contents — go to [into], the vault already on
 * the phone. Two different edits at the same millisecond is not a real scenario for a single-user
 * app; what *is* real is merging the same archive twice, and that has to be a no-op.
 *
 * ## What it does not do
 *
 * There is no field-level merge, no "newer wins field by field" of the kind People's sync seam uses
 * for a person. That rule exists there because two apps genuinely own different halves of one row.
 * Here one person edits one item, and a password stitched together from two versions of itself is a
 * password that works nowhere.
 */
object VaultMerge {

    /** What a merge did, so the screen can say it in a sentence rather than "done". */
    data class Outcome(
        val document: VaultDocument,
        val added: Int,
        val updated: Int,
        val deleted: Int,
        val unchanged: Int
    ) {
        val changed: Int get() = added + updated + deleted
    }

    /**
     * Merge [from] into [into] — [into] being the vault on this phone, [from] the one out of the
     * archive.
     */
    fun merge(into: VaultDocument, from: VaultDocument, now: Long): Outcome {
        val mine = into.items.associateBy { it.id }
        val theirs = from.items.associateBy { it.id }

        var added = 0
        var updated = 0
        var deleted = 0
        var unchanged = 0

        val merged = ArrayList<VaultItem>(mine.size + theirs.size)

        for ((id, ours) in mine) {
            val other = theirs[id]
            if (other == null) {
                merged += ours
                unchanged++
                continue
            }
            val winner = if (other.updatedAt > ours.updatedAt) other else ours
            merged += winner
            when {
                winner === ours || winner == ours -> unchanged++
                winner.isDeleted && !ours.isDeleted -> deleted++
                else -> updated++
            }
        }

        for ((id, other) in theirs) {
            if (mine.containsKey(id)) continue
            merged += other
            // An item that only the archive has *and* that the archive has already deleted is a
            // tombstone for something this phone never saw. It is carried across rather than
            // dropped: a third vault merged in later might still hold the live copy, and this is
            // what stops that resurrecting it.
            if (other.isDeleted) unchanged++ else added++
        }

        return Outcome(
            document = into.copy(
                items = merged.sortedBy { it.id },
                updatedAt = maxOf(now, into.updatedAt, from.updatedAt)
            ),
            added = added,
            updated = updated,
            deleted = deleted,
            unchanged = unchanged
        )
    }

    /**
     * Drop tombstones older than [olderThanMillis].
     *
     * Tombstones are small and kept for a long time on purpose — they are the only defence against a
     * two-year-old backup resurrecting a password that was deliberately thrown away — but they are
     * not kept forever. A year is well past the life of any archive somebody would restore, and a
     * vault that has been pruned is a vault where a truly ancient archive can add rows back; that is
     * the trade, and it is stated on the settings screen that offers it rather than done quietly on
     * a schedule.
     */
    fun prune(document: VaultDocument, now: Long, olderThanMillis: Long = YEAR_MILLIS): VaultDocument {
        val kept = document.items.filter { item ->
            val deletedAt = item.deletedAt ?: return@filter true
            now - deletedAt < olderThanMillis
        }
        return if (kept.size == document.items.size) document else document.copy(items = kept)
    }

    const val YEAR_MILLIS = 365L * 24 * 60 * 60 * 1000
}
