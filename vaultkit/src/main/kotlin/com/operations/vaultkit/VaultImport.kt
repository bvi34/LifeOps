package com.operations.vaultkit

/**
 * Bringing in the passwords that are somewhere else.
 *
 * ## Why this belongs in a vault that refuses to talk to anybody
 *
 * The first thing anybody has to do with a new password manager is get their passwords into it, and
 * until they have, the vault is a nicer place to keep nothing. The honest ways to do that are two,
 * and this app can only have one of them:
 *
 *  - **Ask the other manager's server for them.** Every commercial importer works this way, and it
 *    is unavailable here on purpose — there is no `INTERNET` permission in this module's manifest
 *    and no HTTP client on its classpath, and that is the property the whole app is built around.
 *  - **Read the file the other manager already hands its owner.** Chrome, Edge, Brave, Firefox,
 *    Safari and the iCloud/Apple Passwords app all export a CSV; 1Password exports a CSV and its
 *    own `.1pux`. Every one of those files is produced locally, by software the household already
 *    trusts with these passwords, at their own request.
 *
 * So the import is a *file* import, and the one thing it costs is the thing this file says out loud
 * everywhere it can: for as long as that export exists, the household's entire password list is
 * sitting in plaintext in their Downloads folder, readable by anything with storage access. The
 * screen that runs this import says so before the picker opens and again after the import lands,
 * because the moment it is done is the moment that file should stop existing.
 *
 * ## What is here and what is in [CredentialCsv] / [OnePasswordExport]
 *
 * Those two turn bytes into candidate [VaultItem]s and nothing else — no vault, no decisions. This
 * file has the decisions: which format a file is, what each candidate would *do* to the vault it is
 * about to land in, and what applying a reviewed plan produces. A candidate that matches something
 * already in the vault is the whole reason there is a plan at all — the second import of the same
 * browser, and the household that has been typing passwords in by hand for a week before
 * remembering they had an export, are both ordinary, and neither should end with two rows per site.
 */
object VaultImport {

    /** Where a file came from, as far as the household is concerned. */
    enum class Format(val label: String) {
        CHROMIUM_CSV("Chrome, Edge or Brave"),
        FIREFOX_CSV("Firefox"),
        APPLE_CSV("Safari or Apple Passwords"),
        ONEPASSWORD_CSV("1Password"),
        ONEPASSWORD_1PUX("1Password"),
        GENERIC_CSV("a password export")
    }

    /** One row that did not become an item, and why — shown as a count and a list, never hidden. */
    data class Skipped(val what: String, val why: String)

    /** What a file turned out to hold. [items] carry fresh ids and have not met the vault yet. */
    data class Read(
        val format: Format,
        val items: List<VaultItem>,
        val skipped: List<Skipped> = emptyList()
    )

    /** What a candidate would do to the vault. */
    enum class Verdict {
        /** Nothing here is filed under this address and username. */
        NEW,

        /** Something is, and it holds a different password — importing replaces it. */
        CHANGED,

        /** Something is, and it already holds this exact password. Nothing to do. */
        ALREADY_HERE
    }

    /**
     * One candidate, and what it met.
     *
     * [key] is the candidate's own id, which is what a screen ticks and unticks — stable across the
     * life of a plan and meaningless afterwards, since applying the plan is what mints the item.
     */
    data class Entry(
        val candidate: VaultItem,
        val verdict: Verdict,
        val existing: VaultItem? = null
    ) {
        val key: String get() = candidate.id

        /** Ticked when the plan is first shown. See [Plan]. */
        val selectedByDefault: Boolean get() = verdict == Verdict.NEW
    }

    /**
     * A reviewed-but-not-applied import.
     *
     * The default selection is the conservative one and the difference matters: a **new** item takes
     * nothing away, so it is ticked; a **changed** one overwrites a password that is already in the
     * vault, and the export may well be the older copy — a browser that has not been opened since
     * March against a password changed in June. So those are shown, counted, and left for somebody
     * to tick deliberately. Nothing in an import happens on a default that costs a working password.
     */
    data class Plan(
        val format: Format,
        val entries: List<Entry>,
        val skipped: List<Skipped> = emptyList()
    ) {
        val newCount: Int get() = entries.count { it.verdict == Verdict.NEW }
        val changedCount: Int get() = entries.count { it.verdict == Verdict.CHANGED }
        val alreadyHereCount: Int get() = entries.count { it.verdict == Verdict.ALREADY_HERE }

        val defaultSelection: Set<String>
            get() = entries.filter { it.selectedByDefault }.map { it.key }.toSet()

        val isEmpty: Boolean get() = entries.isEmpty()
    }

    /** What an import did, so the screen can say it in a sentence. */
    data class Outcome(
        val document: VaultDocument,
        val added: Int,
        val updated: Int
    ) {
        val changed: Int get() = added + updated
    }

