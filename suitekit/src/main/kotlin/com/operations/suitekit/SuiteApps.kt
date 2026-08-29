package com.operations.suitekit

import com.operations.backupkit.AppId

/**
 * One hosted app as the Operations Sandbox home screen presents it: what to call it, what it is
 * for, which glyph stands for it, and the colour it is known by.
 *
 * The [iconKey] is a *name*, not a drawable: :suitekit is Android-free, so it says "monitor-heart"
 * and lets :suiteui resolve that to a Material icon. Anything that keys off an app still keys off
 * [AppId], so adding an app is one entry here plus one line in the icon map.
 *
 * [defaultAccent] is each app's shipped identity — the hue it wore when it carried its own theme —
 * and stays only a default: the sandbox settings can repaint any app, and that choice is what the
 * app actually renders (see [SuiteAppearance.accentFor]).
 */
data class SuiteAppInfo(
    val appId: AppId,
    val label: String,
    val tagline: String,
    val iconKey: String,
    val defaultAccent: Long
) {
    val key: String get() = appId.key
}

object SuiteApps {

    /** Every hosted app, in the order the home screen lays them out. */
    val all: List<SuiteAppInfo> = listOf(
        SuiteAppInfo(
            appId = AppId.LIFEOPS,
            label = "LifeOps",
            tagline = "Tasks, aspects, weather and the week",
            iconKey = "dashboard",
            defaultAccent = 0xFF6200EEL
        ),
        SuiteAppInfo(
            appId = AppId.HEALTH,
            label = "Health",
            tagline = "Temperatures, symptoms, medicines",
            iconKey = "monitor-heart",
            defaultAccent = 0xFF2C7A7BL
        ),
        SuiteAppInfo(
            appId = AppId.PEOPLE,
            label = "People",
            tagline = "The household directory",
            iconKey = "groups",
            defaultAccent = 0xFF5A5ABFL
        ),
        SuiteAppInfo(
            appId = AppId.PROJECT,
            label = "Project",
            tagline = "Outlines, docs, lore and the board",
            iconKey = "account-tree",
            defaultAccent = 0xFFB45309L
        ),
        SuiteAppInfo(
            appId = AppId.LOGISTICS,
            label = "Logistics",
            tagline = "Pantry, groceries and recipes",
            iconKey = "inventory",
            defaultAccent = 0xFF2F855AL
        ),
        SuiteAppInfo(
            appId = AppId.CITATION,
            label = "Citation",
            tagline = "Library, reader and notes",
            iconKey = "menu-book",
            defaultAccent = 0xFF4A5568L
        ),
        SuiteAppInfo(
            appId = AppId.ADVISOR,
            label = "Advisor",
            tagline = "Grounded answers across the suite",
            iconKey = "psychology",
            defaultAccent = 0xFF9333EAL
        )
    )

    private val byId: Map<AppId, SuiteAppInfo> = all.associateBy { it.appId }

    /**
     * The catalogue entry for [appId]. Every [AppId] has one — the completeness of this map is a
     * unit test, so a newly hosted app cannot reach the home screen without a name and a colour.
     */
    fun of(appId: AppId): SuiteAppInfo = byId.getValue(appId)

    fun byKey(key: String): SuiteAppInfo? = all.firstOrNull { it.key == key }
}
