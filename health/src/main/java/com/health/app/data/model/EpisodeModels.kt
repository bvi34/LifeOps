package com.health.app.data.model



/**
 * Illnesses, and the free-text notes kept during one.
 */

/** What a care-log entry is about. Free text carries the detail; this is only for grouping. */
enum class CareKind(val key: String, val label: String) {
    NOTE("note", "Note"),
    FLUIDS("fluids", "Fluids"),
    REST("rest", "Rest / sleep"),
    APPOINTMENT("appointment", "Doctor / appointment"),
    TEST("test", "Test result");

    companion object {
        fun fromKey(key: String?): CareKind = entries.firstOrNull { it.key == key } ?: NOTE
    }
}

data class Episode(
    val id: String,
    val profileId: String,
    val title: String,
    val startedAt: Long,
    val endedAt: Long?,
    val note: String?
) {
    val isOpen: Boolean get() = endedAt == null
}

data class CareNote(
    val id: String,
    val profileId: String,
    val episodeId: String?,
    val kind: CareKind,
    val text: String,
    val at: Long
)
