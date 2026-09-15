package com.lifeops.app.data.model



/**
 * Books read, the notes taken against them, and the time spent doing it.
 */

enum class BookStatus {
    TO_READ, READING, DONE;
    companion object {
        fun from(value: String) = entries.firstOrNull { it.name == value } ?: TO_READ
    }
}

data class Book(
    val id: String,
    /** The user-editable GUI title. Sync seeds it once (from [citationTitle]) then never touches it. */
    val title: String,
    val author: String? = null,
    val status: BookStatus = BookStatus.TO_READ,
    val createdAt: String,
    val completedAt: String? = null,
    /** Source kind from Citation (EPUB/PDF/ROYAL_ROAD/OREILLY); null for LifeOps-created books. */
    val sourceType: String? = null,
    /** Derived reading category (LEARNING/FUN) for the reading report; null when unknown. */
    val category: String? = null,
    /** Citation's source id (O'Reilly product id / ISBN / Royal Road id); null when unknown. */
    val sourceId: String? = null,
    /** The title Citation reports — the immutable Citation record, shown alongside the GUI [title]. */
    val citationTitle: String? = null
)

data class BookNote(
    val id: String,
    val bookId: String,
    val content: String,
    val createdAt: String
)

data class BookTimeEntry(
    val id: String,
    val bookId: String,
    val durationMinutes: Int,
    val note: String? = null,
    val recordedAt: String
)
