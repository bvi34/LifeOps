package com.project.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Project's tables.
 *
 * Eight tables, one project id threaded through all of them. That single column is the whole
 * architecture: every section is scoped to a project and cascades with it, so deleting a project
 * cannot leave a doc, a lore entry or a board card behind to be found later by a query that forgot
 * to filter. The cross-section links — a card that points at an outline node, a document that
 * belongs to a chapter — are deliberately **nullable and soft**: they are declared without a foreign
 * key so that deleting a scene does not delete the notes written about it. A dangling link resolves
 * to nothing and the row says "no longer linked", which is recoverable; a cascade would not be.
 */

/**
 * One project on the shelf.
 *
 * [kind] is vocabulary (see `logic/ProjectKind`) rather than behaviour — a manuscript and a piece
 * of software get the same five sections, and only the nouns change.
 */
@Entity(
    tableName = "projects",
    indices = [Index("archived"), Index("sortOrder")]
)
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** `logic/ProjectKind.key`. A string, so a kind can be added without renaming everyone's rows. */
    val kind: String,
    val summary: String?,
    val colorArgb: Long,
    val archived: Boolean,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * One node of a project's outline — an act, a chapter, a scene, an epic, a task.
 *
 * The tree is a parent pointer plus a sort order among siblings, which is the shape that survives
 * an arbitrary drag without renumbering the world. [parentId] carries **no foreign key on purpose**:
 * a self-referencing cascade would delete a subtree behind the app's back, and deleting an outline
 * branch is a decision the user should be shown the size of first (see `Outline.subtree`).
 *
 * [actualWords] is a stored number rather than one summed from the linked document, because an
 * outline is written before there is anything to count and a scene may be drafted somewhere else
 * entirely. When a doc *is* linked, the repository keeps this in step with it.
 */
@Entity(
    tableName = "outline_nodes",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId"), Index("parentId"), Index("sortOrder")]
)
data class OutlineNodeEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val parentId: String?,
    val title: String,
    val synopsis: String?,
    /** `logic/OutlineStatus.key`. */
    val status: String,
    /** 0 means "no target", which is not the same as a target of zero. */
    val targetWords: Int,
    val actualWords: Int,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * One document. [outlineNodeId] is the soft link that makes a doc "the text of" a scene, and
 * [parentDocId] is what makes docs a folder tree rather than a flat list.
 */
@Entity(
    tableName = "docs",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId"), Index("parentDocId"), Index("outlineNodeId"), Index("sortOrder")]
)
data class DocEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val parentDocId: String?,
    val title: String,
    /** An emoji, the way a Notion page carries one. Null is perfectly normal. */
    val icon: String?,
    /** The scene/task this document is the text of, when it is one. Soft link; may dangle. */
    val outlineNodeId: String?,
    /** Cached from the blocks so a list of two hundred docs does not have to load them all. */
    val wordCount: Int,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * One block of one document.
 *
 * Blocks rather than a single text column because that is what an editor needs in order to reorder,
 * retype and tick a line without rewriting the whole document — and because a to-do's ticked state
 * is a fact about a line, not a character in it. The Markdown round trip lives in `logic/DocBlocks`.
 */
@Entity(
    tableName = "doc_blocks",
    foreignKeys = [
        ForeignKey(
            entity = DocEntity::class,
            parentColumns = ["id"],
            childColumns = ["docId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("docId"), Index("sortOrder")]
)
data class DocBlockEntity(
    @PrimaryKey val id: String,
    val docId: String,
    /** `logic/BlockType.key`. */
    val type: String,
    val text: String,
    /** Meaningful only for to-do blocks. */
    val checked: Boolean,
    val sortOrder: Int
)

/**
 * One entry in the project's wiki.
 *
 * [aliases] is a comma-separated column rather than its own table, deliberately. Aliases are a
 * handful of short strings per entry that are only ever read all at once, to build the name index
 * (`logic/Lore.index`); a join table would buy nothing and cost a query on every screen.
 */
@Entity(
    tableName = "lore_entries",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId"), Index("name"), Index("category")]
)
data class LoreEntryEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val name: String,
    /** `logic/LoreCategory.key`. */
    val category: String,
    val summary: String?,
    /** The body, with `[[wiki links]]` left in it exactly as typed. */
    val body: String,
    val colorArgb: Long,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val aliases: String?
)

/**
 * One event on the project's timeline.
 *
 * [whenLabel] is free text and [order] is the author's own sequence, kept apart on purpose: the
 * label may say "the night before the coronation", and the timeline still has to draw it somewhere.
 * See `logic/Timeline` for why deriving one from the other loses the app's most useful check.
 */
@Entity(
    tableName = "timeline_events",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId"), Index("sortOrder")]
)
data class TimelineEventEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val title: String,
    val detail: String?,
    /** The age, arc or era this sits in. Free text; blank means "no era". */
    val era: String?,
    val whenLabel: String?,
    /** The author's ordering — this, not the label, is what the screen draws by. */
    val sortOrder: Int,
    /** The scene this event happens in, when it happens in one. Soft link; may dangle. */
    val outlineNodeId: String?,
    val createdAt: Long
)

/** One column of a project's implementation board. */
@Entity(
    tableName = "board_columns",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId"), Index("sortOrder")]
)
data class BoardColumnEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val name: String,
    val sortOrder: Int,
    /** Null for no limit. A limit warns; it never refuses (see `logic/Board`). */
    val wipLimit: Int?,
    val isDone: Boolean
)

/**
 * One card on the board.
 *
 * [columnId] carries no foreign key: deleting a column must not take the work in it with it. The
 * cards are left stranded, on purpose, and `Board.orphans` is what finds them again.
 *
 * [doneAt] is stored rather than inferred from the column so a card keeps the day it was actually
 * finished even if the board is reorganised around it afterwards.
 */
@Entity(
    tableName = "board_cards",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId"), Index("columnId"), Index("sortOrder")]
)
data class BoardCardEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val columnId: String,
    val title: String,
    val notes: String?,
    val sortOrder: Int,
    /** Soft links into the other sections. Either may dangle; neither cascades. */
    val outlineNodeId: String?,
    val docId: String?,
    val createdAt: Long,
    val doneAt: Long?
)
