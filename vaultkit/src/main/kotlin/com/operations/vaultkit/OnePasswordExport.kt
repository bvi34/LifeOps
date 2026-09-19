package com.operations.vaultkit

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * 1Password's own export: the `export.data` document inside a `.1pux` file.
 *
 * ## Why this exists when 1Password also exports a CSV
 *
 * Because the CSV is lossy in the ways that matter most to somebody leaving. It carries a title, a
 * URL, a username, a password, a second factor and a note — and drops the card numbers, the licence
 * keys, the secure notes' extra fields, the second and third URL, the password history and every
 * custom field the household spent years putting in. A move that loses those is a move nobody
 * finishes, so they stay subscribed to the thing they were leaving.
 *
 * The `.1pux` is a zip with a JSON document in it — the unzipping is [ImportFile]'s job and this
 * file reads the JSON. The shape, elided to what is read here:
 *
 * ```
 * accounts[] → vaults[] → items[] → item
 *                                     uuid, favIndex, createdAt, updatedAt, state, categoryUuid
 *                                     overview { title, url, urls[], tags[] }
 *                                     details  { loginFields[], notesPlain, sections[], passwordHistory[] }
 * ```
 *
 * ## What is kept, and what a field becomes
 *
 * A vault item here has one [VaultItem.secret] and a list of [VaultField]s, so the mapping has to
 * decide which of a 1Password item's values is *the* secret. That is per category: a login's is its
 * password, a card's is its number, a note's is nothing at all. Everything else in the item becomes
 * a field, keeping the name it had and — this is the part with the consequence — keeping whether it
 * was **concealed**, which is what decides whether this app masks it, keeps it out of the search and
 * audits it. A CVV that arrived as an ordinary string would be a CVV shown in a list.
 *
 * Values in 1Password are typed — `{"concealed": "…"}`, `{"monthYear": 202601}`, `{"address": {…}}`
 * — and [flatten] is the one place that knows how each prints. A type it has never seen returns
 * null and the field is skipped rather than guessed at, because a field rendered as `{}` in
 * somebody's vault is worse than a field reported missing.
 *
 * ## What is deliberately not kept
 *
 * **Attachments and documents.** A `.1pux` carries files, and a vault whose sealed body can hold a
 * scanned passport is a vault whose every save rewrites several megabytes of ciphertext — see the
 * note at the top of [VaultDocument] on why the whole document is rewritten every time. Items with
 * attachments still import; the attachment is reported as left behind, by name, so nobody discovers
 * its absence a year later.
 *
 * **Trashed and archived items.** Both are decisions somebody made about that login, and an import
 * that hands them back has undone the filing rather than moved it.
 */
object OnePasswordExport {

    /**
     * Read the `export.data` JSON in [json].
     *
     * Returns null when this is not a 1Password export, which is how [ImportFile] tells a `.1pux`
     * from a zip somebody picked by mistake.
     */
    fun read(
        json: String,
        now: Long,
        newId: () -> String = { UUID.randomUUID().toString() }
    ): VaultImport.Read? {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() as? JsonObject ?: return null
        val accounts = root.array("accounts") ?: return null

        val items = ArrayList<VaultItem>()
        val skipped = ArrayList<VaultImport.Skipped>()

        for (account in accounts.objects()) {
            for (vault in account.array("vaults").objects()) {
                val vaultName = vault.obj("attrs")?.string("name").orEmpty()
                for (wrapper in vault.array("items").objects()) {
                    // The items sit one level down inside the format's own envelope; a couple of
                    // tools that write this format have emitted them flat, so both are accepted.
                    val item = wrapper.obj("item") ?: wrapper
                    convert(item, vaultName, now, newId, items, skipped)
                }
            }
        }

        if (items.isEmpty() && skipped.isEmpty()) return null
        return VaultImport.Read(VaultImport.Format.ONEPASSWORD_1PUX, items, skipped)
    }

