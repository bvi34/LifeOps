package com.people.app.sync

/**
 * Deciding whether an arriving packet is somebody we already have.
 *
 * This is the People seam's answer to `:core`'s `BindOrCreate`, and it exists for the same reason:
 * two peers will independently invent a row for the same human. LifeOps mints one the moment a
 * Google Calendar attendee turns up; you type the same person into People by hand an hour later.
 * Without binding, sync's contribution to the household is a second copy of everyone.
 *
 * Three rungs, strongest first — and it stops at the first that matches:
 *
 *  1. **The shared key.** Already linked; nothing to decide.
 *  2. **Email**, case-insensitively. The one field in a household roster that is genuinely an
 *     identifier rather than a label.
 *  3. **A normalized name.** Case, spacing, punctuation and accents folded away. This is a *guess*,
 *     and it is the last rung on purpose: two people in one household really can share a first
 *     name, so the match is only allowed when the local candidate has no email of its own to
 *     contradict it — an "Alex" with no email may be the arriving "alex", but an Alex with a
 *     different email address is a different Alex.
 */
object PersonBinder {

    /** A local row, reduced to what binding needs to look at. */
    data class Candidate(
        val localId: String,
        val personKey: String?,
        val name: String,
        val email: String? = null
    )

    sealed interface Decision {
        /** The packet belongs to this local row; adopt its key if we hadn't one. */
        data class Bind(val localId: String, val reason: Reason) : Decision

        /** Nobody here is this person; the caller creates a row carrying the packet's key. */
        data object Create : Decision
    }

    enum class Reason { KEY, EMAIL, NAME }

    fun bind(packet: PersonPacket, candidates: List<Candidate>): Decision {
        candidates.firstOrNull { it.personKey != null && it.personKey == packet.personKey }
            ?.let { return Decision.Bind(it.localId, Reason.KEY) }

        val email = packet.email.normalizedEmail()
        if (email != null) {
            candidates.firstOrNull { it.email.normalizedEmail() == email }
                ?.let { return Decision.Bind(it.localId, Reason.EMAIL) }
        }

        val name = normalizeName(packet.name)
        if (name.isNotEmpty()) {
            candidates.firstOrNull { candidate ->
                normalizeName(candidate.name) == name &&
                    // Don't bind over a contradiction: a candidate whose own email differs from the
                    // packet's is somebody else who happens to share a name.
                    (candidate.email.normalizedEmail() == null || email == null)
            }?.let { return Decision.Bind(it.localId, Reason.NAME) }
        }

        return Decision.Create
    }

    /**
     * Fold a name to its comparable shape: lower-cased, accents stripped, punctuation dropped,
     * runs of whitespace collapsed. "Dr. José  García-López" and "dr jose garcia lopez" are the
     * same person typed by two different people in two different moods.
     */
    fun normalizeName(raw: String): String =
        java.text.Normalizer.normalize(raw.trim().lowercase(), java.text.Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .replace(PUNCTUATION, " ")
            .replace(WHITESPACE, " ")
            .trim()

    private fun String?.normalizedEmail(): String? =
        this?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val PUNCTUATION = Regex("[^\\p{L}\\p{Nd} ]+")
    private val WHITESPACE = Regex("\\s+")
}
