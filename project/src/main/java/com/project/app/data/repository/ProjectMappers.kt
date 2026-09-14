package com.project.app.data.repository

import com.project.app.data.db.entities.BoardCardEntity
import com.project.app.data.db.entities.BoardColumnEntity
import com.project.app.data.db.entities.DocBlockEntity
import com.project.app.data.db.entities.DocEntity
import com.project.app.data.db.entities.DocRevisionBlockEntity
import com.project.app.data.db.entities.DocRevisionEntity
import com.project.app.data.db.entities.LoreEntryEntity
import com.project.app.data.db.entities.OutlineNodeEntity
import com.project.app.data.db.entities.ProjectEntity
import com.project.app.data.db.entities.TimelineEventEntity
import com.project.app.data.model.Doc
import com.project.app.data.model.Project
import com.project.app.logic.AttachKind
import com.project.app.logic.BlockType
import com.project.app.logic.BoardCard
import com.project.app.logic.BoardColumn
import com.project.app.logic.DocBlock
import com.project.app.logic.DocRevision
import com.project.app.logic.Lore
import com.project.app.logic.LoreCategory
import com.project.app.logic.LoreEntry
import com.project.app.logic.OutlineNode
import com.project.app.logic.OutlineStatus
import com.project.app.logic.ProjectKind
import com.project.app.logic.RevisionReason
import com.project.app.logic.TimelineEvent

/**
 * Rows in, models out.
 *
 * Split out of the stores because they are a different kind of code: a store decides *when* to
 * write, these decide only what a row looks like once it has been read. Nothing here touches the
 * database or makes a judgement.
 */

// --- mapping ---

fun ProjectEntity.toModel() = Project(
    id = id,
    name = name,
    kind = ProjectKind.fromKey(kind),
    summary = summary,
    colorArgb = colorArgb,
    archived = archived,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun OutlineNodeEntity.toLogic() = OutlineNode(
    id = id,
    parentId = parentId,
    title = title,
    synopsis = synopsis,
    status = OutlineStatus.fromKey(status),
    targetWords = targetWords,
    actualWords = actualWords,
    sortOrder = sortOrder
)

fun DocEntity.toModel() = Doc(
    id = id,
    projectId = projectId,
    parentDocId = parentDocId,
    title = title,
    icon = icon,
    outlineNodeId = outlineNodeId,
    wordCount = wordCount,
    sortOrder = sortOrder,
    updatedAt = updatedAt
)

fun DocBlockEntity.toLogic() = DocBlock(
    id = id,
    type = BlockType.fromKey(type),
    text = text,
    checked = checked
)

fun DocRevisionEntity.toLogic() = DocRevision(
    id = id,
    docId = docId,
    reason = RevisionReason.fromKey(reason),
    wordCount = wordCount,
    savedAt = savedAt
)

fun DocRevisionBlockEntity.toLogic() = DocBlock(
    id = id,
    type = BlockType.fromKey(type),
    text = text,
    checked = checked
)

fun LoreEntryEntity.toLogic() = LoreEntry(
    id = id,
    name = name,
    category = LoreCategory.fromKey(category),
    summary = summary,
    body = body,
    aliases = Lore.parseAliases(aliases)
)

fun TimelineEventEntity.toLogic() = TimelineEvent(
    id = id,
    title = title,
    detail = detail,
    era = era,
    whenLabel = whenLabel,
    order = sortOrder,
    outlineNodeId = outlineNodeId
)

fun BoardColumnEntity.toLogic() = BoardColumn(
    id = id,
    name = name,
    sortOrder = sortOrder,
    wipLimit = wipLimit,
    isDone = isDone
)

fun BoardCardEntity.toLogic() = BoardCard(
    id = id,
    columnId = columnId,
    title = title,
    notes = notes,
    sortOrder = sortOrder,
    outlineNodeId = outlineNodeId,
    docId = docId,
    dueOn = dueOn,
    publishToLifeOps = publishToLifeOps,
    createdAt = createdAt,
    doneAt = doneAt
)

/** A card and the project it belongs to — all a route needs before it can act on one. */
data class CardOwner(val cardId: String, val projectId: String)

/** A record files can be filed on, and how its drawer reads on the household's shelf. */
data class AttachTarget(
    val recordKey: String,
    val kind: AttachKind,
    /** The record's own name — what the screen is titled. */
    val name: String,
    /** The project-led label the shelf shows, so a drawer is not a question. */
    val shelfLabel: String
)