    private fun convert(
        item: JsonObject,
        vaultName: String,
        now: Long,
        newId: () -> String,
        into: MutableList<VaultItem>,
        skipped: MutableList<VaultImport.Skipped>
    ) {
        val overview = item.obj("overview")
        val details = item.obj("details")
        val title = overview?.string("title").orEmpty().ifBlank {
            AutofillMatch.hostOf(overview?.string("url")) ?: "Untitled"
        }

        val state = item.string("state").orEmpty().lowercase()
        if (state == "trashed" || item.bool("trashed") == true) {
            skipped += VaultImport.Skipped(title, "in the trash there")
            return
        }
        if (state == "archived") {
            skipped += VaultImport.Skipped(title, "archived there")
            return
        }
        if (details == null) {
            skipped += VaultImport.Skipped(title, "nothing readable in it")
            return
        }

        val kind = KINDS[item.string("categoryUuid").orEmpty()] ?: VaultItemKind.OTHER
        val login = details.array("loginFields").objects()
        val username = login.firstOrNull { it.string("designation") == "username" }?.string("value")
            ?: login.firstOrNull { it.string("name") == "username" }?.string("value")

        val fields = ArrayList<VaultField>()
        var totp: TotpConfig? = null
        var cardNumber: String? = null
        var password: String? =
            login.firstOrNull { it.string("designation") == "password" }?.string("value")
                ?: login.firstOrNull { it.string("name") == "password" }?.string("value")

        for (section in details.array("sections").objects()) {
            val sectionTitle = section.string("title").orEmpty()
            for (field in section.array("fields").objects()) {
                val value = field.obj("value") ?: continue
                val name = field.string("title").orEmpty().ifBlank { field.string("id").orEmpty() }

                // A second factor is a seed rather than a field: kept as one it would be a string
                // nobody can turn into a code, in an app that can. Only the first is taken — an
                // item with two is vanishingly rare and the second has nowhere to live. A seed that
                // will not parse falls through and is kept as a field, which loses the codes and
                // keeps the secret.
                val seed = value.string("totp")
                if (seed != null && totp == null) {
                    totp = Totp.parse(seed)
                    if (totp != null) continue
                }

                val number = value.string("creditCardNumber")
                if (number != null && cardNumber == null) {
                    cardNumber = number
                    continue
                }

                val concealed = value.has("concealed")
                val promotable = kind != VaultItemKind.CARD && kind != VaultItemKind.NOTE
                if (concealed && password == null && promotable) {
                    password = value.string("concealed")
                    continue
                }

                val flat = flatten(value)?.takeIf { it.isNotBlank() } ?: continue
                fields += VaultField(
                    name = listOf(sectionTitle, name).filter { it.isNotBlank() }.joinToString(" · ")
                        .ifBlank { "Field" },
                    value = flat,
                    secret = concealed
                )
            }
        }

        // The extra addresses. The first is the item's own; the rest are real — a bank with a
        // separate app domain, a site that moved — and are kept as fields rather than dropped,
        // because autofill matches on the one address and somebody has to be able to find the rest.
        val urls = overview?.array("urls").objects().mapNotNull { it.string("url") }
        val primary = overview?.string("url")?.takeIf { it.isNotBlank() } ?: urls.firstOrNull()
        urls.filter { it != primary }.distinct().forEachIndexed { index, extra ->
            fields += VaultField(name = "Website ${index + 2}", value = extra)
        }

        details.obj("documentAttributes")?.let { document ->
            skipped += VaultImport.Skipped(
                document.string("fileName") ?: title,
                "a file attached to \"$title\" — this vault holds text, not documents"
            )
        }

        val cardNumberValue = cardNumber
        val passwordValue = password
        val secret = when (kind) {
            VaultItemKind.CARD -> cardNumberValue ?: passwordValue
            VaultItemKind.NOTE -> null
            else -> passwordValue ?: cardNumberValue
        }.orEmpty()

        // Whichever of the two did not become the secret stays, named for what it is.
        if (cardNumberValue != null && cardNumberValue != secret) {
            fields += VaultField(name = "Card number", value = cardNumberValue, secret = true)
        }
        if (passwordValue != null && passwordValue != secret) {
            fields += VaultField(name = "Password", value = passwordValue, secret = true)
        }

        val note = details.string("notesPlain").orEmpty()
        val empty = secret.isEmpty() && username.isNullOrEmpty() && note.isEmpty()
        if (empty && fields.isEmpty() && totp == null) {
            skipped += VaultImport.Skipped(title, "nothing in it but a name")
            return
        }

        val tags = overview?.array("tags").strings() +
            listOfNotNull(vaultName.takeIf { it.isNotBlank() && it != DEFAULT_VAULT })

        into += VaultItem(
            id = newId(),
            kind = kind,
            title = title,
            username = username.orEmpty(),
            secret = secret,
            fields = fields,
            note = note,
            url = primary.orEmpty(),
            tags = tags.distinct(),
            totp = totp,
            history = history(details, now),
            favourite = (item.number("favIndex") ?: 0.0) > 0,
            createdAt = millis(item.number("createdAt"), now) ?: now,
            updatedAt = millis(item.number("updatedAt"), now) ?: now
        )
    }

