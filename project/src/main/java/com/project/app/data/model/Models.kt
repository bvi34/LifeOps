package com.project.app.data.model

import com.project.app.logic.LoreCategory
import com.project.app.logic.ProjectKind

/**
 * What the screens work in: entity rows with their string columns resolved into enums.
 *
 * The outline, board, timeline and lore shapes the screens actually reason about are the *logic*
 * types (`OutlineNode`, `BoardCard`, `TimelineEvent`, `LoreEntry`) — they are declared there rather
 * than here so the tree walks, the moves and the wiki index stay unit-testable on the JVM. What
 * lives in this file is the handful of models with no logic behind them.
 */

data class Project(
    val id: String,
    val name: String,
    val kind: ProjectKind,
    val summary: String?,
    val colorArgb: Long,
    val archived: Boolean,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long
) {
    val initial: String get() = name.trim().firstOrNull()?.uppercase() ?: "?"
}

/** A document as a list shows it — its blocks are loaded only when it is opened. */
data class Doc(
    val id: String,
    val projectId: String,
    val parentDocId: String?,
    val title: String,
    val icon: String?,
    val outlineNodeId: String?,
    val wordCount: Int,
    val sortOrder: Int,
    val updatedAt: Long
)

/** One document with its blocks — what the editor holds. */
data class DocContent(
    val doc: Doc,
    val blocks: List<com.project.app.logic.DocBlock>
)

/** A lore entry with the links out of it already resolved. */
data class LoreEntryView(
    val entry: com.project.app.logic.LoreEntry,
    val category: LoreCategory,
    val colorArgb: Long,
    val links: List<com.project.app.logic.LoreMention>,
    val backlinkIds: List<String>
)
