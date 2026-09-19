package com.operations.vaultkit

/**
 * What a vault holds, once it is open: a list of items and nothing else.
 *
 * This is the *plaintext* — the only form of it that ever exists is in memory, between an unlock and
 * a lock. It is a plain immutable data class rather than a database because of what that costs: a
 * Room database is a file, a file has a journal, a journal has yesterday's rows in it, and a
 * password manager whose deleted passwords are recoverable from a WAL segment is not a password
 * manager. Every change here rewrites the whole sealed body, which for a household's worth of
 * secrets is a few kilobytes and buys the guarantee that what is on disk is exactly what is in the
 * document and nothing that used to be.
 *
 * That last sentence is also why [VaultItem.history] is safe and a journal would not be: a previous
 * password kept here is kept *on purpose*, inside the sealed body, bounded, visible on the screen
 * that owns the item, and deletable. The objection to a WAL was never "old passwords exist" — it was
 * that they exist where nobody decided to put them and nobody can get them out.
 *
 * [version] is the document's own format, separate from the envelope's ([VaultEnvelope.FORMAT_VERSION]):
 * the layout of the encrypted file and the shape of the JSON inside it change for different reasons
 * and at different times.
 */
data class VaultDocument(
    val version: Int = BASELINE_VERSION,
    val items: List<VaultItem> = emptyList(),
    /** When the document was last written, by the writer's clock. Used by [VaultMerge]. */
    val updatedAt: Long = 0L
) {

    /** Everything not tombstoned, newest first — what every screen actually lists. */
    val live: List<VaultItem> get() = items.filter { !it.isDeleted }

    fun item(id: String): VaultItem? = items.firstOrNull { it.id == id && !it.isDeleted }

    /** The item filed under [ref], if an app has mirrored one there. */
    fun managed(ref: SecretRef): VaultItem? {
        val address = ref.format()
        return items.firstOrNull { it.ref == address && !it.isDeleted }
    }

    /**
     * Add or replace [item], stamping the document's own clock — and keeping the password it
     * replaced, if it replaced one.
     *
     * The history is recorded *here* rather than at the editor, because this is the one funnel every
     * change goes through: the item screen's Save, and a merge that resolves to a newer copy. A
     * rule written at a call site is a rule the next call site forgets.
     */
    fun upsert(item: VaultItem, now: Long): VaultDocument {
        val previous = items.firstOrNull { it.id == item.id }
        return copy(
            items = items.filterNot { it.id == item.id } + item.keepingReplaced(previous, now),
            updatedAt = now
        ).stamped()
    }

    /**
     * Delete [id] — as a tombstone, not a removal.
     *
     * A removed row is a row that comes back the moment an older copy of the vault is merged in, and
     * merging an older copy in is precisely what a restore does. The tombstone keeps the id and the
     * time and drops everything else it held, so what survives is the fact of the deletion rather
     * than the secret — the previous passwords, the second-factor seed and the passkey's private
     * key included.
     */
    fun delete(id: String, now: Long): VaultDocument {
        val existing = items.firstOrNull { it.id == id } ?: return this
        return copy(
            items = items.filterNot { it.id == id } + existing.tombstone(now),
            updatedAt = now
        ).stamped()
    }

    /**
     * Set [version] to the lowest one a reader must understand in order to read this document
     * faithfully.
     *
     * The guard it feeds is in `VaultStore.mutateBlocking`, which refuses to *write* a document
     * whose version is newer than this build understands — because Gson drops what it does not know,
     * so an older build saving a newer document would silently delete whatever the newer one added.
     *
     * Two properties are worth stating, because both are deliberate:
     *
     *  - **It is computed from the contents, not asserted**, and per feature rather than per
     *    release. A vault of plain logins is still version 1 and a build from before any of this
     *    can open *and edit* it; one that has gained a second factor is version 2, which the build
     *    that introduced those can still write; only a vault holding a passkey is version 3.
     *    Stamping every document at the newest number on the day a feature shipped would have
     *    locked households out of their own vault on any phone running the previous build, in
     *    exchange for nothing.
     *  - **It only ever goes up.** Deleting the last TOTP seed does not walk the version back down.
     *    A version that flapped would be a version that meant nothing, and the cost of leaving it
     *    high is that an older build declines to write a file it could technically have handled.
     */
    fun stamped(): VaultDocument = copy(version = maxOf(version, requiredVersion))

    private val requiredVersion: Int
        get() = items.maxOfOrNull { it.requiredVersion } ?: BASELINE_VERSION

    companion object {
        /** Logins, cards, notes — the format before any of the later additions. */
        const val BASELINE_VERSION = 1

        /** Second factors and password history. */
        const val VERSION_WITH_SECOND_FACTORS = 2

        /** Passkeys. What this build writes, when the contents need it. See [stamped]. */
        const val DOCUMENT_VERSION = 3

        /** What a brand-new vault contains. */
        val EMPTY = VaultDocument()
    }
}

