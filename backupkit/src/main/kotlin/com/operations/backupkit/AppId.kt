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
    HEALTH("health", "Health");

    companion object {
        fun fromKey(key: String): AppId? = entries.firstOrNull { it.key == key }
    }
}
