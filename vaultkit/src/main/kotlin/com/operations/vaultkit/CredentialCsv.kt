package com.operations.vaultkit

import java.util.UUID

/**
 * The CSV every browser — and 1Password — writes when somebody asks for their passwords back.
 *
 * ## One reader, several dialects
 *
 * There is no standard here and there never was. What there is, is five files that all say the same
 * five things in different words:
 *
 * | Written by | Header |
 * |---|---|
 * | Chrome, Edge, Brave | `name,url,username,password,note` |
 * | Firefox | `"url","username","password","httpRealm","formActionOrigin","guid","timeCreated","timeLastUsed","timePasswordChanged"` |
 * | Safari, Apple Passwords | `Title,URL,Username,Password,Notes,OTPAuth` |
 * | 1Password | `Title,Url,Username,Password,OTPAuth,Favorite,Archived,Tags,Notes` |
 *
 * So the reader is column-driven rather than dialect-driven: it looks up each thing it needs by a
 * list of names in priority order, and a file whose header it has never seen still imports as long
 * as it calls its password column something recognisable. The dialect is worked out separately and
 * used for **one** purpose — telling the household which of their exports they just picked, so that
 * somebody who meant to import 1Password and is looking at 400 Chrome logins finds out before they
 * tap the button rather than after.
 *
 * ## What it refuses
 *
 * A file with no password, username *or* note column is not a password export, and is rejected
 * outright rather than imported as a list of titles. That is the check that stops a bank statement
 * or a contact list becoming four hundred empty vault items.
 *
 * ## Dates
 *
 * Firefox is the only one of these that says when a password was last changed, and it is worth
 * carrying: [VaultAudit] scores an item's age from [VaultItem.updatedAt], so stamping every imported
 * row with the moment of the import would tell the household that the password they last touched in
 * 2019 is fresh — on the very screen they opened the vault to consult. Where the file knows, the
 * file wins; where it does not, the import time is used and is at least honest about the item.
 */
object CredentialCsv {

    // Each list is in priority order: the first name present in the header is the column used. That
    // matters for a file with both `username` and `email` — the one that says what it is wins.
    private val TITLE = listOf("name", "title", "item name", "display name", "account name")
    private val URL = listOf("url", "login uri", "website", "web site", "site", "urls", "login url")
    private val USERNAME = listOf(
        "username", "login username", "user name", "login", "user", "account", "email",
        "email address", "e-mail"
    )
    private val PASSWORD = listOf("password", "login password", "passwd", "pwd")
    private val NOTE = listOf("note", "notes", "comment", "comments", "login notes")
    private val OTP = listOf(
        "otpauth", "otp auth", "otpurl", "totp", "login totp", "otp", "one-time password",
        "two-factor secret"
    )
    private val TAGS = listOf("tags", "tag", "folder", "grouping", "collection")
    private val FAVOURITE = listOf("favorite", "favourite", "starred", "fav")
    private val ARCHIVED = listOf("archived", "trashed")
    private val CHANGED_AT = listOf("timepasswordchanged", "password changed", "last modified", "updated at")
    private val CREATED_AT = listOf("timecreated", "created", "created at", "date created")

