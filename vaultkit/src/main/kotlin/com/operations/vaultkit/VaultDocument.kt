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
 * [version] is the document's own format, separate from the envelope's ([VaultEnvelope.FORMAT_VERSION]):
 * the layout of the encrypted file and the shape of the JSON inside it change for different reasons
 * and at different times.
 */
data class VaultDocument(
    val version: Int = DOCUMENT_VERSION,
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

    /** Add or replace [item], stamping the document's own clock. */
    fun upsert(item: VaultItem, now: Long): VaultDocument =
        copy(
            items = items.filterNot { it.id == item.id } + item,
            updatedAt = now
        )

    /**
     * Delete [id] — as a tombstone, not a removal.
     *
     * A removed row is a row that comes back the moment an older copy of the vault is merged in, and
     * merging an older copy in is precisely what a restore does. The tombstone keeps the id and the
     * time and drops everything else it held, so what survives is the fact of the deletion rather
     * than the secret.
     */
    fun delete(id: String, now: Long): VaultDocument {
        val existing = items.firstOrNull { it.id == id } ?: return this
        return copy(
            items = items.filterNot { it.id == id } + existing.tombstone(now),
            updatedAt = now
        )
    }

    companion object {
        const val DOCUMENT_VERSION = 1

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

    /** Strip everything but the identity and the fact of the deletion. */
    fun tombstone(now: Long): VaultItem =
        VaultItem(id = id, createdAt = createdAt, updatedAt = now, deletedAt = now)

    /** Does this item match [query]? Never looks at [secret] — see the note in [VaultSearch]. */
    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return title.lowercase().contains(q) ||
            username.lowercase().contains(q) ||
            url.lowercase().contains(q) ||
            note.lowercase().contains(q) ||
            tags.any { it.lowercase().contains(q) } ||
            ref?.lowercase()?.contains(q) == true ||
            fields.any { !it.secret && it.value.lowercase().contains(q) } ||
            fields.any { it.name.lowercase().contains(q) }
    }
}

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
