package com.citation.app.data

import com.citation.app.data.db.AnchorCodec
import com.citation.app.data.db.BookEntity
import com.citation.app.data.db.ChapterEntity
import com.citation.app.data.db.HighlightEntity
import com.citation.app.data.db.NoteEntity
import com.citation.core.key.EntityKey
import com.citation.core.model.Book
import com.citation.core.model.BookMetadata
import com.citation.core.model.Chapter
import com.citation.core.model.SourceType
import com.citation.core.note.Highlight
import com.citation.core.note.Note
import com.citation.core.note.NoteType
import com.citation.core.note.SourceDescriptor
import com.citation.core.sync.AcquisitionState
import com.citation.core.sync.BookLifecycle
import com.citation.core.sync.ReadingState

/** Entity ⇄ core-model mappers. The DB stores strings/JSON; the domain works in typed core models. */
object CitationMappers {

    fun bookToEntity(book: Book, descriptor: SourceDescriptor, lifecycle: BookLifecycle, now: Long): BookEntity =
        BookEntity(
            key = book.key!!.toString(),
            title = book.metadata.title,
            author = book.metadata.author,
            sourceType = book.metadata.source.name,
            sourceId = descriptor.sourceId,
            language = book.metadata.language,
            acquisitionState = lifecycle.acquisition.name,
            readingState = lifecycle.reading.name,
            createdAt = now
        )

    fun chapterToEntity(bookKey: String, chapter: Chapter, storeInline: Boolean, now: Long): ChapterEntity =
        ChapterEntity(
            bookKey = bookKey,
            ordinal = chapter.ordinal,
            title = chapter.title,
            sourceRef = chapter.sourceRef,
            text = if (storeInline) chapter.text else null,
            byteSize = chapter.byteSize,
            cachedAt = now
        )

    fun bookFromEntities(book: BookEntity, chapters: List<ChapterEntity>): Book =
        Book(
            key = EntityKey.parse(book.key),
            metadata = BookMetadata(
                title = book.title,
                author = book.author,
                source = SourceType.valueOf(book.sourceType),
                language = book.language
            ),
            chapters = chapters.sortedBy { it.ordinal }.map {
                Chapter(
                    ordinal = it.ordinal,
                    title = it.title,
                    sourceRef = it.sourceRef,
                    text = it.text ?: "",
                    html = null
                )
            }
        )

    fun summaryFromEntity(book: BookEntity): CitationRepository.BookSummary =
        CitationRepository.BookSummary(
            key = book.key,
            title = book.title,
            author = book.author,
            readingState = book.readingState,
            acquisitionState = book.acquisitionState,
            sourceType = book.sourceType,
            lastChapterOrdinal = book.lastChapterOrdinal,
            lastOpenedAt = book.lastOpenedAt
        )

    fun lifecycleOf(book: BookEntity): BookLifecycle =
        BookLifecycle(
            acquisition = AcquisitionState.valueOf(book.acquisitionState),
            reading = ReadingState.valueOf(book.readingState)
        )

    fun descriptorOf(book: BookEntity): SourceDescriptor =
        SourceDescriptor(
            bookKey = EntityKey.parse(book.key),
            sourceType = SourceType.valueOf(book.sourceType),
            sourceId = book.sourceId,
            title = book.title,
            author = book.author
        )

    fun highlightToEntity(h: Highlight): HighlightEntity =
        HighlightEntity(
            key = h.key.toString(),
            bookKey = h.source.bookKey?.toString(),
            sourceType = h.source.sourceType.name,
            sourceId = h.source.sourceId,
            frozenTitle = h.source.title,
            frozenAuthor = h.source.author,
            quotedSnapshot = h.quotedSnapshot,
            anchorJson = AnchorCodec.encodeAnchor(h.anchor),
            createdAt = h.createdAt
        )

    fun noteToEntity(note: Note, syncVersion: Long?): NoteEntity =
        NoteEntity(
            key = note.key.toString(),
            bookKey = note.source.bookKey?.toString(),
            type = note.type.name,
            body = note.body,
            sourceType = note.source.sourceType.name,
            sourceId = note.source.sourceId,
            frozenTitle = note.source.title,
            frozenAuthor = note.source.author,
            referencesJson = AnchorCodec.encodeReferences(note.references),
            createdAt = note.createdAt,
            syncVersion = syncVersion,
            tagsJson = AnchorCodec.encodeTags(note.tags)
        )

    fun noteFromEntity(entity: NoteEntity): Note =
        Note(
            key = EntityKey.parse(entity.key)!!,
            type = NoteType.valueOf(entity.type),
            body = entity.body,
            source = SourceDescriptor(
                bookKey = entity.bookKey?.let { EntityKey.parse(it) },
                sourceType = SourceType.valueOf(entity.sourceType),
                sourceId = entity.sourceId,
                title = entity.frozenTitle,
                author = entity.frozenAuthor
            ),
            references = AnchorCodec.decodeReferences(entity.referencesJson),
            createdAt = entity.createdAt,
            tags = AnchorCodec.decodeTags(entity.tagsJson)
        )
}
