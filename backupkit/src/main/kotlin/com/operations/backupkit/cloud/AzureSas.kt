package com.operations.backupkit.cloud

import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * What can be read off a shared access signature without asking Azure anything.
 *
 * A SAS is a query string, and two of its parameters are worth a great deal to a screen that would
 * otherwise have nothing to say: `se` is the expiry, and `sp` is what the token is allowed to do.
 * Both are *in the credential itself* — no request, no round trip, no account key — so the settings
 * screen can tell somebody that their nightly backup stops on the 3rd of next month, and that the
 * token they pasted can create blobs but not list them, at the moment they paste it rather than the
 * morning they need a restore.
 *
 * Everything here is best-effort by design. A token whose `se` this cannot parse is treated as
 * having no known expiry rather than as expired: refusing to run because a date format was
 * unfamiliar would turn a cosmetic gap into a household with no backups.
 */
object AzureSas {

    /** The expiry parameter. */
    private const val EXPIRY = "se"

    /** The permissions parameter: some subset of `racwdxltmei`, in Azure's fixed order. */
    private const val PERMISSIONS = "sp"

    /**
     * One parameter's decoded value, or null when the token doesn't carry it.
     *
     * Deliberately tolerant of how the token was pasted: parameters may be separated by `&`, a
     * value may be percent-encoded (the signature always is), and a stray leading `?` is ignored.
     */
    fun param(sasToken: String, key: String): String? {
        val token = sasToken.trim().trimStart('?')
        if (token.isEmpty()) return null
        for (pair in token.split('&')) {
            val name = pair.substringBefore('=', pair).trim()
            if (!name.equals(key, ignoreCase = true)) continue
            val raw = pair.substringAfter('=', "")
            if (raw.isEmpty()) return null
            return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        }
        return null
    }

    /**
     * When the token stops working, in epoch millis, or null if it doesn't say (or says it in a
     * form this doesn't recognise).
     *
     * Azure writes `se` in ISO 8601 and is inconsistent about how much of it: the portal's
     * container SAS is `2026-12-31T23:59:59Z`, the CLI often omits the seconds, an account SAS can
     * be a bare date, and a hand-made one may carry an offset instead of `Z`. All four are the same
     * instant to a household and all four are parsed here.
     */
    fun expiresAt(sasToken: String): Long? {
        val raw = param(sasToken, EXPIRY)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return parseInstant(raw)?.toEpochMilli()
    }

    /**
     * Whether the token has already expired. False when there is no readable expiry — see the note
     * on this object: an unparseable date must not be the reason backups stop.
     */
    fun isExpired(sasToken: String, now: Long): Boolean {
        val expiry = expiresAt(sasToken) ?: return false
        return now >= expiry
    }

    /** Millis until the token expires — negative once it has, null when it doesn't say. */
    fun millisUntilExpiry(sasToken: String, now: Long): Long? = expiresAt(sasToken)?.minus(now)

    /** The permission letters the token carries, lower-cased, or "" when it doesn't list any. */
    fun permissions(sasToken: String): String =
        param(sasToken, PERMISSIONS)?.lowercase().orEmpty()

    /**
     * Whether the token may put a new blob in the container.
     *
     * `c` (create) is enough for a blob that isn't there yet, which every archive is — its name
     * carries the minute it was taken. `w` (write) implies it. Either will do, and the portal's
     * default for "add to a container" hands out both.
     */
    fun canWrite(sasToken: String): Boolean =
        permissions(sasToken).let { it.contains('c') || it.contains('w') }

    /** Whether the token may enumerate the container — what retention needs before it can prune. */
    fun canList(sasToken: String): Boolean = permissions(sasToken).contains('l')

    /** Whether the token may remove an old archive. Without it, retention is off by construction. */
    fun canDelete(sasToken: String): Boolean = permissions(sasToken).contains('d')

    /**
     * The one-line version for the settings screen: what the token can do and how long for.
     *
     * Said plainly rather than as letters, because `sp=racwdl` is not a sentence anybody should
     * have to decode to find out whether their backups will keep working.
     */
    fun describe(sasToken: String, now: Long): String {
        if (sasToken.isBlank()) return "No signature saved."
        val abilities = buildList {
            if (canWrite(sasToken)) add("upload")
            if (canList(sasToken)) add("list")
            if (canDelete(sasToken)) add("delete")
        }
        val what = if (abilities.isEmpty()) "no permissions this app recognises" else abilities.joinToString(", ")
        val remaining = millisUntilExpiry(sasToken, now)
        val when_ = when {
            remaining == null -> "no expiry recorded in the token"
            remaining <= 0L -> "expired"
            else -> "expires in ${days(remaining)}"
        }
        return "Signature allows $what — $when_."
    }

    /** Whole days, rounded up, so "expires in 1 day" never means "expired four hours ago". */
    private fun days(millis: Long): String {
        val day = 24L * 60 * 60 * 1000
        val whole = (millis + day - 1) / day
        return if (whole == 1L) "1 day" else "$whole days"
    }

    /**
     * The four shapes `se` arrives in, tried widest first. A failure is null, never an exception:
     * every caller here treats "unknown" as "carry on".
     */
    private fun parseInstant(raw: String): Instant? {
        runCatching { return OffsetDateTime.parse(raw).toInstant() }
        runCatching {
            return LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC)
        }
        runCatching {
            return LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZoneOffset.UTC).toInstant()
        }
        return null
    }
}