    /**
     * Read [text] as a password CSV, or return null if it is not one.
     *
     * [now] stamps the rows whose file does not say when they were changed; [newId] exists so the
     * tests can be deterministic and is a UUID everywhere else.
     */
    fun read(
        text: String,
        now: Long,
        newId: () -> String = { UUID.randomUUID().toString() }
    ): VaultImport.Read? {
        val rows = Csv.parse(text)
        if (rows.size < 2) return null

        val headers = rows[0].map { Csv.normaliseHeader(it) }
        val password = headers.pick(PASSWORD)
        val username = headers.pick(USERNAME)
        val note = headers.pick(NOTE)
        // A file with none of the three holds nothing a vault can keep. Rejecting it here is what
        // keeps "I picked the wrong file" an error message rather than four hundred empty items.
        if (password == null && username == null && note == null) return null

        val title = headers.pick(TITLE)
        val url = headers.pick(URL)
        val otp = headers.pick(OTP)
        val tags = headers.pick(TAGS)
        val favourite = headers.pick(FAVOURITE)
        val archived = headers.pick(ARCHIVED)
        val changedAt = headers.pick(CHANGED_AT)
        val createdAt = headers.pick(CREATED_AT)

        val items = ArrayList<VaultItem>()
        val skipped = ArrayList<VaultImport.Skipped>()

        rows.drop(1).forEachIndexed { index, row ->
            val line = index + 2
            val cells = { at: Int? -> at?.let { row.getOrNull(it) }?.trim().orEmpty() }

            val rowTitle = cells(title)
            val rowUrl = cells(url)
            val rowUsername = cells(username)
            val rowPassword = cells(password)
            val rowNote = cells(note)
            val label = rowTitle.ifBlank { AutofillMatch.hostOf(rowUrl) ?: rowUsername }
                .ifBlank { "line $line" }

            if (archived != null && cells(archived).isTruthy()) {
                // Archived in the other manager is a decision somebody made about this login. An
                // import that quietly undoes it hands back the logins they filed away on purpose.
                skipped += VaultImport.Skipped(label, "archived where it came from")
                return@forEachIndexed
            }
            if (rowPassword.isEmpty() && rowUsername.isEmpty() && rowNote.isEmpty()) {
                skipped += VaultImport.Skipped(label, "no password, username or note in it")
                return@forEachIndexed
            }

            val changed = epochMillis(cells(changedAt), now)
            val created = epochMillis(cells(createdAt), now)
            items += VaultItem(
                id = newId(),
                kind = if (rowPassword.isEmpty() && rowUsername.isEmpty()) {
                    VaultItemKind.NOTE
                } else {
                    VaultItemKind.LOGIN
                },
                title = label,
                username = rowUsername,
                secret = rowPassword,
                note = rowNote,
                url = rowUrl,
                tags = cells(tags).toTags(),
                totp = cells(otp).takeIf { it.isNotEmpty() }?.let { Totp.parse(it) },
                favourite = favourite != null && cells(favourite).isTruthy(),
                // Zero where the file said nothing, rather than the moment of the import. The
                // difference decides whether a password that disagrees with the one in the vault
                // can be resolved by date at all — see [VaultImport.plan] — and stamping every
                // undated row with today would make every export claim to be the newest copy.
                createdAt = created ?: changed ?: 0L,
                updatedAt = changed ?: 0L
            )
        }

        if (items.isEmpty() && skipped.isEmpty()) return null
        return VaultImport.Read(dialect(headers), items, skipped)
    }

    /**
     * Which export this is, for the one sentence the screen shows before anything is imported.
     *
     * Nothing downstream branches on the answer — the columns already decided everything — so a
     * wrong guess costs a word on a screen rather than a misread password. Hence the last case:
     * saying "a password export" is better than picking a brand at random.
     */
    private fun dialect(headers: List<String>): VaultImport.Format = when {
        headers.any { it == "formactionorigin" || it == "httprealm" } -> VaultImport.Format.FIREFOX_CSV
        headers.any { it in ARCHIVED } && headers.any { it in OTP } -> VaultImport.Format.ONEPASSWORD_CSV
        headers.any { it in OTP } && headers.contains("title") -> VaultImport.Format.APPLE_CSV
        headers.contains("name") && headers.contains("url") -> VaultImport.Format.CHROMIUM_CSV
        else -> VaultImport.Format.GENERIC_CSV
    }

    private fun List<String>.pick(names: List<String>): Int? {
        for (name in names) {
            val at = indexOf(name)
            if (at >= 0) return at
        }
        return null
    }

    private fun String.isTruthy(): Boolean =
        lowercase() in setOf("true", "yes", "y", "1", "x", "archived")

    /** `work, banking` or `work;banking` — both are written, and a folder path is one tag. */
    private fun String.toTags(): List<String> =
        split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * A timestamp from a CSV cell, in whatever unit it was written, or null.
     *
     * Seconds and milliseconds are both written by things that export passwords, and the two are
     * told apart by size rather than by dialect: any plausible date in milliseconds is far past the
     * point a seconds value could reach. Anything in the future, or before this kind of software
     * existed, is not a date somebody meant and is dropped rather than stored — an item stamped in
     * 2087 would never again be reported as old by the audit.
     */
    private fun epochMillis(raw: String, now: Long): Long? {
        val value = raw.trim().takeIf { it.isNotEmpty() }?.toLongOrNull() ?: return null
        val millis = if (value < SECONDS_CEILING) value * 1000 else value
        return millis.takeIf { it in EARLIEST_PLAUSIBLE..now }
    }

    /** Below this, a timestamp is in seconds; above it, milliseconds. (~year 5138 / 1973.) */
    private const val SECONDS_CEILING = 100_000_000_000L

    /** 2000-01-01. Nothing exported a password before this that anybody is importing now. */
    private const val EARLIEST_PLAUSIBLE = 946_684_800_000L
}
