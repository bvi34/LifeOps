package com.lifeops.app.data.model



/**
 * What the app looks like, and what a restore is about to do to it.
 */

/**
 * Appearance is suite-wide now: the preset, the custom palette and the light/dark mode are owned by
 * the Operations Sandbox (`:suitekit`) and obeyed by every hosted app, not by LifeOps alone. These
 * aliases keep LifeOps' own vocabulary — its settings screen, its view models and its backup JSON
 * all still say `ThemePreset` and `CustomPalette` — while there is only one type, one set of
 * presets and one place the choice is stored.
 *
 * The alias targets carry the same field names and defaults LifeOps has always written, so an old
 * backup still restores its palette unchanged.
 */
typealias CustomPalette = com.operations.suitekit.SuitePalette

typealias ThemePreset = com.operations.suitekit.SuitePreset

data class ImportPreview(
    val newTasks: List<Task>,
    val newAspects: List<Aspect>,
    val newCategories: List<Category>,
    val existingTaskCount: Int,
    // Each entry: task title → list of "field: value" strings for unknown fields
    val unknownFieldsByTask: List<Pair<String, List<String>>> = emptyList()
)
