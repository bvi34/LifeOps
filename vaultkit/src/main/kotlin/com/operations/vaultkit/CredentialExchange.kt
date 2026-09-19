package com.operations.vaultkit

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.util.UUID

/**
 * The FIDO Credential Exchange Format — what arrives when one password manager hands its contents
 * to another.
 *
 * ## What this is, and why it is not another export reader
 *
 * [CredentialCsv] and [OnePasswordExport] read files somebody exported by hand. This reads the
 * payload of a *protocol*: the household taps Import, the system shows every credential manager on
 * the phone, they pick one, that app asks them to authenticate, and it hands its vault straight to
 * this one. No file, no Downloads folder, nothing left behind — and nothing in it that the app
 * being left decided to drop, because the format was designed by the people who would otherwise
 * have been dropping it.
 *
 * CXF is a standard, which is the part that matters. One reader here answers Google Password
 * Manager, 1Password, Bitwarden, Dashlane, Apple's Passwords and anything else that implements it,
 * and it keeps answering a manager that ships after this code was written. The Android half — the
 * selector, the permission, the transfer — is in `:secrets`; this file is the part that can be
 * tested without a phone, which is all of the decisions.
 *
 * ## The shape
 *
 * ```
 * Header { version, exporterRpId, exporterDisplayName, timestamp, accounts[] }
 *   Account { id, username, email, collections[], items[] }
 *     Collection { id, title, items[ { item: <id> } ], subCollections[] }
 *     Item { id, title, subtitle, favorite, tags[], scope, creationAt, modifiedAt, credentials[] }
 *       Credential { type: "basic-auth" | "passkey" | "totp" | "note" | "credit-card" | … }
 * ```
 *
 * An **item** is the row a person sees; a **credential** is one fact inside it. A login with a
 * second factor and three custom fields is one item with three credentials, and it becomes one
 * [VaultItem] — which is the shape this vault already has, so the mapping is mostly a matter of
 * deciding which credential is *the* secret. That is [secretOf], and it is per kind: a login's
 * password, a card's number, a wifi network's passphrase, and for a secure note, nothing at all.
 *
 * ## Passkeys come across whole
 *
 * This is the part no CSV can do. A passkey is a key pair, and CXF carries the private half as
 * PKCS#8 — so an imported passkey is not a record *of* a credential, it is the credential, and it
 * signs. The public half is not in the format (it is a function of the private one) and is
 * recomputed on the way in by [Passkeys.publicKeyFrom].
 *
 * Worth knowing rather than discovering: an import is a **copy**. The other manager still holds
 * what it held, and until somebody deletes it there, two apps can offer the same passkey to the
 * same site. That is how the protocol is specified — a transfer that deleted the far side would be
 * a transfer that loses everything if it half-fails — and the screen says so.
 *
 * ## Everything here is defensive
 *
 * The payload comes from another application. Every accessor answers null rather than throwing, a
 * malformed item is skipped and named rather than taken as the end of the import, and no value is
 * treated as anything but text.
 */
object CredentialExchange {

    /**
     * Read a CXF document, or null if this is not one.
     *
     * [now] stamps whatever arrives without a date of its own, and [newId] exists so the tests can
     * be deterministic.
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
            val folders = foldersOf(account)
            for (item in account.array("items").objects()) {
                convert(item, folders, now, newId, items, skipped)
            }
        }

        if (items.isEmpty() && skipped.isEmpty()) return null
        return VaultImport.Read(
            format = VaultImport.Format.CREDENTIAL_EXCHANGE,
            items = items,
            skipped = skipped,
            // The exporter names itself, and it is the only honest label for this import: the
            // household picked an app from a list, and "read 312 from 1Password" is what they are
            // owed rather than "read 312 from a credential provider".
            from = root.string("exporterDisplayName")?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * Which collections each item is in, as tags.
     *
     * A collection is the other manager's folder or vault, and it is the one piece of the
     * household's own filing in the payload. Sub-collections are walked with their parent's name
     * prepended, so `Family / Utilities` survives as one tag rather than as a tree this app has
     * nowhere to put.
     */
    private fun foldersOf(account: JsonObject): Map<String, List<String>> {
        val folders = HashMap<String, MutableList<String>>()

        fun walk(collection: JsonObject, prefix: String) {
            val title = collection.string("title").orEmpty()
            val path = listOf(prefix, title).filter { it.isNotBlank() }.joinToString(" / ")
            for (link in collection.array("items").objects()) {
                val id = link.string("item") ?: continue
                if (path.isNotBlank()) folders.getOrPut(id) { ArrayList() }.add(path)
            }
            for (child in collection.array("subCollections").objects()) walk(child, path)
        }

        for (collection in account.array("collections").objects()) walk(collection, "")
        return folders
    }

