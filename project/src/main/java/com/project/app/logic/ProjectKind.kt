package com.project.app.logic

/**
 * What kind of thing this project is, and therefore what its pieces are called.
 *
 * This is vocabulary, not behaviour: every project gets the same five sections and the same
 * storage. A manuscript's outline is made of chapters and scenes, a piece of software's is made of
 * features and tasks, and a research project's is made of sections and findings — the same tree,
 * read by someone who thinks in a particular set of nouns. Calling a scene a "task" is the small
 * indignity that makes a writing tool feel like a ticket system, and it costs one enum to avoid.
 *
 * [key] is what the database stores. It is a string rather than an ordinal so a kind can be added
 * or reordered later without silently re-labelling everybody's existing projects.
 */
enum class ProjectKind(
    val key: String,
    val label: String,
    /** What one leaf of the outline is called — the thing you actually sit down and write or do. */
    val piece: String,
    val pieces: String,
    /** What a top-level grouping of those is called. */
    val part: String,
    val parts: String
) {
    WRITING("writing", "Writing", "scene", "scenes", "chapter", "chapters"),
    SOFTWARE("software", "Software", "task", "tasks", "feature", "features"),
    RESEARCH("research", "Research", "finding", "findings", "section", "sections"),
    GENERAL("general", "General", "step", "steps", "phase", "phases");

    /** Whether a word target is a meaningful thing to set. Only prose is measured in words. */
    val tracksWords: Boolean get() = this == WRITING || this == RESEARCH

    companion object {
        fun fromKey(key: String?): ProjectKind = entries.firstOrNull { it.key == key } ?: GENERAL
    }
}