    /**
     * The passwords this item used to have.
     *
     * Kept for the reason [VaultItem.history] gives — the account you are locked out of is usually
     * the one whose password you just changed — and capped at the same limit, newest first, because
     * an item carrying two hundred of them is an item carrying two hundred live plaintexts.
     */
    private fun history(details: JsonObject, now: Long): List<VaultSecretVersion> =
        details.array("passwordHistory").objects()
            .mapNotNull { entry ->
                val value = entry.string("value")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                VaultSecretVersion(value, millis(entry.number("time"), now) ?: now)
            }
            .sortedByDescending { it.replacedAt }
            .take(VaultItem.HISTORY_LIMIT)

    /**
     * One typed value, as the string a person would read.
     *
     * Null for a type this does not know how to print — see the class note on why that is a skip
     * rather than a guess.
     */
    private fun flatten(value: JsonObject): String? {
        for (key in PLAIN_KEYS) value.string(key)?.let { return it }
        value.number("date")?.let {
            return runCatching {
                Instant.ofEpochSecond(it.toLong()).atZone(ZoneOffset.UTC).toLocalDate().toString()
            }.getOrNull()
        }
        value.number("monthYear")?.let {
            // `202601` — a card's expiry, written as one number.
            val raw = it.toLong()
            return "${raw / 100}-${(raw % 100).toString().padStart(2, '0')}"
        }
        value.obj("email")?.let { return it.string("email_address") }
        value.obj("address")?.let { address ->
            return ADDRESS_KEYS.mapNotNull { address.string(it)?.takeIf(String::isNotBlank) }
                .joinToString(", ")
        }
        return null
    }

    /**
     * A 1Password timestamp in milliseconds, or null.
     *
     * The format writes seconds; a couple of tools that produce it have written milliseconds. The
     * two are told apart by size rather than by dialect, and a date in the future — or from before
     * this kind of software existed — is dropped rather than stored, because an item stamped in 2087
     * would never again be reported as old by [VaultAudit].
     */
    private fun millis(value: Double?, now: Long): Long? {
        val raw = value?.toLong() ?: return null
        val millis = if (raw < SECONDS_CEILING) raw * 1000 else raw
        return millis.takeIf { it in EARLIEST_PLAUSIBLE..now }
    }

    /** 1Password's category ids. The ones without a home here land as [VaultItemKind.OTHER]. */
    private val KINDS = mapOf(
        "001" to VaultItemKind.LOGIN,
        "002" to VaultItemKind.CARD,
        "003" to VaultItemKind.NOTE,
        "004" to VaultItemKind.IDENTITY,
        "005" to VaultItemKind.LOGIN,
        "103" to VaultItemKind.IDENTITY,
        "106" to VaultItemKind.IDENTITY,
        "108" to VaultItemKind.IDENTITY,
        "109" to VaultItemKind.WIFI,
        "111" to VaultItemKind.LOGIN,
        "112" to VaultItemKind.API_KEY,
        "114" to VaultItemKind.API_KEY
    )

    private val PLAIN_KEYS = listOf(
        "string", "concealed", "url", "phone", "menu", "gender", "creditCardType",
        "creditCardNumber", "reference", "totp"
    )

    private val ADDRESS_KEYS = listOf("street", "city", "state", "zip", "country")

    /** What 1Password calls the vault everybody has, which is not worth becoming a tag. */
    private const val DEFAULT_VAULT = "Personal"

    /** Below this a timestamp is in seconds, above it milliseconds (~1973 / ~5138). */
    private const val SECONDS_CEILING = 100_000_000_000L

    /** 2000-01-01. Nothing anybody is importing now was exported before it. */
    private const val EARLIEST_PLAUSIBLE = 946_684_800_000L

    // --- Gson, made bearable ---------------------------------------------------------------------
    //
    // An export is a file somebody else wrote, and one that can arrive half-written: every accessor
    // here answers null rather than throwing, so a malformed item is a skipped item rather than a
    // failed import of the other four hundred.

    private fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject

    private fun JsonObject.array(name: String): JsonArray? = get(name) as? JsonArray

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.asString

    private fun JsonObject.number(name: String): Double? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isNumber }?.asDouble

    private fun JsonObject.bool(name: String): Boolean? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isBoolean }?.asBoolean

    private fun JsonArray?.objects(): List<JsonObject> =
        this?.mapNotNull { it as? JsonObject } ?: emptyList()

    private fun JsonArray?.strings(): List<String> =
        this?.mapNotNull { element -> (element as? JsonPrimitive)?.takeIf { it.isString }?.asString }
            ?: emptyList()
}
