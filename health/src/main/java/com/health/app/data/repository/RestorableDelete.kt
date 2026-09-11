package com.health.app.data.repository

/**
 * A delete that can be put back exactly as it was.
 *
 * Health's records are typed once, often at 3am, and mostly cannot be reconstructed: nobody
 * remembers what the thermometer said on Tuesday. A delete button that fires on one tap — which is
 * every delete button this app had — is therefore the one control here that can destroy something
 * the household cannot get back. This is the answer: the row is read before it is dropped, and the
 * caller is handed the way to put it back for as long as the offer is on screen.
 *
 * **Only for deletes that restore *exactly*.** A repository method returns one of these when
 * re-running [undo] leaves the database indistinguishable from before — the same row, under the same
 * id, with every cross-link that pointed at it still pointing at it. A delete that also destroys a
 * file, prunes a cache, or unlinks its children is *not* restorable in that sense and must not
 * pretend to be: those ask first instead (see `ui/common/ConfirmDeleteDialog`). Offering an undo
 * that quietly puts back less than it took is worse than offering none, because the household stops
 * checking.
 *
 * The restore runs the inverse of whatever the delete did, not just the row write — a deleted dose
 * put its stock back in the cabinet, so restoring it draws that stock again.
 */
class RestorableDelete internal constructor(private val restore: suspend () -> Unit) {

    /** Put it back. Called once, from the undo the offer carried — see `ui/common/Undo`. */
    suspend fun undo() = restore()
}
