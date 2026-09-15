package com.citation.app.data

import com.citation.app.data.db.BlockCodec
import com.citation.app.data.db.OpdsCatalogEntity
import com.citation.app.data.opds.CatalogCredentials
import com.citation.app.data.opds.OpdsClient
import com.citation.app.data.opds.map
import com.citation.core.epub.EpubParser
import com.citation.core.model.SourceType
import com.citation.core.opds.CatalogPage
import com.citation.core.opds.CatalogSource
import com.citation.core.opds.OpdsEntry
import kotlinx.coroutines.flow.map

/**
 * OPDS catalogs: browsing a server, downloading from it, and folding what it says about a book
 * into what Citation already knew.
 *
 * The only part of Citation that talks to a catalog server, and it does so through `OpdsClient` —
 * nothing here opens a socket itself.
 */

internal fun CitationRepository.toSource(row: OpdsCatalogEntity): CatalogSource {
    val creds = catalogCredentials?.credentials(row.id)
    return CatalogSource(
        id = row.id,
        name = row.name,
        url = row.url,
        username = creds?.username,
        password = creds?.password,
        position = row.position
    )
}

/**
 * Seed the catalog list the first time it is opened.
 *
 * The presets are all free and public, and exist so the screen is useful before the user has
 * typed a server address — an empty "add a catalog" form is a worse first impression than three
 * libraries you can browse immediately. Seeding happens once; a user who deletes them all gets
 * an empty list, not the presets back.
 */
internal suspend fun CitationRepository.seedCatalogsIfEmpty(now: Long = System.currentTimeMillis()) {
    if (db.opdsCatalogDao().count() > 0) return
    CatalogSource.presets().forEach { preset ->
        db.opdsCatalogDao().upsert(
            OpdsCatalogEntity(
                id = preset.id,
                name = preset.name,
                url = preset.url,
                position = preset.position,
                createdAt = now
            )
        )
    }
}

internal suspend fun CitationRepository.addCatalog(
    name: String,
    url: String,
    username: String? = null,
    password: String? = null,
    now: Long = System.currentTimeMillis()
): CatalogSource {
    val id = "OPDS-" + now.toString(36)
    val existing = db.opdsCatalogDao().all()
    val entity = OpdsCatalogEntity(
        id = id,
        name = name.trim().ifBlank { CatalogSource.normalizeRoot(url).substringAfter("://").substringBefore('/') },
        url = CatalogSource.normalizeRoot(url),
        position = existing.size,
        createdAt = now
    )
    db.opdsCatalogDao().upsert(entity)
    if (!username.isNullOrBlank()) {
        catalogCredentials?.setCredentials(id, username, password.orEmpty())
    }
    return toSource(entity)
}

/** Remove a catalog and forget its sign-in with it — the secret outliving the row would be a leak. */
internal suspend fun CitationRepository.deleteCatalog(id: String) {
    db.opdsCatalogDao().delete(id)
    catalogCredentials?.clear(id)
}

internal suspend fun CitationRepository.setCatalogCredentials(id: String, username: String, password: String) {
    catalogCredentials?.setCredentials(id, username, password)
}

internal suspend fun CitationRepository.catalog(id: String): CatalogSource? = db.opdsCatalogDao().get(id)?.let { toSource(it) }

/**
 * Every catalogue the household has, by id.
 *
 * Asked for by the vault seam rather than by a screen: after a restore the catalogues are here
 * and their sign-ins are not, and `CatalogCredentials` cannot enumerate what it is missing —
 * only the rows can say which sign-ins should exist. See `SecretSource.rehydrate`.
 */
internal suspend fun CitationRepository.catalogIds(): List<String> = db.opdsCatalogDao().all().map { it.id }

/** Open a catalog page: its root when [url] is null, otherwise the page a link pointed at. */
internal suspend fun CitationRepository.openCatalog(
    source: CatalogSource,
    url: String? = null,
    now: Long = System.currentTimeMillis()
): OpdsClient.Result<CatalogPage> {
    val target = url ?: source.rootUrl
    val result = opdsClient.feed(source, target)
    if (result is OpdsClient.Result.Success) db.opdsCatalogDao().touchOpened(source.id, now)
    return result.map { CatalogPage(source, target, it) }
}

