package com.citation.core.kindle

/**
 * Deep-linking + position-reading for **Kindle read-in-place** on `read.amazon.com` (the Kindle Cloud
 * Reader). This is the live counterpart to [KindleNotebook]'s after-the-fact export: instead of
 * importing highlights you already made, you read the book *in place* in Amazon's own web reader —
 * exactly like [com.citation.core.oreilly.OreillyLink] does for O'Reilly.
 *
 * The reader's content is DRM'd and licensed, so Citation caches **nothing** and, critically, the
 * page **won't let you copy the passage text** — the selection is suppressed by design. What the page
 * *does* expose, in the clear, is the footer's own position label ("Location 156 of 3866 · 4%"). So a
 * Kindle note is honest about its limits: it cites the **location**, not the words. The position label
 * stands in as the quote (until/unless copying ever works), your annotation is your own, and the book
 * is the source.
 *
 * Position on Kindle is server-side (Whispersync), so simply reopening `?asin=…` resumes at your
 * furthest spot — there's no location to route in the URL. The location we read is therefore *your
 * layer only*: the anchor a note cites and the human "where you were" we keep, never a navigation
 * target. Re-auth is expected and left to the WebView (Amazon's own sign-in, persisted by cookies),
 * so this concerns only the *destination* and the *position readout*.
 */
object KindleLink {

    private const val HOST = "https://read.amazon.com"

    /**
     * The URL that opens [asin] in the Kindle Cloud Reader. Whispersync resumes your furthest position
     * on open, so the ASIN alone is the whole destination — there is no position to encode.
     */
    fun readerUrl(asin: String): String = "$HOST/?asin=${asin.trim()}"

    /**
     * Where a **browse** session lands: your own Kindle library on `read.amazon.com` — the Cloud
     * Reader's book grid. This is the read-in-place counterpart to Browse O'Reilly: instead of typing
     * an ASIN and title, you skim the shelf you already own and *tap* a book, and Citation learns its
     * ASIN + title for you (see [libraryProbeScript] / [cleanTitle]). Amazon keeps you signed in via
     * cookies, so there's no library proxy or stored credential here — you sign in once in the WebView.
     */
    fun libraryUrl(): String = "$HOST/kindle-library"

    /**
     * The ASIN carried by a `read.amazon.com` URL's `?asin=` parameter, or `null` when the URL isn't a
     * Kindle reader link. Tolerant of extra query params (`&ref_=…`) and the reader's own fragments.
     */
    fun asinOf(url: String): String? {
        if (!url.contains("read.amazon.")) return null
        return ASIN_PARAM.find(url)?.groupValues?.get(1)
    }

    /**
     * A position read off the reader's footer label. [value] is the current unit (the "156" in
     * "Location 156"), [total] its ceiling when shown, [percent] the progress readout, and [unit] which
     * scale Amazon is displaying — Kindle shows *Location* for reflowable books and *Page* for
     * page-faithful ones, so both are recognised.
     */
    data class Position(
        val unit: String,
        val value: String,
        val total: String?,
        val percent: String?
    ) {
        /** The compact citation token, e.g. `Location 156` — what a Kindle note cites in place of a quote. */
        val token: String get() = "$unit $value"

        /** The full human label, e.g. `Location 156 of 3866` — the position the way the reader shows it. */
        val label: String get() = if (total != null) "$unit $value of $total" else token
    }

    // "Location 156 of 3866" / "Location 156" / "Page 42 of 300" — case-insensitive, commas tolerated.
    private val FOOTER = Regex("(?i)\\b(Location|Page)\\s+([0-9][0-9,]*)(?:\\s+of\\s+([0-9][0-9,]*))?")
    private val PERCENT = Regex("([0-9]+)\\s*%")
    private val ASIN_PARAM = Regex("(?i)[?&]asin=([A-Za-z0-9]+)")

    /**
     * Parse the reader footer's text ("Location 156 of 3866 ● 4%") into a [Position], or `null` when no
     * position is present yet (the footer is empty while a page is still loading). Commas are stripped
     * from the numbers so the token/label read cleanly.
     */
    fun parseFooter(footerText: String): Position? {
        val m = FOOTER.find(footerText) ?: return null
        val unit = m.groupValues[1].replaceFirstChar { it.uppercaseChar() }
        val value = m.groupValues[2].replace(",", "")
        val total = m.groupValues[3].takeIf { it.isNotBlank() }?.replace(",", "")
        val percent = PERCENT.find(footerText)?.groupValues?.get(1)
        return Position(unit = unit, value = value, total = total, percent = percent)
    }