/**
 * One thing kept in the vault.
 *
 * Two kinds of item live side by side here and the difference is [ref], not the shape:
 *
 *  - **What somebody typed in** — the household's own logins, cards, licence keys, the wifi
 *    password, the safe combination. [ref] is null; nothing but a person ever writes them.
 *  - **What an app mirrored** — Finance's Plaid secret, Citation's catalogue sign-in. [ref] is the
 *    [SecretRef] the owning app reads it back by, and [managedBy] says which app that is. These
 *    exist so that the thing which dies with a restore today (see the comment at the top of
 *    `FinanceBackupContributor`) survives one, and they are shown, searched and exported like any
 *    other item — the vault does not keep a second, secret class of secret.
 *
 * [secret] is the one value the item exists to protect; [fields] are everything else, each marked
 * for whether it should be masked. Splitting them that way means every screen knows what to hide
 * without a per-kind rule, and every audit knows what to score.
 */
data class VaultItem(
    val id: String,
    val kind: VaultItemKind = VaultItemKind.LOGIN,
    val title: String = "",
    val username: String = "",
    val secret: String = "",
    val fields: List<VaultField> = emptyList(),
    val note: String = "",
    val url: String = "",
    val tags: List<String> = emptyList(),
    /**
     * The second factor, for an item that has one. Null for the great majority.
     *
     * A seed, not a code: the code is a function of this and the clock, computed on demand and never
     * written anywhere (see [Totp]).
     */
    val totp: TotpConfig? = null,
    /**
     * What [secret] used to be, newest first, capped at [HISTORY_LIMIT].
     *
     * ## Why a password manager keeps the password you just replaced
     *
     * Because the most common way to be locked out of an account is not forgetting a password, it is
     * *changing* one: the site accepted the new value on a form and stored something else, or the
     * change never committed, or the app on the tablet is still signed in with the old one and will
     * ask for it the next time it is opened. Every one of those is recoverable in ten seconds by
     * somebody who can see what the password was ten minutes ago, and is an account-recovery phone
     * call for somebody who cannot.
     *
     * ## And why the mirrored credentials do not get one
     *
     * An app's rotated token is dead the moment it rotates — no bank will take the previous access
     * token, so keeping it would be storing a secret with no use for it. Worse, those rotate on a
     * schedule rather than when a person decides something, so a history of them would be an
     * unbounded churn of useless plaintext inside the sealed body. [keepingReplaced] drops it for
     * anything with a [ref] rather than leaving that to each writer to remember.
     */
    val history: List<VaultSecretVersion> = emptyList(),
    /**
     * A passkey, for an item that is one.
     *
     * An item holds a passkey *or* a password, in practice — the two are alternative ways to sign in
     * to the same site and a site that offers both is a site you use one of. The field is separate
     * rather than a variant of [secret] because a passkey is a key pair and a set of identifiers
     * rather than a string, and flattening it into one would mean every screen that shows a password
     * having to know not to.
     */
    val passkey: VaultPasskey? = null,
    /** The [com.operations.backupkit.AppId] key of the app that owns this, for mirrored secrets. */
    val managedBy: String? = null,
    /** The [SecretRef] address, for mirrored secrets. Null for anything a person typed in. */
    val ref: String? = null,
    val favourite: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Set when the item is deleted; the row stays so a merge cannot resurrect it. */
    val deletedAt: Long? = null
) {

    val isDeleted: Boolean get() = deletedAt != null

    val isManaged: Boolean get() = ref != null

    val hasTotp: Boolean get() = totp != null

    val hasPasskey: Boolean get() = passkey != null

    /**
     * The lowest document version that can hold this item without losing part of it.
     *
     * Feeds [VaultDocument.stamped], which is where the consequence is explained. It is a ladder
     * rather than a flag so that a vault of plain logins stays writable by the oldest build, and one
     * that has only gained a second factor stays writable by the build that introduced those.
     */
    val requiredVersion: Int
        get() = when {
            passkey != null -> VaultDocument.DOCUMENT_VERSION
            totp != null || history.isNotEmpty() -> VaultDocument.VERSION_WITH_SECOND_FACTORS
            else -> VaultDocument.BASELINE_VERSION
        }

    /** Strip everything but the identity and the fact of the deletion. */
    fun tombstone(now: Long): VaultItem =
        VaultItem(id = id, createdAt = createdAt, updatedAt = now, deletedAt = now)

    /**
     * This item, with [previous]'s password prepended to its history if it replaced one.
     *
     * Four cases record nothing, and each is a case where there is no *replacement* to speak of: a
     * mirrored credential (see [history]), an item that did not exist before, one being brought back
     * from a tombstone, and a password that is unchanged or that was blank. The last matters more
     * than it looks — filling in the empty secret on a stub somebody started last year should not
     * file an empty string as a password they once used.
     */
    fun keepingReplaced(previous: VaultItem?, now: Long): VaultItem {
        if (isManaged) return if (history.isEmpty()) this else copy(history = emptyList())
        if (previous == null || previous.isDeleted) return this
        if (previous.secret.isEmpty() || previous.secret == secret) return this
        return copy(
            history = (listOf(VaultSecretVersion(previous.secret, now)) + history).take(HISTORY_LIMIT)
        )
    }

    /** Does this item match [query]? Never looks at [secret] — see the note in [VaultSearch]. */
    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        // [secret], [history], [totp] and [passkey] are absent from this list on purpose and the
        // absence is tested. A previous password is a password, a second-factor seed is the one
        // secret here whose theft is silent, and a passkey's private key is the credential itself —
        // none goes anywhere near a text field somebody types into. A passkey is still findable,
        // because the item it lives on is titled and addressed after the site it belongs to.
        return title.lowercase().contains(q) ||
            username.lowercase().contains(q) ||
            url.lowercase().contains(q) ||
            note.lowercase().contains(q) ||
            tags.any { it.lowercase().contains(q) } ||
            ref?.lowercase()?.contains(q) == true ||
            fields.any { !it.secret && it.value.lowercase().contains(q) } ||
            fields.any { it.name.lowercase().contains(q) }
    }

    companion object {
        /**
         * How many previous passwords an item keeps.
         *
         * Ten, which covers every real use of this — the last one, and the one before it when
         * somebody changed it twice in a panic — while staying small enough that the answer to "how
         * much old plaintext is in this vault" is bounded and statable. It is a cap rather than an
         * age limit because a password changed once in six years has exactly one predecessor worth
         * keeping and no clock should throw it away.
         */
        const val HISTORY_LIMIT = 10
    }
}

