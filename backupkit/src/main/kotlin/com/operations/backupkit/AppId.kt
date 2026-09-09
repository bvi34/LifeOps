package com.operations.backupkit

/**
 * The apps the Operations Sandbox knows how to back up. This is the *stable* identity used
 * everywhere a payload is addressed — the zip's per-app directory name (`<key>/…`), the manifest's
 * `appId`, and the sandbox's selection UI all key off [key], never off enum ordinal or display name.
 *
 * Adding a new hosted app is authoring, not engineering: add an entry here, ship a
 * [BackupContributor] for it, and register it with the sandbox. Old archives stay readable because
 * the manifest stores the string [key]; an unknown key on restore is simply skipped (see
 * [BackupEngine.restore]).
 */
enum class AppId(val key: String, val defaultDisplayName: String) {
    LIFEOPS("lifeops", "LifeOps"),
    CITATION("citation", "Citation"),
    LOGISTICS("logistics", "Logistics"),
    ADVISOR("advisor", "Advisor"),
    HEALTH("health", "Health"),
    PEOPLE("people", "People"),
    PROJECT("project", "Project"),
    MAINTENANCE("maintenance", "Maintenance"),
    REPOSITORY("repository", "Repository"),
    FINANCE("finance", "Finance"),

    /**
     * The vault. Unlike every other entry here, this app's payload is *meant* to be in the archive
     * while holding credentials — it is a sealed file whose key is a passphrase rather than anything
     * the archive or the phone contains. See `SecretsBackupContributor`, which is the one restore in
     * the suite that deliberately does not overwrite what it finds.
     */
    SECRETS("secrets", "Secrets");

    companion object {
        fun fromKey(key: String): AppId? = entries.firstOrNull { it.key == key }
    }
}
