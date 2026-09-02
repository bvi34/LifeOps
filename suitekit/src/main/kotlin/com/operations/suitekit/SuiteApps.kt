package com.operations.suitekit

import com.operations.backupkit.AppId

/**
 * One hosted app as the Operations Sandbox home screen presents it: what to call it, what it is
 * for, which glyph stands for it, and the colour it is known by.
 *
 * The [iconKey] is a *name*, not a drawable: :suitekit is Android-free, so it says "thermometer"
 * and lets :suiteui resolve that to one of the suite's own hand-drawn marks (`SuiteGlyphs`).
 * Anything that keys off an app still keys off [AppId], so adding an app is one entry here plus one
 * mark over there. The names describe the *drawing*, not the app, because that is what a reader
 * checking whether two apps look alike needs to compare.
 *
 * [defaultAccent] is each app's shipped identity — the hue it wore when it carried its own theme —
 * and stays only a default: the sandbox settings can repaint any app, and that choice is what the
 * app actually renders (see [SuiteAppearance.accentFor]).
 *
 * [iconColors] is the exception to all of that: an app that already owns a coloured icon keeps it,
 * rather than having its mark flattened to one hue. Almost none do — see [SuiteIconColors].
 */
data class SuiteAppInfo(
    val appId: AppId,
    val label: String,
    val tagline: String,
    val iconKey: String,
    val defaultAccent: Long,
    val iconColors: SuiteIconColors? = null
) {
    val key: String get() = appId.key
}

/**
 * The colours an app draws its *own* icon in, for the apps that ship one.
 *
 * Most don't: their mark is a single-colour line drawing, and the home screen paints it in the
 * app's accent. LifeOps does — its launcher icon has always been a purple dial with an amber
 * checkmark ([line] and [highlight] here are LifeOps' `icon_dial` and `icon_check` colour tokens,
 * `res/values/colors.xml` and `assets/icon-themes.css`) — and those two colours are how the app is
 * recognised, so its tile is drawn with them instead of with the one accent.
 *
 * [line] is the structural drawing (a dial's ring and its ticks); [highlight] is the single stroke
 * that carries the identity (the checkmark that stands in for the needle). Two roles, because the
 * marks are line art: a mark needing a third colour is a mark that has stopped being one.
 */
data class SuiteIconColors(val line: Long, val highlight: Long)

object SuiteApps {

    /** Every hosted app, in the order the home screen lays them out. */
    val all: List<SuiteAppInfo> = listOf(
        SuiteAppInfo(
            appId = AppId.LIFEOPS,
            label = "LifeOps",
            tagline = "Tasks, aspects, weather and the week",
            iconKey = "lifeops-dial",
            defaultAccent = 0xFF6200EEL,
            iconColors = SuiteIconColors(line = 0xFF9B72CFL, highlight = 0xFFFFB74DL)
        ),
        SuiteAppInfo(
            appId = AppId.HEALTH,
            label = "Health",
            tagline = "Temperatures, symptoms, medicines",
            iconKey = "thermometer",
            defaultAccent = 0xFF2C7A7BL
        ),
        SuiteAppInfo(
            appId = AppId.PEOPLE,
            label = "People",
            tagline = "The household directory",
            iconKey = "household",
            defaultAccent = 0xFF5A5ABFL
        ),
        SuiteAppInfo(
            appId = AppId.PROJECT,
            label = "Project",
            tagline = "Outlines, docs, lore and the board",
            iconKey = "board",
            defaultAccent = 0xFFB45309L
        ),
        SuiteAppInfo(
            appId = AppId.MAINTENANCE,
            label = "Maintenance",
            tagline = "Assets, upkeep and what they cost",
            iconKey = "wrench",
            defaultAccent = 0xFFB91C1CL
        ),
        SuiteAppInfo(
            appId = AppId.REPOSITORY,
            label = "Repository",
            tagline = "Every document, filed once and findable",
            iconKey = "folder-shelf",
            defaultAccent = 0xFF7B5E3BL
        ),
        SuiteAppInfo(
            appId = AppId.LOGISTICS,
            label = "Logistics",
            tagline = "Pantry, groceries and recipes",
            iconKey = "basket",
            defaultAccent = 0xFF2F855AL
        ),
        SuiteAppInfo(
            appId = AppId.CITATION,
            label = "Citation",
            tagline = "Library, reader and notes",
            iconKey = "open-book",
            defaultAccent = 0xFF4A5568L
        ),
        SuiteAppInfo(
            appId = AppId.ADVISOR,
            label = "Advisor",
            tagline = "Grounded answers across the suite",
            iconKey = "answer-spark",
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