    private fun convert(
        item: JsonObject,
        folders: Map<String, List<String>>,
        now: Long,
        newId: () -> String,
        into: MutableList<VaultItem>,
        skipped: MutableList<VaultImport.Skipped>
    ) {
        val title = item.string("title").orEmpty().ifBlank { "Untitled" }
        val credentials = item.array("credentials").objects()
        if (credentials.isEmpty()) {
            skipped += VaultImport.Skipped(title, "nothing in it but a name")
            return
        }

        val fields = ArrayList<VaultField>()
        val urls = ArrayList<String>()
        var username = ""
        var note = ""
        var totp: TotpConfig? = null
        var passkey: VaultPasskey? = null
        val secrets = HashMap<String, String>()

        // The item's own addresses. `scope` is where CXF v1 puts them; a few exporters wrote them
        // on the credential instead, which is cheap to accept and expensive to be without.
        item.obj("scope")?.let { scope ->
            urls += scope.array("urls").strings()
            scope.array("androidApps").objects().mapNotNull { it.string("bundleId") }
                .forEach { fields += VaultField(name = "App", value = it) }
        }

        for (credential in credentials) {
            urls += credential.array("urls").strings()
            when (credential.string("type")) {
                "basic-auth" -> {
                    credential.field("username")?.let { if (username.isBlank()) username = it.value }
                    credential.field("password")?.let { secrets[LOGIN] = it.value }
                }

                "passkey" -> passkey = passkeyOf(credential, now)
                    ?: run {
                        skipped += VaultImport.Skipped(title, "a passkey this app cannot hold")
                        null
                    }

                "totp" -> if (totp == null) {
                    totp = totpOf(credential)
                    if (totp == null) {
                        credential.string("secret")?.let {
                            fields += VaultField("One-time password", it, secret = true)
                        }
                    }
                }

                "note" -> credential.field("content")?.let { if (note.isBlank()) note = it.value }

                "credit-card" -> {
                    credential.field("number")?.let { secrets[CARD] = it.value }
                    fields += credential.fieldsOf(
                        "fullName" to "Name on card",
                        "cardType" to "Type",
                        "verificationNumber" to "Security code",
                        "pin" to "PIN",
                        "expiryDate" to "Expires",
                        "validFrom" to "Valid from"
                    )
                }

                "wifi" -> {
                    credential.field("passphrase")?.let { secrets[WIFI] = it.value }
                    credential.field("ssid")?.let { if (username.isBlank()) username = it.value }
                    fields += credential.fieldsOf(
                        "networkSecurityType" to "Security",
                        "hidden" to "Hidden network"
                    )
                }

                "api-key" -> {
                    credential.field("key")?.let { secrets[KEY] = it.value }
                    credential.field("username")?.let { if (username.isBlank()) username = it.value }
                    credential.field("url")?.let { urls += it.value }
                    fields += credential.fieldsOf(
                        "keyType" to "Kind",
                        "validFrom" to "Valid from",
                        "expiryDate" to "Expires"
                    )
                }

                "ssh-key" -> {
                    credential.string("privateKey")?.let { secrets[KEY] = it }
                    credential.string("keyType")?.let { fields += VaultField("Algorithm", it) }
                    credential.string("keyComment")?.let { fields += VaultField("Comment", it) }
                }

                "generated-password" -> credential.field("password")
                    ?.let { secrets.putIfAbsent(LOGIN, it.value) }

                "custom-fields" -> {
                    val section = credential.string("label").orEmpty()
                    for (field in credential.array("fields").objects()) {
                        val value = field.string("value")?.takeIf { it.isNotBlank() } ?: continue
                        val name = field.string("label").orEmpty().ifBlank { "Field" }
                        fields += VaultField(
                            name = listOf(section, name).filter { it.isNotBlank() }.joinToString(" · "),
                            value = value,
                            secret = field.string("fieldType") == CONCEALED
                        )
                    }
                }

                // Identity documents and the rest: no secret of their own worth promoting, and
                // every member is a labelled value, so they arrive as what they are.
                "address", "person-name", "passport", "drivers-license", "identity-document" ->
                    fields += credential.allFields()

                // A file is a file. Same answer as an attachment in a 1Password export, and for the
                // same reason: see the note at the top of [VaultDocument] on whole-document writes.
                "file" -> skipped += VaultImport.Skipped(
                    credential.string("name") ?: title,
                    "a file attached to \"$title\" — this vault holds text, not documents"
                )

                // A pointer to another item in the same payload. The item it points at is already
                // being imported on its own, so following it would file the same secret twice.
                "item-reference" -> Unit

                else -> fields += credential.allFields()
            }
        }

        val kind = kindOf(secrets, passkey, note, fields)
        val secret = secretOf(kind, secrets)
        // Whatever did not become the secret is still the household's, and still named.
        secrets.filterValues { it != secret && it.isNotBlank() }
            .forEach { (which, value) -> fields += VaultField(LABELS.getValue(which), value, secret = true) }

        if (secret.isEmpty() && passkey == null && username.isBlank() && note.isBlank() && fields.isEmpty() && totp == null) {
            skipped += VaultImport.Skipped(title, "nothing in it but a name")
            return
        }

        // A passkey names its own site, and an item with no address is an item autofill and search
        // cannot find. The relying party is that address, so it stands in when nothing else does.
        passkey?.rpId?.let { if (urls.none { url -> AutofillMatch.hostOf(url) != null }) urls += it }

        val addresses = urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        addresses.drop(1).forEachIndexed { index, extra ->
            fields += VaultField(name = "Website ${index + 2}", value = extra)
        }

        val created = seconds(item.number("creationAt"), now)
        val modified = seconds(item.number("modifiedAt"), now)
        into += VaultItem(
            id = newId(),
            kind = kind,
            title = title,
            username = username,
            secret = secret,
            fields = fields,
            note = note,
            url = addresses.firstOrNull().orEmpty(),
            tags = (item.array("tags").strings() + folders[item.string("id")].orEmpty()).distinct(),
            totp = totp,
            passkey = passkey,
            favourite = item.bool("favorite") == true,
            // Zero where the other manager said nothing: see the note in [CredentialCsv].
            createdAt = created ?: modified ?: 0L,
            updatedAt = modified ?: 0L
        )
    }