    /**
     * Work out what [read]'s candidates would do to [document].
     *
     * The match is on **address and username together**, which is the pair that identifies an
     * account rather than a site: two logins at the same bank are two items, and the same login
     * re-exported is one. [AutofillMatch.hostOf] does the address half, so `https://www.bank.com/login`
     * and `bank.com` are recognised as the same place — it is the same normalisation autofill
     * matches on, and having two answers to "is this the same site" in one app would be one too many.
     * An item with no address at all — a secure note, or a login somebody typed in without one — is
     * matched on its title instead, which is all it has.
     *
     * Items a person could not have typed in are never matched against: a mirrored credential
     * ([VaultItem.isManaged]) belongs to an app, is addressed by a [SecretRef] rather than a URL,
     * and a CSV row that happened to collide with one must not be allowed to rewrite a bank token.
     */
    fun plan(document: VaultDocument, read: Read): Plan {
        val existing = document.live.filterNot { it.isManaged }
        val byAddress = HashMap<String, VaultItem>()
        val byTitle = HashMap<String, VaultItem>()
        for (item in existing) {
            // Filed under its address where it has one, and under its title where it has none. The
            // asymmetry is the point: a candidate looks up both, so a login somebody typed in by
            // hand — titled `Bank`, no URL, because they never needed one — is found by the row
            // that knows the address. An item that *does* have an address is only ever matched on
            // it, so two sites the household happened to title the same are never merged into one.
            val address = addressKey(item)
            if (address != null) {
                byAddress.putIfAbsent(address, item)
            } else {
                titleKey(item)?.let { byTitle.putIfAbsent(it, item) }
            }
        }

        val entries = read.items.map { candidate ->
            val match = addressKey(candidate)?.let { byAddress[it] }
                ?: titleKey(candidate)?.let { byTitle[it] }
            when {
                match == null -> Entry(candidate, Verdict.NEW)
                match.secret == candidate.secret -> Entry(candidate, Verdict.ALREADY_HERE, match)
                else -> Entry(candidate, Verdict.CHANGED, match)
            }
        }
        return Plan(read.format, entries, read.skipped)
    }

    /**
     * Apply the entries of [plan] whose keys are in [selected].
     *
     * Two shapes of change, and the second is the one with a rule in it:
     *
     *  - A **new** item is added as it was parsed.
     *  - A **changed** one is *not* replaced by the candidate. The vault's own copy keeps its id,
     *    its tags, its extra fields, its favourite flag and everything else somebody did to it here;
     *    what the import supplies is the password, plus anything the vault's copy is missing — a
     *    second factor it has none of, a URL or a note left blank. Replacing the item wholesale
     *    would mean an import silently deleting the work of every edit made since the last one, and
     *    the household asked to bring a password in, not to hand the vault back to Chrome.
     *
     * The replaced password is kept, because the change goes through [VaultDocument.upsert] like
     * every other — which is exactly why that rule lives there rather than at each call site.
     */
    fun apply(
        document: VaultDocument,
        plan: Plan,
        selected: Set<String>,
        now: Long
    ): Outcome {
        var result = document
        var added = 0
        var updated = 0

        for (entry in plan.entries) {
            if (entry.key !in selected) continue
            when (entry.verdict) {
                Verdict.NEW -> {
                    result = result.upsert(stamped(entry.candidate, now), now)
                    added++
                }
                Verdict.CHANGED -> {
                    // Re-read from the document being written rather than trusting the copy the
                    // plan was built against: an app can have mirrored a credential, or another
                    // screen saved an edit, in the time somebody spent reading the list. If the
                    // item has gone entirely since then, the household still asked for this
                    // password — so it arrives as a new item rather than as nothing at all.
                    val current = entry.existing?.let { result.item(it.id) }
                    if (current == null) {
                        result = result.upsert(stamped(entry.candidate, now), now)
                        added++
                    } else {
                        result = result.upsert(merged(current, entry.candidate, now), now)
                        updated++
                    }
                }
                // Not offered, and ignored if a caller selects one anyway: writing an identical
                // item would bump its timestamp and tell the audit the password was changed today.
                Verdict.ALREADY_HERE -> Unit
            }
        }
        return Outcome(result, added, updated)
    }

    /** A candidate on its way in, with the clocks a file that did not carry any would want. */
    private fun stamped(candidate: VaultItem, now: Long): VaultItem = candidate.copy(
        createdAt = candidate.createdAt.takeIf { it > 0 } ?: now,
        updatedAt = candidate.updatedAt.takeIf { it > 0 } ?: now
    )

    /** The vault's copy of an item, taking the import's password and whatever it was missing. */
    private fun merged(current: VaultItem, candidate: VaultItem, now: Long): VaultItem = current.copy(
        secret = candidate.secret,
        url = current.url.ifBlank { candidate.url },
        username = current.username.ifBlank { candidate.username },
        note = current.note.ifBlank { candidate.note },
        totp = current.totp ?: candidate.totp,
        updatedAt = now
    )

    /**
     * What makes two items the same account: where it is, and who signs in to it.
     *
     * Null for an item with no usable address, which is then matched on its title instead — see
     * [plan], where the two halves are put together.
     */
    private fun addressKey(item: VaultItem): String? =
        AutofillMatch.hostOf(item.url)?.let { it + "\u0000" + item.username.trim().lowercase() }

    private fun titleKey(item: VaultItem): String? =
        item.title.trim().lowercase().takeIf { it.isNotEmpty() }
            ?.let { it + "\u0000" + item.username.trim().lowercase() }
}
