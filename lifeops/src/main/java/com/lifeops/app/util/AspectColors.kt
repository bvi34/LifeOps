package com.lifeops.app.util

/** Curated set of visually distinct colors offered when creating/editing an aspect. */
val aspectColorPalette = listOf(
    "#6200EE", // Purple
    "#00BFA5", // Teal
    "#FF6D00", // Orange
    "#D81B60", // Pink
    "#43A047", // Green
    "#1E88E5", // Blue
    "#F4511E", // Deep Orange
    "#8E24AA", // Violet
    "#00ACC1", // Cyan
    "#FDD835", // Yellow
    "#6D4C41", // Brown
    "#5C6BC0", // Indigo
)

/** Picks the first palette color not already used by an existing aspect, cycling if all are taken. */
fun nextAspectColor(usedColors: List<String>): String {
    val used = usedColors.map { it.uppercase() }.toSet()
    return aspectColorPalette.firstOrNull { it.uppercase() !in used }
        ?: aspectColorPalette[usedColors.size % aspectColorPalette.size]
}
