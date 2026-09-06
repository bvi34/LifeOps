package com.project.app.logic

/**
 * The things inside a project that can have files filed on them.
 *
 * A project's *files* — the brief, the contract, the reference PDFs somebody was sent — are
 * documents the household filed rather than writing the project is made of, so they live on the
 * suite's shelf (see `:repository`) and are shown here in place. Until now they could only be filed
 * on the **project**, which meant a reference photo for one scene, the signed contract for one
 * piece of work and a map for one lore entry all landed in the same flat pile.
 *
 * These are the records they can be filed on instead. A document is deliberately not among them:
 * a document *is* the writing, and a file attached to writing is either a reference (which belongs
 * on the piece of the outline the writing is for) or a copy of the writing itself (which belongs on
 * the shelf on its own).
 */
enum class AttachKind(
    /** What the route calls it, and therefore what a link to a record's files says. */
    val key: String,
    /** How to describe it in a sentence, when the record itself has gone. */
    val noun: String
) {
    PROJECT("project", "project"),
    OUTLINE("outline", "piece of the outline"),
    LORE("lore", "lore entry"),
    CARD("card", "card");

    companion object {
        fun fromKey(key: String?): AttachKind? = entries.firstOrNull { it.key == key }
    }
}

object Attachments {

    /**
     * How a record's drawer reads on the household's shelf.
     *
     * The shelf lists everything the household has been filed across every app, so a drawer called
     * "The docks" is a question rather than an answer — the project has to lead, exactly as it does
     * for a card handed to the LifeOps week. Repository stores this as a **label**, not a foreign
     * key, which is what lets it show a project's paperwork without knowing what a project is; the
     * cost is that a rename has to be pushed down, and `ProjectRepository` does that.
     */
    fun shelfLabel(projectName: String, recordName: String?): String {
        val project = projectName.trim().ifEmpty { "Project" }
        val record = recordName?.trim().orEmpty()
        return if (record.isEmpty()) project else "$project — $record"
    }
}