/**
 * A password this item used to have, and when it stopped having it.
 *
 * [replacedAt] is the moment of the change rather than of the original setting, because the question
 * somebody asks a history is always "what was it before Tuesday?".
 */
data class VaultSecretVersion(
    val secret: String,
    val replacedAt: Long
)

/** One extra value on an item. [secret] decides whether it is masked, copied carefully, and audited. */
data class VaultField(
    val name: String,
    val value: String,
    val secret: Boolean = false
)

/**
 * What sort of thing an item is.
 *
 * The list is short and closed on purpose. A vault that lets you invent categories is a vault where
 * half the logins are filed under something nobody remembers choosing; tags do that job better, and
 * the kind exists only to decide what the editor asks for and what the audit expects to find.
 */
enum class VaultItemKind(val key: String, val label: String, val secretLabel: String) {
    LOGIN("login", "Login", "Password"),
    PASSKEY("passkey", "Passkey", "Key"),
    CARD("card", "Card", "Number"),
    API_KEY("api-key", "API key", "Key"),
    NOTE("note", "Secure note", "Contents"),
    IDENTITY("identity", "Identity", "Number"),
    WIFI("wifi", "Wi-Fi", "Password"),
    OTHER("other", "Other", "Secret");

    companion object {
        fun fromKey(key: String?): VaultItemKind = entries.firstOrNull { it.key == key } ?: OTHER
    }
}
