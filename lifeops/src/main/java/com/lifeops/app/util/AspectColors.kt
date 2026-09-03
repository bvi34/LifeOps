package com.lifeops.app.util

import com.operations.suitekit.SuiteSwatches

/**
 * The colours offered when creating or editing an aspect.
 *
 * These are the suite's swatches, not LifeOps' own: an aspect ring and a Health profile dot end up
 * side by side on the sandbox home screen, so the palette is a decision the suite makes once.
 */
val aspectColorPalette: List<String> = SuiteSwatches.PALETTE

/** Picks the first palette color not already used by an existing aspect, cycling if all are taken. */
fun nextAspectColor(usedColors: List<String>): String = SuiteSwatches.next(usedColors)
