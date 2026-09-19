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
 *  - **Or take the handover the platform now defines.** Credential Exchange ([CredentialExchange])
 *    is the sanctioned version of the same act: the household picks another credential manager from
 *    a system selector, authenticates to *it*, and it hands its vault across on the device. No file
 *    exists at any point. It is the best route where it is offered, and it is offered by more
 *    managers every year.
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
        GENERIC_CSV("a password export"),

        /**
         * Not a file at all: the payload of a Credential Exchange transfer, handed over by another
         * credential manager on this phone. The exporter names itself, so [Read.from] usually has a
         * better label than this one — see [CredentialExchange].
         */
        CREDENTIAL_EXCHANGE("another password app")
    }

    /** One row that did not become an item, and why — shown as a count and a list, never hidden. */
    data class Skipped(val what: String, val why: String)

    /** What a file — or a transfer — turned out to hold. [items] have not met the vault yet. */
    data class Read(
        val format: Format,
        val items: List<VaultItem>,
        val skipped: List<Skipped> = emptyList(),
        /**
         * What the source called itself, where it said.
         *
         * Only a Credential Exchange transfer knows this, because only there does the other app
         * get to speak: it names itself in the payload. A file has no such claim to make and the
         * format's own label is the honest answer.
         */
        val from: String? = null
    )

    /**
     * What a candidate would do to the vault.
     *
     * The three in the middle are the answer to the question an import cannot dodge: this account
     * is already here with a *different* password, so which of the two is the real one? See
     * [verdictFor], where that is decided from evidence rather than from a shrug.
     */
    enum class Verdict {
        /** Nothing here is filed under this address and username. */
        NEW,

        /**
         * The same password, plus something this vault does not have — a passkey, a second factor.
         * Nothing is overwritten; the item gains what it was missing.
         */
        ADDS,

        /**
         * The import's copy is demonstrably the current one, so it becomes the password and the
         * one here is kept as a previous password.
         */
        REPLACES,

        /**
         * The import's copy is demonstrably the *older* one. The password here is left alone and
         * the incoming one is filed as a previous password, which is where it belongs and where it
         * is worth having: the account somebody is locked out of is usually the one whose password
         * changed on one device and not the other.
         */
        PREVIOUS,

        /** Two passwords and nothing to tell them apart. The household decides; nothing is ticked. */
        UNDECIDED,

        /** The same account, the same password, nothing to add. */
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

        /**
         * Ticked when the plan is first shown. See [Plan].
         *
         * Everything that is evidently right is ticked; the one case with a real choice in it is
         * not. [Verdict.PREVIOUS] is ticked despite touching an existing item because it only ever
         * *adds* to that item's history — it cannot cost anybody a working password.
         */
        val selectedByDefault: Boolean
            get() = verdict != Verdict.UNDECIDED && verdict != Verdict.ALREADY_HERE
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
        val skipped: List<Skipped> = emptyList(),
        val from: String? = null
    ) {

        /** What to call the source on screen: its own name where it gave one. */
        val sourceLabel: String get() = from ?: format.label

        fun count(verdict: Verdict): Int = entries.count { it.verdict == verdict }

        val newCount: Int get() = count(Verdict.NEW)
        val alreadyHereCount: Int get() = count(Verdict.ALREADY_HERE)

        val defaultSelection: Set<String>
            get() = entries.filter { it.selectedByDefault }.map { it.key }.toSet()

        val isEmpty: Boolean get() = entries.isEmpty()
    }

    /** What an import did, so the screen can say it in a sentence. */
    data class Outcome(
        val document: VaultDocument,
        val added: Int,
        val updated: Int,
        /** Items that kept their password and gained a previous one. See [Verdict.PREVIOUS]. */
        val recorded: Int = 0
    ) {
        val changed: Int get() = added + updated + recorded
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
                nothingToAdd(match, candidate) -> Entry(candidate, Verdict.ALREADY_HERE, match)
                else -> Entry(candidate, verdictFor(match, candidate), match)
            }
        }
        return Plan(read.format, entries, read.skipped, read.from)
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
        var recorded = 0

        for (entry in plan.entries) {
            if (entry.key !in selected) continue
            if (entry.verdict == Verdict.ALREADY_HERE) continue

            if (entry.verdict == Verdict.NEW) {
                result = result.upsert(stamped(entry.candidate, now), now)
                added++
                continue
            }

            // Re-read from the document being written rather than trusting the copy the plan was
            // built against: an app can have mirrored a credential, or another screen saved an
            // edit, in the time somebody spent reading the list. If the item has gone entirely
            // since then, the household still asked for this password — so it arrives as a new
            // item rather than as nothing at all.
            val current = entry.existing?.let { result.item(it.id) }
            if (current == null) {
                result = result.upsert(stamped(entry.candidate, now), now)
                added++
                continue
            }

            when (entry.verdict) {
                Verdict.PREVIOUS -> {
                    result = result.upsert(filedAsPrevious(current, entry.candidate, now), now)
                    recorded++
                }
                Verdict.ADDS -> {
                    result = result.upsert(keeping(current, entry.candidate), now)
                    updated++
                }
                // REPLACES, and UNDECIDED once somebody has ticked it — which is them saying the
                // import is the current copy, the same claim REPLACES makes from evidence.
                else -> {
                    result = result.upsert(replacing(current, entry.candidate, now), now)
                    updated++
                }
            }
        }
        return Outcome(result, added, updated, recorded)
    }

    /**
     * Which of two passwords for one account is the current one.
     *
     * This is the question the household cannot be expected to answer for four hundred rows, and
     * the one an importer usually ducks by overwriting everything or by asking about each. There is
     * real evidence available, in this order:
     *
     *  1. **It is not a different password at all** — the import brings a passkey or a second
     *     factor for an account already here. Nothing to weigh.
     *  2. **This vault already knows the incoming password, as an old one.** It is in the item's
     *     history, which means it was replaced *here*. No date can outrank that.
     *  3. **The import knows this vault's password as an old one** — its own history carries what
     *     is current here, so its copy is the later one.
     *  4. **The dates.** Where both sides say when the password was last changed, the later one is
     *     the current one. This is the common case for a Credential Exchange transfer and for a
     *     1Password or Firefox export, all of which carry the field.
     *  5. **Otherwise, nobody knows** — and the plan says so rather than guessing, because the cost
     *     of being wrong is a working password replaced by a dead one.
     *
     * A zero timestamp means the source did not say, which is why the readers leave it at zero
     * rather than stamping the moment of the import: an undated export that claimed today's date
     * would win every comparison in step 4 while knowing nothing.
     */
    private fun verdictFor(current: VaultItem, candidate: VaultItem): Verdict = when {
        candidate.secret.isEmpty() || candidate.secret == current.secret -> Verdict.ADDS
        current.history.any { it.secret == candidate.secret } -> Verdict.PREVIOUS
        candidate.history.any { it.secret == current.secret } -> Verdict.REPLACES
        else -> byDate(current.updatedAt, candidate.updatedAt)
    }

    private fun byDate(mine: Long, theirs: Long): Verdict = when {
        mine <= 0 || theirs <= 0 || mine == theirs -> Verdict.UNDECIDED
        theirs > mine -> Verdict.REPLACES
        else -> Verdict.PREVIOUS
    }

    /** A candidate on its way in, with the clocks a file that did not carry any would want. */
    private fun stamped(candidate: VaultItem, now: Long): VaultItem = candidate.copy(
        createdAt = candidate.createdAt.takeIf { it > 0 } ?: now,
        updatedAt = candidate.updatedAt.takeIf { it > 0 } ?: now
    )

    /**
     * Does the vault's copy already have everything this candidate brings?
     *
     * A matching password is not enough on its own. A transfer from another credential manager can
     * arrive with a **passkey** or a **second factor** for an account this vault has held as a
     * plain password for years, and calling that "already here" would drop the one thing worth
     * importing. Nor is a password the vault has *seen* before worth importing again: one already
     * sitting in the item's history was recorded when it was replaced, and filing it a second time
     * would stack duplicates every time the same stale export is imported.
     */
    private fun nothingToAdd(current: VaultItem, candidate: VaultItem): Boolean {
        val known = candidate.secret.isEmpty() ||
            candidate.secret == current.secret ||
            current.history.any { it.secret == candidate.secret }
        return known &&
            (candidate.passkey == null || current.passkey != null) &&
            (candidate.totp == null || current.totp != null)
    }

    /**
     * The vault's copy, gaining whatever it was missing and keeping its password.
     *
     * Everything the household did to the item here survives — its id, tags, fields, favourite
     * star, and the password itself. What the import may supply is what is *absent*: a second
     * factor, a passkey, and any of the blanks. An import is not a handover of the vault back to
     * the app being left.
     */
    private fun keeping(current: VaultItem, candidate: VaultItem): VaultItem = current.copy(
        url = current.url.ifBlank { candidate.url },
        username = current.username.ifBlank { candidate.username },
        note = current.note.ifBlank { candidate.note },
        totp = current.totp ?: candidate.totp,
        // Never over the top of one that is already here: a passkey this vault holds is one it
        // issued or already adopted, and the site has its public half.
        passkey = current.passkey ?: candidate.passkey
    )

    /**
     * The same, with the import's password taking over.
     *
     * The password it replaced is kept, because this goes through [VaultDocument.upsert] like every
     * other change — which is exactly why that rule lives there rather than at each call site.
     */
    private fun replacing(current: VaultItem, candidate: VaultItem, now: Long): VaultItem =
        keeping(current, candidate).copy(secret = candidate.secret, updatedAt = now)

    /**
     * The vault's copy, keeping its password and gaining the import's as a previous one.
     *
     * The answer for a credential manager that is simply out of date — the browser still holding
     * what the password was before it was changed somewhere else. Throwing that away would be
     * reasonable; keeping it costs one line in a bounded history and answers the question a
     * password history exists for, which is *what was it before?*
     *
     * [VaultItem.updatedAt] is deliberately not moved. The password did not change today, and the
     * audit scores an item's age from that field — an import that touched it would report every
     * stale password in the vault as fresh.
     */
    private fun filedAsPrevious(current: VaultItem, candidate: VaultItem, now: Long): VaultItem {
        val kept = keeping(current, candidate)
        if (candidate.secret.isEmpty() || kept.history.any { it.secret == candidate.secret }) {
            return kept
        }
        // When it stopped being the password is not in the file; the best answer available is when
        // the one that displaced it was set.
        val replacedAt = current.updatedAt.takeIf { it > 0 } ?: now
        return kept.copy(
            history = (listOf(VaultSecretVersion(candidate.secret, replacedAt)) + kept.history)
                .take(VaultItem.HISTORY_LIMIT)
        )
    }

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