/** Search a catalog from the page currently open (which is what carries the search link). */
internal suspend fun CitationRepository.searchCatalog(page: CatalogPage, terms: String): OpdsClient.Result<CatalogPage> =
    opdsClient.search(page.source, page.feed, terms)
        .map { CatalogPage(page.source, page.url, it, query = terms) }

/** Fetch a cover thumbnail for the catalog browser. Null on any failure — a cover is decoration. */
internal suspend fun CitationRepository.catalogImage(source: CatalogSource, url: String): ByteArray? =
    runCatching { opdsClient.image(source, url) }.getOrNull()

/**
 * Download a catalog entry into the library.
 *
 * The catalog's own metadata is merged over the file's afterwards, because a catalog usually
 * knows more than the file does — a Calibre server has series, tags and a blurb that the EPUB's
 * OPF often omits entirely, and losing that on the way in would make a downloaded book poorer
 * than the row you tapped.
 *
 * Format is decided by what was actually served, not by what the link claimed: servers
 * mislabel, and the bytes do not.
 */
internal suspend fun CitationRepository.acquire(
    source: CatalogSource,
    entry: OpdsEntry,
    now: Long = System.currentTimeMillis()
): AcquireResult {
    // An ISBN the catalog states is strong enough evidence to skip a download entirely.
    entry.isbn?.let { isbn ->
        db.bookDao().findBySource(isbn, SourceType.EPUB.name)?.let {
            return AcquireResult.AlreadyHave(it.key, it.title)
        }
    }

    val link = entry.preferredDownload ?: return AcquireResult.Failed("Nothing to download")

    val download = when (val result = opdsClient.download(source, link.href)) {
        is OpdsClient.Result.Success -> result.value
        is OpdsClient.Result.Unauthorized -> return AcquireResult.Failed("Sign-in required")
        is OpdsClient.Result.HttpError -> return AcquireResult.Failed("Server said ${result.code}")
        is OpdsClient.Result.Unreachable -> return AcquireResult.Failed(result.message)
        is OpdsClient.Result.NotACatalog -> return AcquireResult.Failed("Unexpected response")
        is OpdsClient.Result.Unsupported -> return AcquireResult.Failed("Not offered")
    }

    // Sniff the magic number rather than trusting the served type — the same rule MainActivity
    // uses for a file handed in from outside, and for the same reason.
    val bytes = download.bytes
    return when {
        bytes.looksLikeEpub() -> {
            val book = importEpub(bytes, now) ?: return AcquireResult.Failed("Could not read the EPUB")
            val key = book.key?.toString() ?: return AcquireResult.Failed("Could not read the EPUB")
            mergeCatalogMetadata(key, entry, source)
            AcquireResult.Added(key, book.metadata.title)
        }
        bytes.looksLikePdf() -> {
            val key = importPdf(bytes, entry.title, now)
            mergeCatalogMetadata(key, entry, source)
            AcquireResult.Added(key, entry.title)
        }
        else -> AcquireResult.UnsupportedFormat(link.formatLabel)
    }
}

/**
 * Fill in what the catalog knew and the file did not.
 *
 * Deliberately additive: a value the file supplied wins, because it came from the publisher's
 * own package document, and only the gaps are filled from the catalog row. The exception is the
 * cover — if the book has none and the catalog offers one, fetching it is worth a round trip,
 * since a shelf of blank rectangles is the thing a library screen most needs to avoid.
 */
