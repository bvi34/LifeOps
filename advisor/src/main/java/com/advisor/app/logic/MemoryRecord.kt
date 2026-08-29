package com.advisor.app.logic

/**
 * One long-term memory — a fact, preference, event, insight or instruction Advisor should remember
 * across sessions. Memory is Advisor's *own* store (a dedicated, heavily-tagged database), distinct
 * from the live app data it retrieves and from identity; it is never permission-gated.
 *
 * [tags] are the recall backbone — free-form and ideally namespaced (`person:sam`, `topic:health`,
 * `operation:kitchen`) — so a memory can be found by facet as well as by text. [salience] (0–100) and
 * [pinned] are gentle priors that keep important memories reachable even when the wording differs.
 */
data class MemoryRecord(
    val id: String,
    val content: String,
    val kind: String = "note",
    val tags: List<String> = emptyList(),
    val salience: Int = 50,
    val pinned: Boolean = false,
    val source: String = "user",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastRecalledAt: Long? = null,
    val recallCount: Int = 0
) {
    /** Text the recall ranker matches against — the content plus its tags. */
    fun searchableText(): String =
        if (tags.isEmpty()) content else content + " " + tags.joinToString(" ")

    fun hasTag(tag: String): Boolean = tags.any { it.equals(tag, ignoreCase = true) }
}
