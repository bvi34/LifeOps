package com.citation.app.data

import com.citation.app.data.db.ReaderSettingsCodec
import com.citation.app.data.db.ReaderSettingsEntity
import com.citation.app.data.db.ReadingPaceEntity
import com.citation.core.reader.ReaderFont
import com.citation.core.reader.ReaderSettings
import com.citation.core.reader.ReadingPace
import com.citation.core.store.Store
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * How the reader looks, and how fast it is being read.

 * Settings and pace together because both are facts about the reading rather than about the book:
 * neither survives a re-import, and neither means anything to another reader.
 */

/**
 * The settings a book actually opens with: its own if it has been given any, otherwise the
 * global ones.
 */
internal fun CitationRepository.readerSettingsFor(bookKey: String): Flow<ReaderSettings> =
    combine(
        db.readerSettingsDao().observe(ReaderSettingsEntity.GLOBAL),
        db.readerSettingsDao().observe(bookKey)
    ) { global, own ->
        ReaderSettingsCodec.decode(own?.settingsJson ?: global?.settingsJson)
    }

/** Whether this book has been given settings of its own. */
internal suspend fun CitationRepository.hasOwnReaderSettings(bookKey: String): Boolean =
    db.readerSettingsDao().get(bookKey) != null

/**
 * Save settings, either globally or for one book.
 *
 * [bookKey] null writes the global set. Writing a book's own set forks it entirely rather than
 * layering a patch, so what you see is what that book keeps until you put it back on the
 * global settings.
 */
internal suspend fun CitationRepository.saveReaderSettings(
    settings: ReaderSettings,
    bookKey: String? = null,
    now: Long = System.currentTimeMillis()
) {
    db.readerSettingsDao().upsert(
        ReaderSettingsEntity(
            bookKey = bookKey ?: ReaderSettingsEntity.GLOBAL,
            settingsJson = ReaderSettingsCodec.encode(settings.sanitized()),
            updatedAt = now
        )
    )
}

/** Put a book back on the global settings. */
internal suspend fun CitationRepository.clearReaderSettings(bookKey: String) = db.readerSettingsDao().delete(bookKey)

/**
 * Store a font the reader picked, and return the path to use.
 *
 * Kept in the sovereign store because a book set in a face that vanishes is a book that changes
 * appearance for no reason the reader can see. Named by a digest of its bytes, so picking the
 * same file twice does not accumulate copies — and labelled with [pickedName], the file the
 * reader chose it from, since the digest is no use to anybody reading a list.
 */
internal suspend fun CitationRepository.storeReaderFont(bytes: ByteArray, extension: String, pickedName: String? = null): String? =
    runCatching { files.writeReaderFont(bytes, extension, pickedName).absolutePath }.getOrNull()

/** Fonts the reader has added, for the picker to offer again without a second trip to the files. */
internal fun CitationRepository.readerFonts(): List<ReaderFont> = files.readerFonts()

/** Rename a font the reader added. False when the name is empty or the font has gone. */
internal suspend fun CitationRepository.renameReaderFont(path: String, name: String): Boolean =
    runCatching { files.renameReaderFont(path, name) }.getOrDefault(false)

/**
 * Remove a font the reader added.
 *
 * Books still set in it are not rewritten: the face falls back to sans on the next render, which
 * is the same thing that happens to any font that has gone, and is why a missing font has never
 * been able to make a book unopenable.
 */
internal suspend fun CitationRepository.deleteReaderFont(path: String): Boolean =
    runCatching { files.deleteReaderFont(path) }.getOrDefault(false)

// --- Reading pace ----------------------------------------------------------------------------
//
// Observed, never assumed. The reading meter already refuses to count time you were not
// reading, so characters-per-minute can simply be measured — which is what lets a time estimate
// be shown at all without inventing a words-per-minute for the user.

/**
 * Record a stretch of reading against a book and against the reader overall.
 *
 * Both are kept because neither alone is right: a book with little history of its own is best
 * estimated from how this person reads generally, and a book with plenty is best estimated from
 * itself — a dense technical book and a novel are not read at the same speed. Implausible
 * samples are dropped inside [ReadingPace], so a jump-to-chapter cannot poison an estimate real
 * reading built.
 */
internal suspend fun CitationRepository.recordPace(
    bookKey: String,
    characters: Int,
    engagedMillis: Long,
    now: Long = System.currentTimeMillis()
) {
    if (characters <= 0 || engagedMillis <= 0) return
    listOf(bookKey, ReadingPaceEntity.GLOBAL).forEach { scope ->
        val stored = db.readingPaceDao().get(scope)
        val updated = ReadingPace(stored?.characters ?: 0, stored?.millis ?: 0)
            .observe(characters, engagedMillis)
        db.readingPaceDao().upsert(
            ReadingPaceEntity(
                bookKey = scope,
                characters = updated.characters,
                millis = updated.millis,
                updatedAt = now
            )
        )
    }
}

/**
 * The pace to estimate this book with: its own once it is confident, otherwise the reader's
 * overall pace, otherwise nothing.
 */
internal suspend fun CitationRepository.paceFor(bookKey: String): ReadingPace {
    val own = db.readingPaceDao().get(bookKey)?.let { ReadingPace(it.characters, it.millis) }
    if (own != null && own.confident) return own
    val global = db.readingPaceDao().get(ReadingPaceEntity.GLOBAL)
    return global?.let { ReadingPace(it.characters, it.millis) } ?: ReadingPace()
}
