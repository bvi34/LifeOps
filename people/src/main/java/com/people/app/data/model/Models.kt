package com.people.app.data.model

import com.people.app.logic.DateKind

/** What the screens work in: entity rows with their string columns resolved into enums. */

data class Person(
    val id: String,
    val personKey: String,
    val name: String,
    val relationship: String?,
    val birthDate: String?,
    val email: String?,
    val phone: String?,
    val note: String?,
    val colorArgb: Long,
    val archived: Boolean,
    val sortOrder: Int,
    val updatedAt: Long
) {
    val initial: String get() = name.trim().firstOrNull()?.uppercase() ?: "?"
}

data class PersonNote(
    val id: String,
    val personId: String,
    val content: String,
    val createdAt: Long
)

data class ImportantDate(
    val id: String,
    val personId: String,
    val label: String,
    val kind: DateKind,
    val monthDay: String,
    val year: Int?,
    val note: String?
)

/** One sync round's outcome, as the roster screen reports it. */
data class SyncStatus(
    val lastRunAt: Long?,
    val peersSeen: List<String>,
    val received: Int,
    val sent: Int,
    val error: String? = null
)