internal suspend fun CitationRepository.mergeCatalogMetadata(bookKey: String, entry: OpdsEntry, source: CatalogSource) {
    val stored = db.bookDao().get(bookKey) ?: return
    val merged = stored.copy(
        author = stored.author ?: entry.author,
        publisher = stored.publisher ?: entry.publisher,
        published = stored.published ?: entry.published,
        description = stored.description ?: entry.summary,
        series = stored.series ?: entry.series,
        seriesIndex = stored.seriesIndex ?: entry.seriesIndex,
        subjectsJson = CitationMappers.mergeSubjects(stored.subjectsJson, entry.categories),
        sourceId = stored.sourceId ?: entry.isbn
    )
    if (merged != stored) db.bookDao().upsert(merged)

    if (stored.coverPath == null) {
        entry.cover?.let { url ->
            runCatching {
                opdsClient.image(source, url)?.let { bytes ->
                    db.bookDao().setCoverPath(bookKey, files.writeCover(bookKey, bytes).absolutePath)
                }
            }
        }
    }
}

internal fun ByteArray.looksLikeEpub(): Boolean =
    size > 4 && this[0] == 0x50.toByte() && this[1] == 0x4B.toByte()

internal fun ByteArray.looksLikePdf(): Boolean =
    size > 4 && this[0] == 0x25.toByte() && this[1] == 0x50.toByte() &&
        this[2] == 0x44.toByte() && this[3] == 0x46.toByte()

/**
 * Re-read a book from the file it was imported from, to pick up what a newer parser can see.
 *
 * Without this, structure, covers, illustrations and shelf metadata would only ever apply to
 * books added *after* the parser learned to find them — a library built up over a year would
 * stay plain forever, for no reason other than when it happened to be imported. The source file
 * is kept precisely so this is possible.
 *
 * The safety condition is exact and enforced per chapter: structure is written **only where the
 * re-parsed text is byte-identical to the text already stored**. Where it is, the offsets every
 * note anchored against are provably unchanged, so adding structure cannot move an anchor;
 * where it somehow is not, that chapter is left exactly as it was. Titles, reading position,
 * lifecycle and notes are never touched — only derived data is refreshed.
 */
internal suspend fun CitationRepository.refreshFromFile(bookKey: String, now: Long = System.currentTimeMillis()): RefreshResult {
    val entity = db.bookDao().get(bookKey) ?: return RefreshResult.NotRefreshable
    if (entity.sourceType != SourceType.EPUB.name && entity.sourceType != SourceType.AO3.name) {
        return RefreshResult.NotRefreshable
    }
    val file = java.io.File(files.sovereignDir, "$bookKey.epub")
    if (!file.exists()) return RefreshResult.FileMissing

    val parsed = runCatching { EpubParser.parse(file.readBytes()) }.getOrNull()
        ?: return RefreshResult.Unreadable

    val stored = db.chapterDao().forBook(bookKey).associateBy { it.ordinal }
    var updated = 0
    var skipped = 0
    parsed.book.chapters.forEach { chapter ->
        val existing = stored[chapter.ordinal] ?: return@forEach
        if (existing.text != chapter.text) {
            skipped++
            return@forEach
        }
        db.chapterDao().upsert(
            existing.copy(
                blocksJson = chapter.blocks.takeIf { it.isNotEmpty() }?.let { BlockCodec.encodeBlocks(it) },
                anchorsJson = chapter.anchors.takeIf { it.isNotEmpty() }?.let { BlockCodec.encodeAnchors(it) }
            )
        )
        updated++
    }

    val coverPath = storeBookImages(bookKey, parsed) ?: entity.coverPath
    val meta = parsed.book.metadata
    db.bookDao().upsert(
        entity.copy(
            // Derived fields only. The title the user sees, the author, the source binding and
            // both state machines stay as they are — a refresh is not a re-import.
            publisher = entity.publisher ?: meta.publisher,
            published = entity.published ?: meta.published,
            description = entity.description ?: meta.description,
            subjectsJson = CitationMappers.mergeSubjects(entity.subjectsJson, meta.subjects),
            series = entity.series ?: meta.series,
            seriesIndex = entity.seriesIndex ?: meta.seriesIndex,
            coverPath = coverPath,
            chapterCount = if (entity.chapterCount > 0) entity.chapterCount else parsed.book.chapters.size,
            tocJson = entity.tocJson ?: parsed.book.toc.takeIf { !it.isEmpty }?.let { BlockCodec.encodeToc(it) }
        )
    )
    return RefreshResult.Refreshed(chapters = updated, unchanged = skipped)
}