    /**
     * What sort of thing this item is, decided by what it turned out to hold.
     *
     * Read top to bottom: a passkey makes it a passkey whatever else is on it, because that is what
     * signs you in; a password makes it a login; and an item with nothing but text is a note.
     */
    private fun kindOf(
        secrets: Map<String, String>,
        passkey: VaultPasskey?,
        note: String,
        fields: List<VaultField>
    ): VaultItemKind = when {
        passkey != null -> VaultItemKind.PASSKEY
        secrets.containsKey(LOGIN) -> VaultItemKind.LOGIN
        secrets.containsKey(CARD) -> VaultItemKind.CARD
        secrets.containsKey(WIFI) -> VaultItemKind.WIFI
        secrets.containsKey(KEY) -> VaultItemKind.API_KEY
        note.isNotBlank() && fields.isEmpty() -> VaultItemKind.NOTE
        else -> VaultItemKind.OTHER
    }

    /** The one value the item exists to protect, per kind. A note's is nothing. */
    private fun secretOf(kind: VaultItemKind, secrets: Map<String, String>): String = when (kind) {
        VaultItemKind.CARD -> secrets[CARD]
        VaultItemKind.WIFI -> secrets[WIFI]
        VaultItemKind.API_KEY -> secrets[KEY]
        VaultItemKind.NOTE -> null
        // A passkey item can still carry the password the site used to take, and it stays the
        // secret: the item is titled after the site, and that is where somebody would look for it.
        else -> secrets[LOGIN] ?: secrets[KEY] ?: secrets[CARD] ?: secrets[WIFI]
    }.orEmpty()

    /**
     * A passkey, with the public half recomputed from the private one.
     *
     * Null when the key will not parse or is not P-256 — reported rather than dropped, because an
     * item that quietly loses its passkey is an account somebody cannot sign in to, discovered at
     * the worst possible moment.
     */
    private fun passkeyOf(credential: JsonObject, now: Long): VaultPasskey? {
        val privateKey = credential.string("key")?.let { WebAuthn.fromBase64Url(it) } ?: return null
        val publicKey = Passkeys.publicKeyFrom(privateKey) ?: return null
        val credentialId = credential.string("credentialId") ?: return null
        val rpId = credential.string("rpId")?.takeIf { it.isNotBlank() } ?: return null

        return VaultPasskey(
            credentialId = credentialId,
            rpId = rpId,
            rpName = rpId,
            userHandle = credential.string("userHandle").orEmpty(),
            userName = credential.string("username").orEmpty(),
            userDisplayName = credential.string("userDisplayName").orEmpty(),
            privateKey = WebAuthn.base64Url(privateKey),
            publicKey = WebAuthn.base64Url(publicKey),
            createdAt = now
        ).takeIf { it.isUsable }
    }

