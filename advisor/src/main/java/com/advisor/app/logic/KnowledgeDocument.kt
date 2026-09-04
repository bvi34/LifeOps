package com.advisor.app.logic

/**
 * The apps Advisor can draw knowledge from. The [key] is the stable identity used in permission
 * records and document ids; it deliberately matches the sandbox's `AppId.key` so a granted
 * permission and a backed-up app line up by string, never by enum ordinal.
 */
enum class SourceApp(val key: String, val displayName: String) {
    LIFEOPS("lifeops", "LifeOps"),
    CITATION("citation", "Citation"),
    LOGISTICS("logistics", "Logistics"),
    HEALTH("health", "Health"),
    PEOPLE("people", "People"),
    PROJECT("project", "Project"),
    MAINTENANCE("maintenance", "Maintenance"),
    REPOSITORY("repository", "Repository");

    companion object {
        fun fromKey(key: String): SourceApp? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One retrievable unit of the user's own data — a task, an aspect, a book, a pantry line, … —
 * flattened into plain text so the retriever and the model never need to know which app it came
 * from. This is the *only* shape the RAG core sees; the Android knowledge sources
 * (`data/source/`) are responsible for turning each app's rows into these.
 *
 * [id] is globally unique and human-legible (`"<source>:<kind>:<rowId>"`) so a citation can be
 * traced back to its origin. [timestamp] is epoch millis when known (0 = unknown) and is only used
 * as a recency tie-break in ranking, never as a hard filter.
 */
data class KnowledgeDocument(
    val id: String,
    val source: SourceApp,
    val kind: String,
    val title: String,
    val body: String,
    val timestamp: Long = 0L
)

/** A [KnowledgeDocument] the retriever selected, with the relevance [score] it earned. */
data class RetrievedChunk(
    val document: KnowledgeDocument,
    val score: Double
)
