package com.citation.core.reader

/**
 * A font the reader added, as the reader sees it: where the file is, and what it is called.
 *
 * The two are deliberately not the same string. The file is named by a digest of its bytes —
 * content-addressing is what stops one face accumulating a copy per pick, and it is the right name
 * for a file. It is a hopeless *label*: a picker offering `a3f9c2b1d0e4…` tells a reader nothing
 * about which of their faces that is.
 */
data class ReaderFont(val path: String, val name: String)

/**
 * What a reader's font is called.
 *
 * Seeded from the file they picked and editable afterwards, because a download is called
 * `OpenDyslexic-Regular-webfont-v2.otf` and a reader calls it "OpenDyslexic". Somebody with three
 * weights of the same family, or two faces they are comparing, needs to tell them apart at a
 * glance, and only they know by what.
 */
object ReaderFontNames {

    /** Long enough for a family and a weight, short enough to stay on one line in the picker. */
    const val MAX_LENGTH = 40

    /**
     * Tidy a name a reader typed into one that can be shown on a line: no control characters, no
     * runs of whitespace, no unbounded length. May come back empty, which is the caller's cue that
     * there is no name here rather than an empty one to store.
     */
    fun clean(raw: String): String = raw
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString("")
        .replace(WHITESPACE, " ")
        .trim()
        .take(MAX_LENGTH)
        .trim()

    /**
     * A first name for a font, taken from the file it was picked from.
     *
     * The extension goes because it says nothing a reader wants in a list, and any directory in
     * front of it goes because the name is a label, not a path.
     */
    fun fromFileName(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        return clean(base.substringBeforeLast('.', base))
    }

    /**
     * What to call a font that has no name — one added before names existed, or one whose label was
     * lost. Honest rather than pretty: it names the file, so two of them are still distinguishable,
     * and it invites a rename.
     */
    fun fallback(path: String): String {
        val file = path.substringAfterLast('/').substringAfterLast('\\')
        val stem = file.substringBeforeLast('.', file).filter { !it.isWhitespace() }
        return if (stem.isEmpty()) "Added font" else "Font ${stem.take(6)}"
    }

    /** The name to show for a font: the one the reader gave it, or [fallback]. */
    fun of(path: String, label: String?): String =
        label?.let { clean(it) }?.takeIf { it.isNotEmpty() } ?: fallback(path)

    private val WHITESPACE = Regex("\\s+")
}