    /** A second factor. The defaults are the ones every authenticator assumes; see [TotpConfig]. */
    private fun totpOf(credential: JsonObject): TotpConfig? {
        val secret = credential.string("secret")?.takeIf { it.isNotBlank() } ?: return null
        return TotpConfig(
            secret = secret,
            algorithm = TotpAlgorithm.fromKey(credential.string("algorithm")) ?: TotpAlgorithm.SHA1,
            digits = credential.number("digits")?.toInt()?.takeIf {
                it in TotpConfig.MIN_DIGITS..TotpConfig.MAX_DIGITS
            } ?: TotpConfig.DEFAULT_DIGITS,
            periodSeconds = credential.number("period")?.toInt()?.takeIf {
                it in TotpConfig.MIN_PERIOD..TotpConfig.MAX_PERIOD
            } ?: TotpConfig.DEFAULT_PERIOD_SECONDS,
            issuer = credential.string("issuer").orEmpty(),
            account = credential.string("username").orEmpty()
        ).takeIf { it.isUsable }
    }

    /** One `EditableField`: its value, and whether the format says it is meant to be hidden. */
    private data class Editable(val value: String, val concealed: Boolean)

    /**
     * Read [name] as an `EditableField`.
     *
     * Tolerant of a plain string in its place, which is not in the specification and is what a
     * hand-rolled exporter writes. Taking it costs nothing and refusing it would lose a password
     * over a wrapper object.
     */
    private fun JsonObject.field(name: String): Editable? {
        val element = get(name) ?: return null
        if (element is JsonPrimitive && element.isString) {
            return Editable(element.asString, false).takeIf { it.value.isNotBlank() }
        }
        val obj = element as? JsonObject ?: return null
        val value = obj.string("value")?.takeIf { it.isNotBlank() } ?: return null
        return Editable(value, obj.string("fieldType") == CONCEALED)
    }

    /** Several named fields at once, keeping the format's own labels where it gave one. */
    private fun JsonObject.fieldsOf(vararg names: Pair<String, String>): List<VaultField> =
        names.mapNotNull { (member, label) ->
            field(member)?.let { editable ->
                val named = (get(member) as? JsonObject)?.string("label")?.takeIf { it.isNotBlank() }
                VaultField(named ?: label, editable.value, secret = editable.concealed)
            }
        }

    /**
     * Every member of a credential this reader has no specific mapping for.
     *
     * The fallback that makes an unknown credential type survive rather than vanish: a format that
     * gains a type next year produces labelled values here instead of a silently emptier vault.
     */
    private fun JsonObject.allFields(): List<VaultField> = entrySet()
        .filterNot { it.key == "type" || it.key == "id" }
        .mapNotNull { (key, _) ->
            field(key)?.let { VaultField(label(key), it.value, secret = it.concealed) }
        }

    /** `verificationNumber` → `Verification number`, for a member nobody has named here. */
    private fun label(member: String): String = member
        .replace(Regex("([a-z0-9])([A-Z])")) { match ->
            match.groupValues[1] + " " + match.groupValues[2].lowercase()
        }
        .replaceFirstChar { it.uppercase() }

    private fun seconds(value: Double?, now: Long): Long? {
        val raw = value?.toLong() ?: return null
        val millis = if (raw < SECONDS_CEILING) raw * 1000 else raw
        return millis.takeIf { it in EARLIEST_PLAUSIBLE..now }
    }

    private const val CONCEALED = "concealed-string"

    // Which secret came from where, so the one that becomes [VaultItem.secret] can be chosen by
    // kind and the others kept as named fields rather than lost.
    private const val LOGIN = "password"
    private const val CARD = "card"
    private const val WIFI = "wifi"
    private const val KEY = "key"

    private val LABELS = mapOf(
        LOGIN to "Password",
        CARD to "Card number",
        WIFI to "Network password",
        KEY to "Key"
    )

    /** Below this a timestamp is in seconds, above it milliseconds (~1973 / ~5138). */
    private const val SECONDS_CEILING = 100_000_000_000L

    /** 2000-01-01. */
    private const val EARLIEST_PLAUSIBLE = 946_684_800_000L

    // --- Gson, made bearable. Same discipline as [OnePasswordExport]: null, never an exception. ---

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