    /**
     * A one-liner JS expression that returns the reader footer's current text (or `""`), for a WebView
     * to `evaluateJavascript` and hand to [parseFooter]. Kept here so the DOM contract the reader
     * depends on lives beside the parser that consumes it. Reads the light-DOM footer title; falls back
     * to the position-labelled element if Amazon renames the test id.
     */
    fun footerProbeScript(): String =
        "(function(){var e=document.querySelector('[item-i-d=\"reader-footer-title\"]')" +
            "||document.querySelector('.footer-label.position');" +
            "return e?(e.innerText||e.textContent||''):'';})()"

    /**
     * A one-liner JS expression for the **browse** WebView to poll while you skim your library. It
     * returns the current URL and the best available book title, joined by a newline
     * (`"<href>\n<title>"`) — because the Cloud Reader is a single-page app: tapping a book swaps in the
     * reader (`?asin=…`) *without* a fresh navigation, so a poll — the same honest trick
     * [footerProbeScript] uses for the position — is how the browse surface notices you picked one.
     *
     * The caller reads the ASIN from the href via [asinOf] and the human title via [cleanTitle] over the
     * title half. The title is taken from the reader chrome if it has rendered, else the document title
     * (which Amazon sets to the book's name once a book is open); [cleanTitle] rejects the library's own
     * generic chrome so an early poll falls back to the ASIN rather than naming the book "Kindle".
     */
    fun libraryProbeScript(): String =
        "(function(){var t='';" +
            "var s=['[item-i-d=\"reader-header-title\"]','.book-title','[aria-label=\"Book title\"]'];" +
            "for(var i=0;i<s.length;i++){var e=document.querySelector(s[i]);" +
            "if(e){var v=(e.innerText||e.textContent||'').trim();if(v){t=v;break;}}}" +
            "if(!t)t=document.title||'';" +
            "return location.href+'\\n'+t;})()"

    /**
     * A self-installing JS fix for the library grid that comes up **blank** in an Android WebView.
     *
     * The Cloud Reader's app-shell sizes itself with `height: 100vh` on the flex column that wraps the
     * header and the scrolling book grid (`<main id="library">`, a `flex: 1 1 auto` child). Android's
     * WebView mis-computes `vh` for that shell — it collapses to zero, so `main` gets no height and the
     * grid, though fully in the DOM (dozens of covers and links), never paints. The same page lays out
     * correctly in desktop Chromium at the identical viewport, which pins the cause on `vh`, not the CSS.
     *
     * `window.innerHeight` *is* reported correctly by the WebView (only the CSS `vh` unit is wrong), so
     * the fix pins the shell — the parent of the stable `#library` element — to `innerHeight` in real
     * pixels, which the flex column then distributes to `main` normally. It re-pins on resize/orientation
     * change and for a few seconds after load (the shell can mount a beat late), then leaves the listener
     * to handle rotation. Idempotent: installs its listeners once. Targeting `#library`'s parent keeps it
     * independent of Amazon's churn-prone obfuscated class names.
     */
    fun libraryLayoutFixScript(): String =
        "(function(){" +
            "function fit(){var m=document.getElementById('library');" +
            "if(m&&m.parentElement){m.parentElement.style.height=window.innerHeight+'px';}}" +
            "fit();" +
            "if(!window.__kcrShellFit){window.__kcrShellFit=1;" +
            "window.addEventListener('resize',fit);" +
            "window.addEventListener('orientationchange',fit);" +
            "var n=0,id=setInterval(function(){fit();if(++n>20){clearInterval(id);}},250);}" +
            "})()"

    // Amazon chrome that shows up as a page/reader title but isn't a book name — reject these so the
    // browse handoff falls back to the ASIN instead of naming a book after the reader itself.
    private val GENERIC_TITLES = setOf(
        "kindle cloud reader", "amazon kindle", "kindle", "amazon.com",
        "kindle library", "your library", "your books", "library", "read now"
    )

    // Suffixes Amazon tacks onto a product/page title; stripped so the stored name is just the book.
    private val TITLE_SUFFIXES = listOf(
        " - kindle edition", " - kindle", ": kindle store", " - amazon.com", " | kindle"
    )

    /**
     * Clean a raw reader/page title into a book title — trimmed, with Amazon's `- Kindle edition` /
     * `- Amazon.com`-style chrome stripped — or `null` when it's only the reader's own generic label
     * (so the browse handoff can fall back to the ASIN). Empty/blank input is likewise `null`.
     */
    fun cleanTitle(raw: String?): String? {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        var changed = true
        while (changed) {
            changed = false
            for (suffix in TITLE_SUFFIXES) {
                if (s.endsWith(suffix, ignoreCase = true)) {
                    s = s.dropLast(suffix.length).trim()
                    changed = true
                }
            }
        }
        if (s.isBlank() || s.lowercase() in GENERIC_TITLES) return null
        return s
    }
}
