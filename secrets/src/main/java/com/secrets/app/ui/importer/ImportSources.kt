package com.secrets.app.ui.importer

/**
 * Where a household's passwords actually are, and how each of those places lets them out.
 *
 * ## Why this is a list of instructions rather than a list of connections
 *
 * For the managers that implement **Credential Exchange** it is not: the card above this list hands
 * the whole job to the platform, and that is the better route in every way (see
 * [com.operations.vaultkit.CredentialExchange]). This list is what is left — the managers that have
 * not implemented it, the phones whose Android is older than the transfer, and the passwords that
 * live on a computer rather than on this phone.
 *
 * For those, an import cannot be a connection, and the reason is worth stating because it is not
 * effort:
 *
 *  - **There is no API that enumerates another manager's vault outside the transfer.** Credential
 *    Manager's everyday surface answers one credential at a time, to the app asking, for a sign-in
 *    it is performing. Credential Exchange is the *only* sanctioned way to read the rest, and it
 *    exists precisely because the ordinary API deliberately does not — which is why the card above
 *    uses it and there is nothing else to fall back to.
 *  - **A manager that has not implemented it cannot be reached at all.** Google Password Manager's
 *    store lives inside Play Services' private data and Chrome's inside Chrome's; no exported
 *    provider, no intent, no OAuth scope reads either. For an account-based manager like 1Password
 *    there is no consumer API either, by the same design that makes it worth using: the account is
 *    unlocked by a passphrase and a Secret Key that never leave the device, and the server holds
 *    ciphertext it cannot open.
 *  - **And this module holds no network anyway.** No `INTERNET` permission, no HTTP client on the
 *    classpath. Whatever a remote API offered, this app could not call it — which is the same
 *    property that makes the vault safe to put in a backup.
 *
 * What every one of those places does offer is an export, made locally, at the owner's request,
 * usually behind the device's own lock screen — the same authorisation a hosted connection would
 * have asked for, handed over as a file instead of a token. So the job of this list is to make that
 * handoff short: say exactly where the export lives in each app, and then be somewhere the file can
 * be sent directly (see `ImportShareActivity`) rather than saved, lost, and hunted for in a picker.
 */
enum class ImportSource(
    val label: String,
    /** What this source's export carries, in one line. */
    val holds: String,
    /** Whether the export can be made on this phone, or has to come from a computer. */
    val onDevice: Boolean,
    val steps: List<String>,
    /**
     * A page that explains the export, for a source whose own screens are not on this phone.
     *
     * Null for most of them on purpose: a link that has rotted is worse than no link, so the only
     * one here is the address that *is* the product rather than an article about it.
     */
    val web: String? = null
) {

    CHROMIUM(
        label = "Chrome, Edge or Brave",
        holds = "Addresses, usernames, passwords and notes.",
        onDevice = true,
        steps = listOf(
            "Open the browser's Settings, then Password manager.",
            "Tap the gear, then Export passwords.",
            "Confirm with your fingerprint or screen lock — the browser asks, not this app.",
            "When the share sheet appears, pick Secrets."
        ),
        web = "https://passwords.google.com"
    ),

    FIREFOX(
        label = "Firefox",
        holds = "Addresses, usernames, passwords — and when each password last changed, which this " +
            "app keeps rather than replacing with today.",
        onDevice = false,
        steps = listOf(
            "On a computer, open about:logins in Firefox.",
            "Use the three dots at the top right, then Export logins.",
            "Send the .csv to this phone — a share, a cable, a cloud folder — and open it here."
        )
    ),

    APPLE(
        label = "Safari or Apple Passwords",
        holds = "Addresses, usernames, passwords, notes and second factors.",
        onDevice = false,
        steps = listOf(
            "On a Mac or iPhone, open the Passwords app.",
            "Export all passwords, and confirm with the device's own lock.",
            "Send the .csv to this phone and open it here."
        )
    ),

    ONEPASSWORD(
        label = "1Password",
        holds = "Everything: logins, cards, secure notes, identities, wifi keys, custom fields, " +
            "extra addresses and the passwords each item used to have.",
        onDevice = false,
        steps = listOf(
            "On a computer, open the 1Password desktop app — the export is not in the phone app " +
                "or the web vault.",
            "Pick your account, then Export, and choose the .1pux format. Its .csv works too and " +
                "keeps much less.",
            "Send the file to this phone and open it here."
        )
    ),

    OTHER(
        label = "Something else",
        holds = "Any export with a password, username or note column — Bitwarden, LastPass, " +
            "Dashlane, KeePass and most others write one.",
        onDevice = false,
        steps = listOf(
            "Find the export in whatever holds your passwords now — it is usually under Settings, " +
                "and usually called Export.",
            "A .csv is what almost all of them write.",
            "Send it to this phone and open it here."
        )
    );

    /** What the screen says about where the export has to be made. */
    val whereItIsMade: String
        get() = if (onDevice) {
            "This one can be done on this phone, and the export can be sent straight here."
        } else {
            "This export is made on a computer. Once the file is on this phone — shared, copied or " +
                "downloaded — it can be sent to Secrets or picked below."
        }
}
