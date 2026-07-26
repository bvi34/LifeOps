package com.citation.core.capture

import com.citation.core.identity.IdentityKey

/**
 * **Cross-app capture provenance** — the answer to "where did this fragment come from?" for a note
 * that arrived from *another app* (a browser selection routed through `PROCESS_TEXT`, a shared link,
 * a Kindle highlight export, a typed quick-note over an unknown app).
 *
 * The design tension: frictionless capture is inherently *thin*. The user highlighted a sentence and
 * tapped "Save to Citation" — we may get a URL, or only the source app's package name, or in the
 * worst case nothing but the wall-clock. The rule is **never leave a note unassigned**: walk an
 * ordered ladder ([ProvenanceLadder]) and attach the *best* identifier the capture actually yielded,
 * all the way down to a timestamp, which still beats "unassigned". Then record **how strong** that
 * identifier is so triage and promotion can act on it later.
 *
 * The ladder produces a [CaptureProvenance] whose [CaptureProvenance.clusterId] is a **self-describing
 * tagged string** (`url:https://…`, `book:9781449373320`, `app:com.amazon.kindle`, `ts:1690000000000`).
 * That single string is what the note carries as its source id, and it is enough to (a) group notes
 * that share it into one provisional source, (b) recover the rung for triage, and (c) recover a hard
 * book identity for promotion — with no extra columns in the note store.
 */

/**
 * How trustworthy a note's attached identifier is.
 *
 * Only a **stable book identity** (an ISBN, a Royal Road fiction id, a Citation book key) is [HARD]:
 * it names a real work and can bind a note straight to its record. Everything else — a URL, a
 * filename, an app package, a timestamp — is [PROVISIONAL]: a usable grouping key, but not proof of a
 * work. Provisional notes cluster on their own and wait for a hard identity to *promote* them (see
 * [CapturePromotion]); until then they are frictionless-but-lossy and belong in triage if thin.
 */
enum class ProvenanceStrength { HARD, PROVISIONAL }

/**
 * The rungs of the provenance fallback ladder, **strongest first**. [ProvenanceLadder] walks these
 * top-down and attaches the first identifier the capture supplies.
 *
 * Each rung knows its [tag] — the prefix that makes a cluster id self-describing — and its
 * [strength]. The ordinal *is* the rung's rank (0 = best), so "which is stronger" is just a compare.
 */
enum class ProvenanceRung(val tag: String, val strength: ProvenanceStrength) {
    /** 1. Stable book identity — ISBN / RR fiction id / Citation book key. The only [HARD] rung. */
    BOOK_IDENTITY("book", ProvenanceStrength.HARD),

    /** 2. The page URL a selection came from. Provisional, but a strong grouping key. */
    URL("url", ProvenanceStrength.PROVISIONAL),

    /** 3. The filename of the document the fragment came from (a PDF/reader with no stable id). */
    FILENAME("file", ProvenanceStrength.PROVISIONAL),

    /**
     * 3½. The work's **title** with no machine identity behind it — a Kindle export without an ISBN,
     * say. Not in the brief's numbered ladder, but the brief clusters explicitly by "same title", so a
     * title is a first-class provisional grouping key: it collapses a book's captures into one cluster
     * and fuzzy-promotes to the real record when the book is added properly. Below FILENAME (a filename
     * pins a document instance) but well above the thin rungs.
     */
    TITLE("title", ProvenanceStrength.PROVISIONAL),

    /** 4. The source app / package name — all an opaque app exposes. Thin: belongs in triage. */
    APP_PACKAGE("app", ProvenanceStrength.PROVISIONAL),

    /** 5. The capture timestamp — the floor. Worst case, but still beats "unassigned". */
    TIMESTAMP("ts", ProvenanceStrength.PROVISIONAL);

    companion object {
        /** The rung a self-describing [clusterId] belongs to, by its `tag:` prefix, or `null`. */
        fun ofClusterId(clusterId: String): ProvenanceRung? {
            val tag = clusterId.substringBefore(':', missingDelimiterValue = "")
            return entries.firstOrNull { it.tag == tag }
        }
    }
}

/**
 * The raw material a capture entry point hands to the ladder — whatever that entry point could
 * gather, most of it optional. The ladder never *fails* on a sparse [RawCapture]; it just lands on a
 * lower rung.
 *
 * @property text the captured passage or typed note — the content itself. Always present.
 * @property bookIdentity a hard identity when the source app exposed one (Kindle ASIN/ISBN, an
 *   O'Reilly urn); this is what lifts a capture to the [ProvenanceRung.BOOK_IDENTITY] rung.
 * @property title a human title for display when known (page `<title>`, Kindle book title).
 * @property author a human author when known (Kindle export carries it).
 * @property url the page/document URL, if any.
 * @property filename the document filename, if any.
 * @property appPackage the sharing app's package name, if the OS surfaced it. Used as the cluster key.
 * @property appLabel a human name for [appPackage] ("Chrome") when it can be resolved, used only for
 *   display — the cluster still keys on the package, so the friendly name never affects grouping.
 * @property location an opaque in-source position token (Kindle location, page fragment) for the
 *   note's anchor — provenance-independent, carried through untouched.
 * @property capturedAt epoch millis; the ladder's guaranteed floor.
 */
data class RawCapture(
    val text: String,
    val bookIdentity: IdentityKey? = null,
    val title: String? = null,
    val author: String? = null,
    val url: String? = null,
    val filename: String? = null,
    val appPackage: String? = null,
    val appLabel: String? = null,
    val location: String? = null,
    val capturedAt: Long
)

/**
 * The identifier the ladder settled on for one capture: which [rung], the self-describing [clusterId],
 * the [strength], and a [displayTitle] legible without any live source.
 *
 * This is frozen onto the note (as its source id + title) and is the *only* provenance state the note
 * needs to carry — clustering, triage, and promotion all re-derive what they need from [clusterId].
 */
data class CaptureProvenance(
    val rung: ProvenanceRung,
    val clusterId: String,
    val displayTitle: String,
    val author: String?
) {
    /** Convenience: the strength of the rung this provenance landed on. */
    val strength: ProvenanceStrength get() = rung.strength

    /** True when the best identifier is only an app name or a timestamp — the triage trigger. */
    val isThin: Boolean get() = rung == ProvenanceRung.APP_PACKAGE || rung == ProvenanceRung.TIMESTAMP

    /** The hard book identity carried in a [ProvenanceRung.BOOK_IDENTITY] cluster id, or `null`. */
    val bookIdentity: IdentityKey? get() = ClusterId.bookIdentityOf(clusterId)
}
