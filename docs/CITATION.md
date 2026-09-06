# Citation — the reading app

Citation is a **standalone reading app** that ingests content from many sources, lets you read it,
and pulls notes from it while preserving each note's source so you can find your way back later. It
is its own Android app (`applicationId com.citation.app`, a distinct install and icon), a **peer on
the LifeOps sync spine** rather than a screen inside LifeOps: the two talk only over the mailbox
sync seam. LifeOps sends down "acquire book X" intents; Citation sends up reading telemetry and
note/highlight packets.

This document covers the **reading half** — ingest, read, capture notes, sync out. The LifeOps-side
work (packet-ingestion UI, the design-hub / correlation layer) lives on the LifeOps side and is out
of scope here.

## Module layout

```
:core       pure-Kotlin / JVM library — the sync contract and the reader's framework-independent
            spine. Zero Android dependencies, fully unit-tested on the JVM.
:citation    Android app — Room storage (two-store split), the Compose reader, and (later)
            WorkManager ingestion jobs, all built on :core.
```

The split is deliberate and mirrors how LifeOps keeps its growth/weather logic in Android-free
`util/` modules: **everything that can be pure logic is**, so the hard parts (identity, anchoring,
sync bookkeeping) are provable without a device.

## The walking skeleton (built + verified)

The task list's milestone 1 — *the vertical slice that proves the pipe* — is implemented end to end
in `:core` and exercised by `CitationSkeletonTest`:

```
EPUB bytes → internal Book model → mint entity key → capture one highlight
          → attach one note with a typed anchor → emit one note packet up the mailbox
```

Once this pipe is real, every other source is "add a producer that yields a `Book`", and every
other feature bolts onto this spine.

### `:core` components

| Area | Type(s) | What it does |
|---|---|---|
| **Internal model** | `model/Book`, `Chapter`, `BookMetadata`, `SourceType`, `TableOfContents` | The one normalized representation every format produces and the reader alone consumes. A Book = ordered Chapters of flowing text + metadata + the publisher's nested contents; a Chapter carries its structure as ranges over that same text (see *Structure over the text* below). |
| **Entity keys** | `key/EntityKey`, `KeyAllocator` | Partitioned, Jira-style keys (`ER-Book-3`) minted **offline with zero coordination**; each app owns a namespace so peers never collide. Provenance is baked into the key. |
| **Dedup** | `identity/IdentityKey`, `IdentitySet`, `DedupValidator` | Typed identity per source: RR fiction id (authoritative), ISBN (**edition-aware** — DDIA 1st ≠ 2nd; ISBN-10/13 of one edition match), PDF SHA (strong positive, **weak negative**). Matches on the strongest shared evidence. |
| **EPUB** | `epub/EpubParser`, `Html`, `TocDocuments` | Zip + OPF + spine → `Book`, plus the contents document, the cover, the illustrations and the shelf metadata; deterministic HTML→flowing-text reduction (the stable surface notes anchor against). Dependency-free (`java.util.zip` + regex). Degrades to recovered chapters rather than throwing. |
| **Manifest** | `manifest/IntegrityManifest`, `StorageReport` | Per-favourite expected-vs-cached, gap detection, per-chapter bytes. One object answers "is it whole?" and "what does it cost?", tagged reclaimable vs irreplaceable. |
| **Anchors** | `anchor/TextAnchor`, `FuzzyAnchor` | Typed anchors (flowing quote+locator; PDF page+quads). **Quote + fuzzy match** re-resolution survives RR edits and re-exports; a deleted passage orphans (NONE) rather than misjumping. |
| **Notes** | `note/Note`, `Highlight`, `PassageReference`, `SourceDescriptor` | Two kinds kept distinct: **passage-anchored** (one highlight) and **freestanding synthesis** (may cite several). Each carries a **frozen quoted snapshot** + typed anchor, so a note **outlives its source** — intact-but-orphaned, never lost. |
| **Ownership** | `store/Ownership`, `Store` | The two-store split as policy: only borrowed (RR) content is `DISPOSABLE`/evictable; owned files and all notes are `SOVEREIGN`. Eviction structurally cannot reach owned data. |
| **Sync** | `sync/Mailbox`, `Packets`, `BookLifecycle`, `BindOrCreate` | Mailbox pattern (state/outbox + inbox, **monotonic version**, idempotent + resumable). Up: telemetry + note packets carrying `{sourceType, sourceId, frozen-context}`. Down: acquire-book intents. **Bind-or-create** reconciles a fuzzy center-authored book to a resolved artifact at one checkpoint. **Two orthogonal state machines** — acquisition (`wanted→resolving→acquired/unavailable`) and reading (`to-read→reading→done`) — never collapsed. |

The walking skeleton is covered by JVM unit tests; together with the Royal Road engine, the note
resolver, the sync protocol, the PDF/O'Reilly pieces, the structured document model, the OPDS
catalog engine, the library query layer, and the storage aggregator below, **`:core` has 420 passing
JVM unit tests** (run `gradle :core:test`).

### `:citation` (Android)

Built on `:core`, following LifeOps' Screen → ViewModel → Repository shape:

- **Storage** (`data/db`): Room *sovereign* DB (books, chapters, notes, highlights, key watermarks,
  sync state); `data/store/FileStores` gives the two physical directories — owned files under
  `filesDir/sovereign`, borrowed bodies under `cacheDir/disposable` (the only dir eviction is ever
  handed). Anchors/references serialize via `AnchorCodec`.
- **Repository** (`data/CitationRepository`): restores the key allocator + mailbox from the DB
  (minting and sync versions survive restarts), then drives import → persist → capture → queue.
  Offline-first throughout.
- **Reader** (`ui/ReaderScreen`, `ReaderViewModel`): renders the internal model — format-blind — as a
  first-class reader:
  - **Highlights are visible.** Passage notes are drawn back into the chapter as inline highlights
    (resolved live by `FuzzyAnchor`, so an edited/re-fetched chapter still lights the right words), and
    a tap opens the note to read, annotate, jump, or delete.
  - **Selection is first-class.** A custom `ReaderTextToolbar` adds **Add note** and **Highlight** into
    the text-selection bar (`LocalTextToolbar`); a selection becomes a passage note or an annotatable
    bare highlight without re-pasting the quote (and without clobbering the clipboard). A repeated
    passage anchors to the occurrence nearest the viewport, not blindly the first.
  - **Paging** is a swipe committed on release, edge tap-zones, or the pinned buttons — with an
    animated page turn — and a **chapter drawer** (TOC) plus a progress bar. In the default **paged**
    reading mode a page turn moves one *screenful* at a time **within** a chapter (the chapter text is
    measured against the live viewport + typography and split into pages by `core/reader/Paginator`, a
    pure, unit-tested line-walker); only the last/first page crosses a chapter boundary. A crossing is
    still **one page**: turning back off a chapter's first page opens the chapter before it at its
    *last* page (`core/reader/PageTurn`), so forward-then-back returns you where you were instead of
    dropping you at a chapter start you left an hour ago — only an explicit jump (the Previous button,
    the contents list) opens a chapter at its beginning. A **Scroll** mode (one continuous column,
    chapter-at-a-time turns, backwards landing at the previous chapter's end for the same reason)
    stays one tap away in the format sheet.
  - **Resume** actually restores: reopening lands on the saved chapter *and* position — a scroll offset
    in scroll mode, or the page's start **character offset** (font-size independent) in paged mode —
    persisted as you read.
  - **Reading comfort:** a Display sheet covering type (size, spacing, margins, tracking, face —
    your own fonts listed under names you can change, and removable from the same list —
    justification, hyphenation, paragraph style), colour (Paper / Sepia / Night / System, a
    **Custom** theme where you set the page and text colours yourself, true black, warmth, in-reader
    brightness) and screen behaviour (keep awake, full screen, orientation, volume keys) — persisted,
    and per-book when a book is told to keep its own. The type and colour settings are **carried into
    the Kindle and O'Reilly readers** too, so they apply to every book rather than only to the EPUBs.
    See *How the book is set*.
  - **Highlights have colours:** five named ones, filed the way tags are but visible while you read,
    and each drawn against whatever colour you set your pages to rather than in one fixed tint.

> **Build note:** `:citation` is a standard Android module and needs the Android SDK to build
> (`gradle :citation:assembleDebug`). `:core` is pure JVM and builds/tests with no SDK
> (`gradle :core:test`).

## Royal Road read loop (milestones 2–3 — core built + verified)

The RR fetch *brains* live in `:core` under `com.citation.core.rr`, fully unit-tested; only the
WebView, HTTP fetch, and WorkManager scheduling are Android glue (the `NwsParser`/`NwsClient` split
again).

| Area | Type(s) | What it does |
|---|---|---|
| **Catalog / extraction** | `rr/FictionCatalog`, `RoyalRoadHtml` | Fiction-page HTML → ordered chapter catalog (deduped by RR chapter id); chapter-page HTML → flowing-text `Chapter`. **Chapter 1 comes through the reader too**, never the live page. |
| **Update detector** | `rr/RoyalRoadFeed` | Per-fiction RSS → entries; `detectNewChapters` returns only genuinely-new chapter ids, oldest-first. The feed detects; a scrape is only the body puller. |
| **Rolling buffer** | `rr/RollingBuffer` | Keep ~5 chapters / ~50k chars ahead; slides forward on each advance, requests only the uncached window. |
| **Backfill** | `rr/BackfillPlanner` | Favourite → full backfill, **gaps-first then new** (self-healing), recomputed from the cached set each call (resumable). |
| **Eviction** | `rr/EvictionPolicy` | 7-day-from-last-read for non-favourites; favourite/open exemptions are structural via `Ownership`. |
| **Rate budgets** | `rr/RateBudget` | Sliding-window limiter with per-hour + per-day ceilings; feeds loose, scrapes ~6/hr. Injected clock → deterministic tests. |
| **Priority queue** | `rr/FetchQueue`, `FetchLane` | Cross-lane arbitration — active-buffer → finish-current → favourites-new → favourites-backfill; **the buffer always wins**, FIFO within a lane. |

**Android glue** (`data/rr`, `work`, `ui/RoyalRoadCatalogScreen`): `RoyalRoadClient`
(HttpURLConnection, parses via `:core`), `RoyalRoadCoordinator` (wires client + planners + queue +
budgets + file store + catalog persistence), three WorkManager jobs (favourites poll / backfill /
eviction), and a WebView **skim/catalog** screen that intercepts a `/fiction/{id}` tap and opens the
story *through the reader* instead of loading the live page.

## Archive of Our Own (via official EPUB download — built + verified)

AO3 is a source, but **not** a scraped web serial. An early attempt mirrored Royal Road (parse the
work page, cache chapters, poll for updates), but AO3's HTML is hostile to that: work pages 302 through
adult-content gates, some works require login, and the markup drifts. AO3 already publishes a complete,
sanctioned **EPUB download** for every work, and Citation's EPUB producer parses it cleanly — so AO3 is
ingested as an **owned EPUB snapshot**, not borrowed cache. This is simpler, robust, and gets every
chapter with correct metadata in one fetch.

| Area | Type(s) | What it does |
|---|---|---|
| **Download** | `data/ao3/Ao3Client` | Fetches `archiveofourown.org/downloads/{workId}/work.epub` (the slug and `updated_at` AO3 puts on its own links are optional). Redirects — including the hop to `download.archiveofourown.org` — are **followed manually** rather than via `HttpURLConnection`'s built-in handling, which proved unreliable (the scraping attempt landed on a pre-redirect page). |
| **Parse** | `epub/EpubParser` (reused) | AO3's Calibre-produced EPUB 2.0 (root-level `content.opf`, split `*_split_NNN.xhtml` chapters, `toc.ncx`) parses through the existing EPUB producer with no AO3-specific code. Pinned by `Ao3EpubTest` against a real AO3 export in test resources. |
| **Identity** | `identity/IdentityKey.Ao3Id` | AO3 work id — **authoritative for AO3**, a distinct type from `RoyalRoadId` so an AO3 work and a Royal Road fiction sharing a number never dedup together. The imported book carries it, so re-opening the same work reuses the download instead of fetching twice. |

**Android glue** (`data/ao3/Ao3Client`, `ui/Ao3CatalogScreen`): a WebView **catalog** screen browses
AO3 and intercepts a `/works/{id}` tap, handing the work id back so the app downloads + opens it
through the reader instead of loading the live page. `CitationRepository.openAo3` downloads the EPUB
the first time (via `importAo3` — parse, mint a key, store chapters inline in the sovereign DB and keep
the raw `.epub`, tagged `SourceType.AO3` with an `Ao3Id`), and thereafter just loads the owned book;
open/delete/storage/dedup all route AO3 down the same owned-EPUB paths. Because AO3 is owned, there is
no coordinator, buffer, eviction, or update-poll (the v4 borrowed-serial tables are dropped again in the
citation.db v4 → v5 migration). The **Import EPUB** picker now accepts `application/epub`/`octet-stream`
too, so a manually-downloaded AO3 EPUB is selectable. On the LifeOps side AO3 telemetry maps to the
**Fun** reading category, like Royal Road.

## Opening a book from outside the app

`MainActivity` is the system's handler for **opening** an EPUB or a PDF (`ACTION_VIEW`) — tap a book
in a file manager, a browser download, or another app's "open with" and it lands here, not in a
generic viewer. The activity reads the file, sniffs its **magic number** (`%PDF`, `PK`) rather than
trusting the intent's MIME type (senders type EPUBs as `application/epub`, `application/epub+zip`, or
`application/octet-stream` more or less at random), and hands the bytes to the same
`importEpub`/`importPdf` the New tab's pickers use. The import then **opens** — the point of the tap
was to read the thing, so it lands in the reader rather than in a status line on a tab you'd have to
go find. A file that can't be read, or that is neither format, raises an alert over the reader
(`ReaderViewModel.importAlert`) for the same reason.

PDFs used to open in Logistics (its Walmart-order import). Logistics now takes the deliberate
*share*-a-PDF gesture instead, and Citation has the open — see `OPERATIONS_SANDBOX.md`, "Who answers
for a file type".

## Notes complete (milestone 4 — core built + verified)

The note **degradation/resolution engine** lives in `:core` as `note/NoteResolver` (unit-tested): it
turns a frozen note plus the current (maybe-changed, maybe-absent) source into a per-reference state
and jump target —

| State | Meaning | Jump |
|---|---|---|
| `RESOLVED` | source present, passage still matches | reliable jump |
| `FUZZY` | present but the passage was edited | probable jump, flagged |
| `ORPHANED` | present but the passage is gone | snapshot only, no jump |
| `SOURCE_UNAVAILABLE` | source evicted/absent | snapshot only, no jump |

Only the last two are "degraded", and both keep the note fully readable — *degraded, not lost*.
Jump reliability is tagged by source type (`RELIABLE` for owned/internal, `BEST_EFFORT` for borrowed
Royal Road / O'Reilly), and a synthesis note's overall state is the **best** across its references.

**Android wiring:** the reader's composer now saves either a **passage-anchored** note (quote
required) or a **freestanding synthesis** note (quote optional, cites where found); Royal Road
serials are registered as sovereign books on open, so **notes on borrowed sources work** and survive
eviction of their chapter bodies. A **Notes screen** lists every note with its state badge and the
frozen snapshot, and tapping one **jumps back to live context** (best-effort for borrowed).

## Notes retrieval — search, tags, export (core built + verified)

Capture was already excellent; this is the **payoff end** — notes that come back *out* again. All the
logic is pure `:core` under `com.citation.core.note`, JVM-tested, and operates on the in-memory note
corpus (one person's reading is small and already loaded, so there's no FTS table or query
round-trip — it stays offline-first like everything else):

| Area | Type(s) | What it does |
|---|---|---|
| **Search** | `note/NoteSearch` | In-memory full-text filter. A note matches when **every** whitespace-token appears (substring, case-folded) anywhere in its searchable surface — your body, the frozen quoted snapshot(s), the source title/author, and its tags. AND-of-tokens narrows rather than widens. |
| **Tags** | `note/Tags`, `TagCount` | A local organizational layer. `parse` folds raw editor input (spaces/commas/`#` optional) into normalized lowercase tags, de-duped, first-seen order; `counts` builds the facet (most-used first); `withTag` filters. Multi-word concepts are one hyphenated tag. |
| **Export** | `note/MarkdownExport` | Renders notes to portable Markdown — grouped by source, each entry a blockquoted snapshot + your words + tags + correct attribution (the frozen `{title, author}` makes every quote self-citing). Pure string-in/out, so the whole layout is unit-tested. |

Tags live on the `core` `Note` (`tags: List<String>`, default empty) but are **deliberately off the
sync wire** — a note's *text* is the shared artifact; its filing is Citation's own. The `NotePacket`
projection is untouched, so the sync seam is unchanged.

**Android wiring:** the **Notes screen** gains a search box and a tag-facet chip row (tap a tag to
filter, tap again to clear), each note row shows its tags, and the note detail dialog gains a **Tags**
field (also reachable from the reader). Tags persist via a new `notes.tagsJson` column (Room
`citation.db` v2 → v3 migration; encoded/decoded in `AnchorCodec`) and are set local-only —
`CitationRepository.setNoteTags` preserves the existing `syncVersion` rather than re-posting. An
**Export** action on the Personal tab renders the *currently-visible* notes (so filtering by tag or
search first exports just that slice) and shares them as a real `.md` file via a `FileProvider`
(`NotesExporter`), mirroring how LifeOps shares its backup — drop the file straight into Obsidian.

## Sync seam (milestone 5 — core built + verified)

Citation is a peer on the LifeOps spine. The whole protocol is pure/JVM-tested in `:core`:

| Area | Type(s) | What it does |
|---|---|---|
| **Wire codec** | `sync/SyncCodec` | JSON for packets, intents, and envelopes (Gson tree model, explicit type tags). A note packet is **legible without the source** — the frozen `{sourceType, sourceId, title, author, snapshot, anchor}` is right on the wire. |
| **Envelopes** | `sync/SyncEnvelope` | Outbound (unacked up-packets + our intent cursor) and inbound (new intents + their packet ack) — each carries the ack cursor that makes the monotonic-version protocol converge. |
| **Engine** | `sync/SyncEngine` | `buildOutbound` snapshots what to send; `applyInbound` prunes the outbox to their ack, delivers intents, runs reconcile, and advances the cursor. Re-applying the same envelope is a no-op (safe retry). |
| **Intent reconciler** | `sync/IntentReconciler` | Down-intent bind-or-create: fuzzy-match "acquire X by Y" to an existing book (dedup) or create a `wanted` one — and **never** flips a book to acquired (that's the reader's axis). |
| **Transport** | `sync/FileEnvelopeStore` | The mailbox as two JSON files in a shared folder — no server; works offline; JVM-tested end to end. |

**Android wiring:** the repository's mailbox now carries both directions; `sync()` writes the outbound
envelope, applies LifeOps' response, and persists newly-`wanted` books (both state machines set from
`BookLifecycle.wanted()`). A `SyncWorker` runs it periodically, and a "Sync with LifeOps" action runs
it on demand.

## PDF + O'Reilly (milestone 6 — core built + verified)

Two more sources, each on its own track (core pieces unit-tested):

| Area | Type(s) | What it does |
|---|---|---|
| **PDF render track** | `pdf/PdfTrack` | Positioned glyphs don't reflow, so a PDF renders *pages* as the ground-truth view. View↔PDF coordinate transform (Y-flip, **zoom-stable quads**), page/quad anchor construction, and the SHA-256 import identity (strong-positive dedup). |
| **PDF reflow track** | `pdf/PdfFlow` | The *second* view over the same file: extracted page text → the format-blind `Book`, so a PDF becomes **selectable and quotable** instead of a picture of glyphs. Cleans running heads/folios, heals line-break hyphens, rejoins wrapped lines into paragraphs. Chunks **whole pages** into chapters and encodes each chapter's page map into its `sourceRef`, so an offset maps back to a page (`pageOf`) and a page back to a chapter (`chapterForPage`). **Declines** (returns null) when there's no text layer — a scan stays paged-only rather than opening blank. |
| **External anchor** | `anchor/TextAnchor.External` | A read-in-place anchor for content Citation never holds: the source reader's opaque **location token** + the frozen quote. Resolves via deep link (best-effort), not text matching; `NoteResolver` + `SyncCodec` handle it. |
| **O'Reilly deep-link** | `oreilly/OreillyLink` | Build/parse the URL to a book + position, so reopening lands one tap from your spot. Optionally routes through a library proxy (see below); parsing is host-agnostic, so a proxied position round-trips unchanged. Also builds the **browse** URL (O'Reilly's catalog, proxied) and pulls a book's `slug` out of a catalog link, title-cased into a readable library name (`titleFromSlug`). |
| **Library proxy** | `oreilly/OreillyLibraryProxy` | EZproxy host rewriting — `learning.oreilly.com` → `learning-oreilly-com.mcpl.idm.oclc.org` — so you reach the licensed content on a **library card**, not a personal O'Reilly account. Encode (`.`→`-`, `-`→`--`) is reversible, so hosts round-trip; `rewrite` is idempotent. Defaults to Mid-Continent Public Library, any EZproxy host is configurable. |
| **Warm page cache** | `oreilly/OreillyCachePolicy` | Policy for the WebView's HTTP cache of pages you've opened — so a reopen is fast and a page re-reads during a brief disconnect. The invariant it enforces: this is **never a permanent copy of licensed content**. It's always `Store.DISPOSABLE`, always `RECLAIMABLE` (refetchable by reopening online), and TTL-bounded — `shouldPurge` drops it once it's sat untouched past 7 days (and never while the reader is open). |
| **Auto-reauth** | `oreilly/EzproxyLogin` | The reauth brain: detect an OCLC/EZproxy sign-in page (`isLoginPage`) and build a safe credential-fill/submit script (`fillScript`). Selectors lead with MCPL's confirmed form (`POST mcpl.idm.oclc.org/login`; card `name=user id=cardnum`, PIN `name=pass id=pin` — the card field is itself `type=password`, so the card never falls back to a `type=password` selector), then generic fallbacks for other libraries. Credentials are embedded as JSON string literals so a card/PIN can't break out of the script. It's the *user's own* library credential into the *library's* form — it never touches O'Reilly's DRM or federated tokens. |

**Android wiring:** `PdfReaderScreen` renders pages via the platform `PdfRenderer` (own track, page
nav, page-anchored notes); PDF import hashes + dedups into the owned store. **Reading a PDF as text**
is one tap from the paged view: `data/pdf/PdfPageText` (PDFBox-Android, the port `:logistics` already
carries) extracts the text layer page by page, `PdfFlow` reflows it, and the chapters are stored
inline against the same book — so the existing flowing reader renders it with selection, typography,
and search, and the rendered page stays one tap back for the figure the reflow flattened. The reflow
is **derived, never a conversion**: the imported file is untouched, both tracks read the same book,
and switching either way keeps your place (page → the chapter covering it, chapter → the page you're
on). A selection made in the reflowed text still captures a **`TextAnchor.Pdf`** — page + quote, no
glyph quads — so a PDF note cites its page whichever track you took, with no second anchor shape and
no change to the sync contract. A scan with no text layer says so and stays on pages. `OreillyReaderScreen`
hosts O'Reilly's own reader in a WebView (**no content cache** — licensed), routed through your
library's EZproxy so you read on a library card; when the proxy session lapses and bounces to the
OCLC sign-in page it **auto-reauths** from your saved card + PIN (`EzproxyLogin.fillScript`, with a
manual sign-in fallback in the top bar), persists the proxy session via cookies, tracks your position
from the URL, and captures **annotations only** (quote + location token as an `External` anchor) into
the sovereign store. The card/PIN live in `data/OreillyAccess`, **encrypted at rest via the Android
Keystore** (`EncryptedSharedPreferences`) and **never synced**; a Settings section takes the proxy
host + card + PIN (the PIN field masked). A **warm page cache** keeps opened pages in the WebView's
own HTTP cache (`data/OreillyWebCache`): the reader picks `LOAD_DEFAULT` online and
`LOAD_CACHE_ELSE_NETWORK` offline (re-read a page you've seen through a brief disconnect), drops the
cache on open once `OreillyCachePolicy` calls it stale, and Settings shows its footprint with a "Clear
O'Reilly cache" action — the cache is disposable and reclaimable, never promoted to the sovereign
store, so your notes and position are untouched by clearing it. Your O'Reilly library, positions, and
notes are Room-backed and already browse fully offline. Library gains "Import PDF" and "Add O'Reilly
book"; opens route by source type to the right track.

**Browse O'Reilly** (`ui/OreillyCatalogScreen`) is the read-in-place counterpart to Browse Royal
Road: a full-screen WebView on O'Reilly's own catalog, routed through your library's EZproxy so the
whole skim runs on a library card — the same cookie-persisted session and card/PIN **auto-reauth**
the reader uses, so a lapse mid-browse re-signs-in without kicking you out. Tapping into a book
(`/library/view/{slug}/{id}/`) is intercepted, kept from opening in the catalog, and handed back
(`openOreillyFromCatalog`) so it opens **read-in-place** through `OreillyReaderScreen` — registered in
your library (deduped by id, so re-browsing reuses the entry + notes), titled from the URL slug. The
old "…add an O'Reilly book by id" dialog stays as a manual fallback.

**Browse Kindle library** (`ui/KindleLibraryScreen`) is the same move for Kindle: a full-screen WebView
on your own library at `read.amazon.com` (Amazon's own sign-in + cookies — no proxy or stored
credential, unlike O'Reilly). Because the Cloud Reader is a single-page app, a book tap swaps in the
reader *without* a navigation, so instead of `shouldOverrideUrlLoading` the screen **polls**
`KindleLink.libraryProbeScript` (the same trick the reader uses to follow the footer position); when a
book's ASIN appears it hands back the ASIN + a `cleanTitle`'d name (`openKindleFromLibrary`), opening it
**read-in-place** through `KindleReaderScreen` — registered in your library, deduped by ASIN, so
re-picking reuses the entry + notes. The old "…add a Kindle book by ASIN" dialog stays as a manual
fallback for when you already know the ASIN.

## Structure over the text, not instead of it (core built + verified)

The reader was format-blind but also **structure-blind**: every chapter was reduced to a flat
string, so italics, headings, verse, tables and illustrations were thrown away before the reader
ever saw them. A technical PDF lost every figure; a footnoted history book silently dropped its
apparatus; a poem read as prose.

Recovering them is not "render the HTML instead", because that flat string is **load-bearing** — it
is the surface every frozen note snapshot and every `TextAnchor` offset was captured against.
Change one character of it and a note taken last year lands on the wrong words, silently. So
structure is expressed as **ranges into the unchanged text**.

| Area | Type(s) | What it does |
|---|---|---|
| **Blocks** | `doc/DocumentBlock`, `BlockKind` | Paragraph, heading (with level), block quote, verse, code, list item, caption, table — each a `[start, end)` of the chapter's canonical text. Images and rules are **zero-width markers** (`start == end`) that sit *between* characters, so adding illustrations to a book you had already annotated cannot move a single anchor. |
| **Inline** | `doc/InlineSpan`, `InlineStyle` | Italic, bold, code, underline, strikethrough, super/subscript, small caps, links, and **footnote references** kept distinct from ordinary links — likewise as ranges. |
| **Reduction** | `doc/HtmlDocument` | The original reduction **instrumented** rather than replaced: it runs the same strip-tags / decode-entities / collapse-whitespace pipeline stage by stage while carrying an offset map, so it can say where each tag landed. Text output is identical *by construction*. |
| **Segmentation** | `doc/Structure` | Reads structure off those positions. The reduction already emits a blank line at every block close, so a run between blank lines is a block — split further at block-element boundaries, because `<title>t</title></head><body><h1>Heading</h1>` reduces to `tHeading` with no break between them, and every EPUB chapter in the world starts exactly that way. |
| **Entities/XML** | `doc/Entities`, `xml/Xml`, `xml/Text` | Shared, so the reduction and the heading extractor cannot drift by one entity — a drift here moves offsets. |

`HtmlReductionParityTest` is the load-bearing test: it keeps the **original regex implementation
verbatim as an oracle** and asserts byte-equality against it over adversarial markup (split
entities, unclosed script tags, mixed line endings, malformed tags) *and* every content document in
a real EPUB.

**Android wiring:** `ui/reader/ChapterRender` turns a chapter into what Compose draws — and keeps a
two-way offset map, which is the whole trick. The drawn string is deliberately **not** the canonical
text: an image needs a placeholder character to sit on, a list item needs a bullet, a table needs
separators between cells the reduction runs together, and paragraph layout replaces the blank lines
between blocks. So the paginator, the highlighter and the selection handler all work in *display*
offsets, and anything stored or anchored converts back first — which is why a position saved before
a book had any structure still lands on the right page. Pagination measures with the placeholders
the renderer reserved, so a page holding a plate accounts for its height instead of overflowing by
exactly that much; illustrations decode **downsampled** to the text column, because a publisher's
plate is routinely 2000px wide and decoding several at full size is how a reader runs out of memory.
Typography follows book convention rather than web convention: paragraphs separated by a first-line
indent rather than a blank line, and no indent on the paragraph opening a section. Royal Road
chapters go through the same reduction, so a web serial's italics and scene breaks survive too.

## A book that looks like a book (built + verified)

The EPUB producer now recovers what makes a book recognisable, not just readable:

| Area | What it does |
|---|---|
| **Contents** | The publisher's **nested** table of contents — EPUB 3 `nav`, falling back to EPUB 2 `toc.ncx` (still the only contents document in a large share of real libraries). `epub/TocDocuments` walks them one level at a time, so `Part II › Chapter 7 › "Consistent Hashing"` survives instead of a flat spine of a hundred undifferentiated files. An entry pointing **inside** a chapter (`#fragment`) resolves through `Chapter.anchors` to an offset, which is what gives a single-file book a usable contents list at all. |
| **Cover** | By descending confidence: the EPUB 3 `cover-image` property, the EPUB 2 `<meta name="cover">` pointer, the first image in the first spine document, then any manifest image named like a cover. |
| **Illustrations** | Every referenced image, with its href resolved from chapter-relative to zip-absolute (`../img/plate%20one.png` inside `OEBPS/text/ch1.xhtml` → `OEBPS/img/plate one.png`), percent-decoding included. |
| **Shelf metadata** | Publisher, date, blurb, subjects, and **series** — stated two incompatible ways in the wild (calibre's `<meta name="calibre:series">` and EPUB 3's `belongs-to-collection`), both read. |

Stored images live **beside the book in the sovereign store**, named by a digest of their source
reference rather than their path: an EPUB href can contain `..`, characters the filesystem rejects,
or arbitrary nesting, and a flat directory of digest-named files has none of those failure modes.
Only images the content actually references are kept — a publisher's archive routinely carries
fonts, stylesheets and unused artwork. All of it is **derived**: re-parsing the kept `.epub`
reproduces every one, so losing them is recoverable in a way losing a note is not.

## The library (core built + verified)

A flat, unsorted, unsearchable column of titles works for a dozen books and is useless for hundreds
— and connecting a catalog makes hundreds normal within a week. The arranging is pure `:core`:

| Area | Type(s) | What it does |
|---|---|---|
| **Shelf model** | `library/LibraryEntry` | One book as a shelf sees it, with progress preferring what the reader measured in characters and falling back to a coarse chapter estimate for a book not opened since. Honest at the edges: an unopened book reads 0%, not 1/n, and a finished one reads 100% whether or not its last chapter was scrolled to the bottom. |
| **Sort** | `library/LibrarySort`, `LibrarySorting` | Recent / added / title / author / series / progress. *The Time Machine* files under T-i-m-e; "H. G. Wells" sorts as "Wells, H. G."; a series reads in order with standalone books after it; never-opened books sort after opened ones rather than jumbling in at zero. |
| **Filter + search** | `library/LibraryFilter`, `LibraryQuery` | AND-of-tokens across title, author, series and subjects — narrowing as you type, the same rule note search uses. Composable filters for shelf, source, reading state, subject and starred. |
| **Facets** | `library/Facet` | Subject and series counts, most-used first. |
| **Shelves** | `library/BookCollection` | Manual, deliberately not rule-based: a smart collection needs a query language and an explanation for why a book vanished from it; a shelf you put books on needs neither, and series/subject grouping already gives the automatic view. |

**Android wiring:** the Library tab is now covers in a grid or a list, search, a sort menu, shelf and
subject chips (drawn from the *unfiltered* library, so the row doesn't collapse as you narrow), and
a book detail sheet — star, mark finished, shelve, remove. A book with no cover gets a woven tile
keyed to its own title, because a grid half full of grey rectangles is harder to scan than one with
no covers at all. Covers decode downsampled, off the main thread.

## OPDS catalogs — the acquisition half (core built + verified)

The biggest gap in "universal library reader" was never a format: it was that acquisition meant
"pick a file, or browse four specific sites". **OPDS is the one protocol that changes that.** A
single client reaches a self-hosted Calibre content server or Calibre-Web instance, Standard Ebooks,
Project Gutenberg, Feedbooks, Kavita and Komga, and most library lending platforms — so the work is
in speaking it properly rather than writing an integration per source.

| Area | Type(s) | What it does |
|---|---|---|
| **Atom (OPDS 1.x)** | `opds/OpdsParser` | What nearly every real server speaks. A nesting-aware scan in the same spirit as the EPUB producer, and lenient where feeds are inconsistent: namespaces, `dc:` vs `dcterms:`, `<content>` vs `<summary>`, escaped-HTML summaries, and navigation feeds mislabelled as acquisition ones. |
| **JSON (OPDS 2.0)** | `opds/Opds2Parser` | Readium-based servers. Normalised into the **same** `OpdsFeed`, so nothing downstream learns there are two protocols. |
| **Classification** | `opds/OpdsLink`, `OpdsLinkKind`, `OpdsFormat` | OPDS says everything through `rel` and `type`: whether an entry is a book or a folder, downloadable or only borrowable, where its cover is, how to page and search. Decided once, as data — the UI never pattern-matches a rel string. |
| **Feed model** | `opds/OpdsFeed`, `OpdsEntry`, `OpdsFacetGroup` | Navigation and publications already separated, paging and facets picked out, and every href **absolute** by parse time. Entry metadata mirrors `BookMetadata` deliberately — a catalog usually knows more than the file does. |
| **URLs** | `opds/OpdsUrl`, `OpenSearchDescription` | Resolution for every way feeds state a link (absolute, protocol-relative, root-relative, relative, with spaces in it), and OpenSearch template expansion — the indirection OPDS uses instead of inventing its own search. |
| **Catalogs** | `opds/CatalogSource`, `CatalogPage`, `CatalogDecoder` | Saved catalogs with free public presets as seeds. A typed address is normalised from what a person actually types (`nas.local:8080`) rather than what the protocol wants. The decoder sniffs the body as well as the content type, so a correct feed under the wrong type still parses and an HTML sign-in page is reported as "not a catalog" instead of silently parsing to an empty shelf. |

**Android wiring:** `data/opds/OpdsClient` is the only class that touches a catalog server (the
`NwsClient`/`NwsParser` split again). It copes with what real servers do — Basic auth, manual
redirect hops, mislabelled types, a size cap — and returns **typed failures**, because "the NAS is
asleep" should render as a message with a retry, not a crash. Credentials are held to the catalog's
**own origin**, so a redirect out to a CDN cannot carry someone's server password with it, and they
live in a Keystore-backed store (`CatalogCredentials`) rather than the database: a `citation.db`
travels — the sandbox backup copies it, a restore swaps it in wholesale — so a database carrying
server passwords would make every backup a credential leak.

`ui/CatalogScreen` browses natively rather than in a WebView, which is the whole point: the entries
are data, so a tap becomes a download that lands in the library with its series, subjects and blurb
attached, folders walk in and out with the system Back gesture, and a book that can only be borrowed
says so instead of offering a button that would fail. On download the catalog's metadata is
**merged over** the file's — additive, with the file's own values winning, since those came from the
publisher's package document — and a cover is fetched if the file had none.


## Reading mechanics — search, bookmarks, progress, lookup (core built + verified)

The features a reader is judged on, each written where the honest answer is harder than the obvious
one.

| Area | Type(s) | What it does |
|---|---|---|
| **In-book search** | `reader/BookSearch` | The most-missed feature in any reader. Matching folds case and normalises whitespace — so a phrase the source happens to break across a line still matches — but reports offsets into the **real** text, because the offset must serve as both a jump target and a highlight range in the canonical text. The walk compares against a normalised view *without ever materialising one*, so there is only one set of offsets in existence and no chance of returning the wrong one. Snippets cut at word boundaries and elide visibly. A chapter not yet downloaded contributes nothing, because "not here" and "not fetched" are different claims. |
| **Progress** | `reader/ReadingProgress` | Characters, not chapters. "Chapter 3 / 40" is a location: three chapters into a book whose first three are a foreword, a preface and a note on the text is not 7.5% read. Percent never rounds up to 100 before the end. |
| **Time left** | `reader/ReadingPace`, `TimeLeft` | Every reader app either asks you for a words-per-minute or invents one. Citation already measures **engaged** time honestly (`ReadingMeter` voids the stretch where you walked away), so the pace is simply observed. It reports whether it is `confident` yet and callers show **nothing** rather than a guess — an invented "4 hours left" on the first page is worse than no number, because the reader cannot tell it was invented. Jumps and stalls are dropped rather than smoothed; old observations decay so a dense technical book after a novel is followed. |
| **Bookmarks** | `reader/Bookmark`, `Bookmarks` | A **position, not a passage** — which is why the reader needed both. Conflating them means either highlighting a sentence you did not care about to mark your place, or scrolling a list of positions hunting for the one that was about something. But an offset alone is as fragile as an anchor-by-offset, so a bookmark freezes the line it was set on and re-resolves through `FuzzyAnchor`: an edit earlier in the chapter moves it with the words, a deleted passage degrades to the chapter rather than jumping somewhere wrong. Sovereign — it outlives its book like a note. |
| **Page turns** | `reader/Paginator`, `reader/PageTurn` | `Paginator` decides *where* the pages break (it walks the laid-out lines, never stalls, and gives page 0 less room for the chapter title); `PageTurn` decides *which* page a gesture lands on. The whole point of the second one is the chapter boundary: a book is paginated per chapter, so a page back off a chapter's first page enters the chapter before it — and entering it at the *start* silently converts one page back into a jump over an entire chapter, which is not a turn any more. Backwards crossings land on the previous chapter's **last** page, so forward and back are inverses. Both are pure and unit tested; the Compose layer only measures and draws. |
| **Lookup** | `reader/Lookup` | The hard part is deciding *what the word is*. A selection arrives as `“Whither,` or `mansions.` or a whole clause dragged by accident; handing that to a dictionary returns nothing, which reads as the feature being broken rather than the query being wrong. Ends are stripped, insides kept (`don't`, `well-being`), and a phrase is never offered a dictionary entry it cannot have. |

**Android wiring:** search takes over the top of the reader rather than a separate screen — you want
a passage in order to get back to it, so the page stays underneath and the results stay up after you
land. Matches are lit in a colour distinct from a highlight, because a search match is transient and
not yours. A bookmark ribbon in the top bar toggles on the page you are *looking at* (the viewport,
not the last debounced save), and the list is reached from the contents sheet — both answer "take me
somewhere in this book", so they share a route instead of each claiming an icon. The bottom bar
tracks the whole book by characters, with time-left shown only once the estimate has earned it.
**Keep screen on** is a window flag scoped to the reader — released the moment reading stops, no
permission, no wake lock — applied across every reader track and on by default. **Look up** joins Add
note and Highlight in the selection toolbar as a single item: what a selection is worth looking up
*as* depends on what it turns out to be, and the toolbar cannot know that without a clipboard round
trip it should not pay just to decide what to draw; the platform's own dictionary leads where a
handler exists, with web fallbacks in the same list rather than hidden behind a failure.

Room v7 adds `bookmarks` (sovereign: it nulls rather than cascades, outliving its book) and
`reading_pace` (pure observation, safe to lose, rebuilt within an hour of reading), plus
`books.progressFraction` — the library cannot compute character-accurate progress without loading
every chapter, so the reader writes what it measured and the shelf reads it back, which is what keeps
the library and the page from quoting different numbers for the same book.

## How the book is set, and what the screen does (core built + verified)

Display settings used to live in composition state, so a reader's text size, margins and theme were
lost on every app restart. `reader/ReaderSettings` gathers all of it into one persisted value
(Room v8), and a book can be told to keep its own.

Per-book settings are a **complete fork**, not a sparse patch. A patch looks tidier and behaves
worse: change the global font later and a book that had overridden only its margins silently changes
face too — the kind of surprise nobody can debug from the outside.

| Area | What it does |
|---|---|
| **Typography** | Size, line spacing, margins, letter spacing, and the choice between **indented** and **spaced** paragraphs — both correct, belonging to different traditions. Justification and automatic hyphenation are paired, and the sheet says so: justification on a narrow phone column *without* hyphenation is what opens rivers of whitespace. Justification applies to running prose only — a justified heading or table row stretches a few words across the column and reads as a bug; verse and code are never justified, because their line breaks are the author's. |
| **Fonts** | Citation ships none of its own. The faces readers ask for here — OpenDyslexic above all — are ones it has no right to redistribute, so instead you point at a file you already have: that covers dyslexia faces, a preferred serif, and a face for your book's script, without Citation curating any of them. Picked fonts are copied into the sovereign store and **content-addressed**, so picking the same file twice does not accumulate copies and a book cannot change face because a downloads folder was cleaned. A missing or unreadable font falls back to sans rather than making a book unopenable. |
| **Naming and removing fonts** | The digest that names a font *file* is a hopeless *label* — a picker offering `a3f9c2b1d0e4…` tells you nothing about which of your faces that is — so the name is kept beside it (`core/reader/ReaderFonts`, a `.label` sidecar in the font directory) and is yours to change. It is seeded from the document's own name, asked of the content provider rather than read off the URI, since a `content://` path carries a provider's document id and not a file name. Renaming writes the sidecar and never the font: the file's name **is** its content digest, and every book already set in that face refers to it by that path, so renaming the file would silently unset all of them. Re-adding the same face from another folder does not overwrite a name you chose. Removing a font is confirmed rather than undone — Citation's copy may be the last one left — and the sheet says what happens next: the face being read in goes back to sans, and any other book set in it falls back on its next render, which is what has always happened to a font that vanished. |
| **Colour** | `reader/ReaderPalette` — the themes, **true black** for OLED (which also *softens* the text rather than maximising contrast: full-strength type on pure black is a harsh edge in a dark room), and **warmth** applied to the colours themselves rather than as a translucent orange sheet. An overlay dims everything it covers, flattening contrast exactly when a reader has turned warm because it is late and their eyes are tired; cutting blue in the colours warms the page while leaving it as legible as it was. |
| **Your own colours** | The `CUSTOM` theme: page and text colours the reader names, held in `customBackground` / `customText` and reached either by tapping a swatch or by typing an exact `#RRGGBB`. Both, because they answer different questions — a strip of tints is for *comparing* which is easiest on your eyes; a hex field is for a reader who arrives already knowing the value, from an overlay they own or a colour they were given, and for whom hunting it on a gradient would be guesswork. The four presets are the usual answers but not everybody's: readers with Irlen syndrome or a light sensitivity are routinely told a specific tint helps, and so are many dyslexic readers, and no preset could guess it. The chosen pair is shown as a **sample page** — the sheet covers the book, so otherwise you are picking colours against a preview you cannot see — warmed exactly as the real page will be. Colours are forced opaque (a part-transparent page would let the app's own surface bleed through the book) and kept when you switch away to compare, so a trip through Sepia and back does not throw away colours somebody sat and tuned. Contrast is **checked, not policed**: a pair below WCAG AA is called out as hard to read, but the reader is the one looking at the page and may have a reason for it. |
| **Your own highlight colours** | `note/HighlightColor` — five named colours a highlight can be made in, because a colour is only worth anything if it *means* something (yellow for what the book says, blue for what you argue with) and a meaning has to hold across a year of reading. It is the other half of `tags`: tags are the filing you search, colour is the filing you see while turning pages, without opening a note. Off the sync wire for the same reason tags are — what a note *says* is the shared artifact, what its mark looks like on your page is yours. A new highlight takes the colour set in the Display sheet, so capture stays one gesture; the odd one is recoloured from the note itself. |
| **Drawing a highlight** | `ReaderPalette.highlight` — a mark cannot be a fixed colour, because it is not drawn on a fixed page. A translucent yellow that reads as a highlighter on white goes muddy on a night page and near-invisible on one the reader tinted themselves; once readers can choose their own page colours, "the app accent at 28% alpha" stops being an answer at all. So each tint is mixed into the actual page as strongly as the text on top can still be read over, stepping back until the passage is comfortable rather than merely marked — with a floor, because a highlight nobody can see is worse than a faint one. A highlight can never render its own sentence unreadable, which is the failure that makes a reader think their book is broken. |
| **Read-in-place** | `reader/ReaderWebStyle` + `ui/reader/ReaderWebStyling` — the same settings, carried into the Kindle and O'Reilly readers Citation hosts. Without it the whole Display sheet applied to one of four reading tracks, which is barely a setting at all: a reader who needs a night page or their own dyslexia face needs it in every book they open, not only in the ones that happen to be EPUBs. It is a **guest in someone else's page**, and the scope says so. Size goes through `WebSettings.textZoom`, which scales the site's *own* relative sizing — a `font-size` in px flattens a reader that sets its headings in `em`. Colours paint the whole surface; typography is applied to prose elements only, never `div` or `*`, so their toolbar and page-turn controls are not resized along with the text. **Margins are deliberately absent** — the setting most likely to break a fixed-layout reader and the least missed inside one that manages its own measure. A reader's own font is embedded as data because the page is served over https and its origin cannot reach the filesystem, whatever the WebView is allowed to do; over a size cap it degrades to sans rather than freezing the JS bridge. Both are single-page apps that rebuild their DOM as you turn pages, so the script replaces one known element and is re-run on a timer. And it has a switch: those readers change without notice, and somebody looking at a page Citation has made worse needs a way to stop it faster than uninstalling. |
| **Screen** | Full-screen reading, orientation lock, and an in-reader brightness that is a **window attribute** — it applies while the reader is up and never touches the device's own setting, which is what makes turning it right down safe. |
| **Volume keys** | `reader/VolumeKeys` — down is forward by default (down is the direction the text moves), reversible for readers who hold the phone the other way. |

**Android wiring:** `ui/reader/ScreenBehaviour` applies the window-level effects and **undoes each on
the way out** — an app that leaves the bars hidden, the orientation pinned or the brightness
overridden after you close a book has broken the rest of itself to serve one screen. They apply to
whichever reader is open (flowing text, a PDF's pages, a licensed book in its own WebView) rather
than only the one whose sheet sets them. Both reading modes share **one** text style, because the
paged mode *measures* with it to decide where pages break: if measuring and drawing disagreed about
hyphenation, text would appear mysteriously clipped at the bottom of a page. `ui/DisplaySheet` groups
the settings by the question they answer — how the type is set, how the page is coloured, what the
screen does — rather than as one long list.


## Reading aloud (core built + verified; UI built)

The reader could set a book any way you liked and could not say a word of it. The speech track adds
the voice, and it is built the same way everything else here is: the part that decides how a book
*sounds* is framework-independent and unit-tested, and the part that can only exist on a phone is
kept as thin as it can be.

The substrate was already there. A `Book` is an ordered list of chapters of flowing text with
structure as ranges over it, so one implementation covers EPUB, PDF reflow, Royal Road and AO3 at
once — nothing in the speech package knows what a spine or a serial is. And **canonical character
offsets are already the app's universal position**, which is what makes the voice and the page the
same reader: the narrator's position is an offset, so listening advances progress, resumes in the
reader, and can be annotated, with no second notion of "where I am".

| Area | Type(s) | What it does |
|---|---|---|
| **Units** | `speech/Utterance`, `SpokenRun` | One speakable unit carries both the string handed to an engine and the canonical range it came from, plus the mapping between them. The two differ on purpose — a sentence wrapped over three source lines is spoken as one line, a footnote marker is not read aloud as a stray number, a table row gets commas the reduction never had — and the mapping is what turns an engine's "I am on characters 40..46 of what you gave me" back into offsets the reader can light up. |
| **Sentences** | `speech/SentenceSplitter` | Hand-rolled rather than `BreakIterator`: the platform iterator is locale data, differs between the JVM and Android's ICU (so a test here would prove nothing about the device), and cannot be told that a 900-character sentence has to break *somewhere sensible* because a neural voice cannot hold it. Titles, initials, decimals and a lowercase continuation all keep their sentence together; a runaway one is cut at a clause boundary, then a space, and only ever mid-word as a last resort. |
| **Planning** | `speech/SpeechPlanner`, `SpeechPlan`, `SpeechOptions` | Turns a chapter into ordered units with the pauses between them. Headings are spoken whole and rest longer; verse goes a line at a time because its line breaks are the meaning; a scene break becomes the longest silence in the chapter; code and tables are silent by default and comprehensible when turned on. Text a source left uncovered by any block is still spoken — degrade, don't crash. Plans are **derived data**: never stored, recovered by re-planning, so better pacing later migrates nothing and invalidates no note. |
| **Decisions** | `speech/Narration`, `NarrationState` | What happens *between* sentences — the end of a chapter, skipping back off the top of one, whether the sleep timer stops you now or lets the chapter finish. All ordinals and indices, no audio, so all of it is tested without a device. |
| **Sleep timer** | `speech/SleepTimer` | The one control only ever used by somebody who will not be awake to correct it: it fades out over the last twenty seconds rather than cutting off mid-word, offers *end of chapter* as well as a duration, and gives a reader who reaches for "still awake" a minute late the whole extension rather than what was left. |
| **Resuming** | `speech/Resume`, `SavedPlace`, `ResumePoint` | Which of the two recorded places a book opens at — where the eye left off, or where the voice got to — and in which unit the winner is expressed. |
| **Voices** | `speech/VoiceModel`, `VoiceCatalog`, `VoiceSelection` | A short curated list of open-licence Piper voices with their real sizes and checksums, and the rules for choosing between what is installed — including falling back to the best remaining voice when the chosen one has been deleted, rather than leaving a reader with silence and no explanation. |

**Why on-device neural, and what it costs.** The interesting options were the platform engine (free,
offline, ships with the phone, sounds like a phone), a cloud voice (excellent, and it means paying
per sentence and shipping the book off the device), and a downloaded neural voice run locally. The
third is the one that fits: good enough to listen to for hours, no account, no per-use cost, nothing
leaves the phone, and it works on a plane — which is the same trade Citation already makes
everywhere else. What it costs is a download the reader consents to with the size in front of them,
and the memory and CPU to run it, which is why the catalogue states the quality tier honestly: a
high voice on an old phone can take longer to say a sentence than the sentence takes.

**Android wiring:** `audio/SpeechEngine` is the whole seam — say this unit, tell me when you are
done, tell me the words as you pass them if you can. `SystemSpeechEngine` implements it over the
platform's own engine and is the one that always works; it is also the only one that reports word
boundaries (`onRangeStart`, API 26 — exactly Citation's minimum), so it is the way to get a
word-by-word read-along rather than a sentence one. `NeuralSpeechEngine` runs a downloaded voice and
plays its float samples through a plain `AudioTrack`, reaching the runtime only through
`NeuralSynthesizer`/`NeuralSynthesizers` — so the engine, the planner and the player know nothing
about which runtime is installed, or whether one is at all.

**The neural runtime is sherpa-onnx, driving Piper VITS models** (`audio/SherpaNeuralSynthesizer`,
the one class in Citation that knows a runtime exists). Three files make a voice speak, and they
arrive from three places for reasons worth stating:

- **The model** (60–120 MB) is downloaded, because it is far too large to ship to readers who never
  listen. `VoiceCatalog` names seven English voices with their real sizes and SHA-256s.
- **The token table** beside it maps the model's phoneme inventory to its trained ids, and comes
  down with the model because it is per-voice.
- **The pronunciation data** — espeak-ng's phoneme tables, intonation data, English dictionary and
  English voice definitions — ships **in the APK** (`assets/espeak-ng-data`, unpacked once by
  `audio/EspeakData`). The full data set is 18 MB across 355 files; the part an English voice
  actually reads is **848 KB across 13**, verified by synthesis rather than by guesswork. Small
  enough to always carry, which buys the thing that matters: the first voice a reader downloads
  works offline the moment it lands, with no second download to fail halfway.

The native libraries are neither committed nor optional-in-a-way-that-breaks: `:citation`'s build
fetches them, pinned by version **and** SHA-256, into `build/` (`fetchNeuralVoiceRuntime`, ~45 MB
once, arm64-v8a + armeabi-v7a). The 188 KB Java API is committed to `citation/libs/` instead,
because a failure to reach a host must never be a failure to *compile*. If the fetch is skipped
(`-Pcitation.skipNativeVoices`) or fails, the module still builds, the linker reports the runtime
absent, nothing is registered, the Listen tab says the device's own voice is being used and stops
offering downloads — and books read exactly as they did before speech existed.

**Licensing, stated rather than buried:** sherpa-onnx is Apache-2.0, but its text-to-phoneme path is
piper-phonemize over **espeak-ng, which is GPLv3**, and the pronunciation data in `assets/` is
espeak-ng's. For a personal build that is nothing to act on; distributing the APK would carry
GPLv3's obligations. Removing the runtime is a one-line change (don't register it) and costs only
the neural voice.

`Narrator` is the process-wide coordinator — plan, engine, position, audio focus — because listening
outlives the screen, and `NarrationService` is the media-playback foreground service and
`MediaSession` that keep the process alive and put the controls on the lock screen, the headset
button and the car. Both are written against the platform's own session and notification rather than
a playback library: there is no file, no container and nothing seekable here, just an engine and a
position. Voices live in a store of their own — not the disposable cache, which eviction walks and
which would take a voice the reader is offline with, and not sovereign content, which is the
reader's own and belongs in the backup. Downloads land on a `.part` file and are renamed only after
their checksum verifies, because the failure that actually happens is a download cut short at forty
megabytes, and a runtime handed half a model does not fail politely.

**One deliberate refusal:** listening does not teach the reading-pace estimate. `ReadingPace` answers
"how long will this take *you* to read", learnt from how fast this reader reads; time spent listening
measures the speaking rate of an engine instead, and a listener at 1.5× would drag every "12 min
left" in the app toward a number about nobody. The position still advances and is still saved; only
the pace measurement declines to learn from it, and a reader who disagrees can turn it on.

**Where it is in the app.** One play button and one tab, and the split between them is the point:

- **In the reader**, a play button in the chapter bar, beside Previous/Next. It starts at the **top
  of the page you are looking at** — both reading modes already keep the live position pointed at
  the first line on screen, so the voice picks up where your eyes are rather than at some other
  place the book remembers. While it runs, the bar says so, with the sleep timer's countdown where
  the reading estimate usually sits. Pressing it in a book the voice does *not* have open starts
  that book rather than silently resuming the other one.
- **The Listen tab** holds the player, the voice picker (installed voices, the catalogue with sizes
  and download progress, delete), speed, the sleep timer, and every toggle — including **continue in
  the background**, which is the one thing that decides whether the voice is a feature of the page
  or of the app. It is a tab and not a sheet in the reader because listening is not a property of
  the page you have open: it keeps going with the app closed, and the person reaching for the speed
  or a sleep timer is usually not looking at the book when they do.

Chapter titles are announced when a chapter actually begins — once, and never when the chapter's own
first heading already says the same words, which most EPUBs' do.

**Where the voice got to is its own record.** Listening produces a second "where I was", and it is
kept in its own columns rather than overwriting the reading position (`books.listenChapterOrdinal` /
`listenCharOffset` / `listenedAt`, DB v10). Two reasons, both load-bearing. It is a different *fact*
— recorded with the app in a pocket, routinely hours ahead of the last page anybody looked at, the
way a note records the origin it was captured from. And it is a different *unit*: the voice only
ever knows canonical characters, while `lastCharOffset` holds pixels when the scroll reader saved it
and characters when the paged one did. Both places are timestamped, and `speech/Resume` picks
whichever was reached last when the book is opened — so a chapter listened to on a walk is where you
land, in the reader and in the voice alike. A canonical place is staged on its own restore channel
so the scrolling reader resolves it through the layout (find the line holding that character) rather
than scrolling to a character count as though it were a pixel count.

**Measured, not assumed:** on a container-grade x86 CPU the medium voice loads in ~2 s and
synthesizes at **7–8× realtime** (a 7-second sentence in under a second), streaming in four chunks —
which is what makes pause responsive, since the callback stops the model rather than only the
speaker. A phone will be slower; the `high` voice is slower again, which is why the catalogue states
the quality tier honestly.

**Not built yet:** the read-along highlight. `ChapterRender` already maps canonical offsets to
display offsets and `ReaderScreen` already shades ranges, and `NarrationState` already publishes the
canonical range of the sentence being spoken (and the word, on the platform engine) — so lighting it
up is a new range through machinery that exists on both sides.

## Storage visibility (milestone 7 — core built + verified)

The core aggregator `manifest/StorageInventory` (unit-tested) builds the storage picture and **does
nothing else** — the design's rule is *visibility only, no ceilings, no auto-eviction of favourites
or owned files*. It classifies each item by recoverability (a property of the source: a Royal Road
serial is reclaimable because refetchable, a research PDF irreplaceable), aggregates into a
`StorageReport`, can raise an advisory `shouldWarn` past a soft threshold, and lists the reclaimable
items largest-first — but never returns anything to delete. (Auto-eviction of borrowed, non-favourite
cache is the separately-scoped `rr/EvictionPolicy`, not this.)

**Android wiring:** the repository sums owned-file sizes, RR disposable-cache sizes, and note text
into a report; a **Storage screen** shows the reclaimable-vs-irreplaceable split, per-item footprints
with a recoverability badge, and states plainly that nothing is auto-deleted — you decide what to
prune.

## Cross-app highlight capture (core built + verified)

Capture something from **another app** — a browser selection, an article, a Kindle highlight — and
have it land in Citation with its source attached, without stopping to open Citation. The rule that
shapes it: **build toward apps handing content to Citation, never toward reading another app's page.**
(A live overlay that reads the screen underneath is explicitly out of scope — Kindle renders book
pages as a canvas behind a secure-window flag, so the text isn't in the accessibility tree and a
screen grab comes back black. That's blocked by design and crosses the DRM line.)

A capture is just a **note packet with a source**, so it reuses the note model and the same
fuzzy-to-concrete `BindOrCreate` lifecycle as center-authored book intents. The `:core` spine
(`capture/`, `kindle/`) is fully JVM-tested:

| Area | Type(s) | What it does |
|---|---|---|
| **Provenance ladder** | `capture/ProvenanceLadder`, `Provenance`, `ClusterId` | At capture time, walk an ordered ladder and attach the **best identifier available, never unassigned**: stable book identity (ISBN/RR-id) → URL → filename → title → app package → timestamp (the floor). Marks each **hard** (a real work identity) vs **provisional**. The identifier is a self-describing `tag:value` cluster id (`book:isbn:…`, `url:…`, `title:…`, `app:…`, `ts:…`) frozen as the note's source id — so no extra columns, and rung + identity are re-derivable offline. |
| **Note construction** | `capture/CaptureBuilder` | Maps a provenance into records: a **quoted** capture (browser/Kindle) → `External`-anchored passage note; a **manual** capture (bubble) → freestanding synthesis note. Pure, so the whole mapping is unit-tested without keys or storage. |
| **Retroactive clustering** | `capture/CaptureClusterer` | Groups captures sharing an identifier (same URL, same title) under one **provisional source** — a derived view, before that source is ever a real record. |
| **Promotion** | `capture/CapturePromotion` | When a hard identity later appears (you add the book with an ISBN), **bind** the matching provisional cluster to the real record instead of stranding it — reusing `BindOrCreate` so hard-identity, fuzzy-title, and edition-safety rules all carry over verbatim. |
| **Manual link** | `capture/CaptureLink` | The **user-driven** counterpart to promotion: when a capture never got a hard identity (a browser clip, a floating thought, a Kindle-notebook highlight), you say "this *is* from that book on my shelf." Binds every still-provisional note in the cluster to the chosen book — no fuzzy matching (you decided) — re-pointing `bookKey` only, so the frozen snapshot/title/cluster id survive and the note stays legible. Pure + JVM-tested. |
| **Triage** | `capture/CaptureTriage` | The unresolved / **thin-context view**: surfaces captures whose best identifier is only an app name or a timestamp, so you tag them while you remember. Read-only, derived, optional-and-later — never at capture time. |
| **Kindle import** | `kindle/KindleNotebook` | Parses the Kindle **notebook export** (HTML) into per-highlight captures — a highlight-*import* source, not a live one. **Non-realtime** and bounded by Amazon's per-book clipping limit; ingests exactly what the file holds. Clusters on the book title (the export carries no ISBN) and promotes like any other capture. |
| **Kindle browse** | `kindle/KindleLink.libraryUrl` / `libraryProbeScript` / `cleanTitle` | The **browse-your-shelf** helpers behind "Browse Kindle library" (the read-in-place counterpart to Browse O'Reilly): open your own library on `read.amazon.com`, and when you tap a book, learn its **ASIN** (`asinOf` on the reader URL) + **title** without typing either. The Cloud Reader is an SPA, so a tap swaps in the reader without a navigation — `libraryProbeScript` is polled and hands off once the ASIN appears; `cleanTitle` strips Amazon's `- Kindle edition`-style chrome and rejects the reader's own generic labels so an early poll falls back to the ASIN rather than naming the book "Kindle". `libraryLayoutFixScript` repairs the grid, which otherwise comes up **blank**: the Cloud Reader's `height: 100vh` app-shell collapses to zero under Android's WebView (which mis-computes `vh`), so the covers load into the DOM but never paint — the script pins the shell to `window.innerHeight` in real pixels on load and on rotation. |

**Android wiring:** a chromeless `CaptureActivity` backs the sanctioned entry points — a `PROCESS_TEXT`
"Save to Citation" item in the system text-selection toolbar (any app with selectable text), a
`text/plain` + `text/html` **share target** (including Kindle's highlight-share and notebook export) —
resolves provenance and finishes with a toast, no app switch. A `QuickCaptureBubbleService` +
`ManualCaptureActivity` provide the "display over other apps" **floating bubble** for typing a note
over anything (the catch-all; captures what you type, never the screen). The repository files captures
offline, and importing an EPUB/PDF/RR serial runs **promotion** to adopt any waiting provisional
captures. For the captures promotion can't reach — no hard identity ever arrives — the note detail
dialog offers **"Link to a book"** (`CaptureLink` → `CitationRepository.linkNoteToBook`): pick any book
in your library and the capture's source is bound by hand, along with its cluster siblings, and re-posted
up the mailbox so LifeOps sees the binding. The Personal tab shows a **triage banner**; New has "Import
Kindle notebook"; Settings gates the bubble behind the overlay permission.

## Reading telemetry — engaged time (core built + verified)

Reading is the one activity LifeOps rewards *by time*, so the time has to be honest — otherwise
leaving the app open on a page would farm resources. The measurement is **engaged time**, in pure
`:core` (`reader/ReadingMeter`, JVM-tested, injected clock like `RateBudget`):

- Time accrues **between reading-progress signals** (page turns, scrolls, chapter advances), and each
  open interval is **capped** (default 5 min). A page you genuinely dwell on for four minutes counts
  four; a page left open for thirty counts only the cap; backgrounding the reader pauses accrual
  entirely. Idle dwell can never inflate the total — which is what lets the LifeOps economy reward
  reading with **no output cap**, because the receipts are honest at the source.

`TelemetryPacket` now carries `sourceType` alongside `minutesRead`, so LifeOps can map a session to a
category (**O'Reilly → Learning, Royal Road & Archive of Our Own → Fun**) without ever holding the book. The packet has
always ridden the sync seam; it is now **actually emitted** (it was previously only built in the
walking skeleton).

**Android wiring:** `ReaderViewModel` owns one `ReadingMeter`. A reading session starts on every open
path (flowing / PDF / O'Reilly / Royal Road / jump-to-note) and ends on close; progress is fed from
the existing position signals (`savePosition`, `goToChapter`, `saveOreillyPosition`, and a PDF
page-turn effect); a single lifecycle observer in `ReaderScreen` pauses/resumes across foreground
changes for all three tracks (guarded, so it's a no-op on the home screen). On pause/close the meter
drains to whole engaged minutes — carrying the sub-minute remainder across brief backgrounding — and
`CitationRepository.recordReadingTelemetry` posts a `TelemetryPacket` up the mailbox. Consuming it on
the LifeOps side (source→category→resource at week-close) is the next, LifeOps-side phase.

## Status

All seven task-list milestones plus cross-app highlight capture are implemented; the notes layer
closes the loop from capture to **retrieval** — search, tags, and Markdown export — and reading
emits **engaged-time telemetry** (honest, idle-proof, source-tagged) up the sync seam.

The reader now renders books rather than only their words: structure as ranges over the unchanged
canonical text, the publisher's nested contents, covers and illustrations, and the shelf metadata a
library needs. It also does the things a reader is judged on — search inside the book, bookmarks that
survive the text moving, progress in characters with a time estimate learned from your own honest
reading, a screen that stays on, and word lookup from the selection — and it is set the way you set
it, in the face you chose, and stays that way across restarts. The library is a shelf you can search, sort, facet and organise; and **OPDS** connects
it to catalogs — a Calibre server, Standard Ebooks, Gutenberg, Feedbooks, Kavita/Komga — which is
what turns Citation from an app you put files into, into an app connected to libraries.

The framework-independent spine — internal model, structured document model, keys, dedup,
EPUB/RR/PDF/O'Reilly ingestion, OPDS catalogs, the library query layer, notes + degradation +
retrieval, the sync seam, storage visibility, and the capture provenance/clustering/promotion/triage
logic + Kindle notebook parser + the speech planner — lives in `:core` and is fully JVM-tested
(**585 tests**); the
Android reader (`:citation`) adds Room storage, the Compose readers and shelves, the capture entry
points, WorkManager jobs, the catalog client, and the sync transport on top (buildable with the
Android SDK).

### What "universal" still does not mean

Worth stating as a boundary rather than leaving as an omission. Citation owns the unencrypted world
and reads the walled gardens **in place**, capturing annotations only — the O'Reilly and Kindle
pattern. It does not implement Adobe ADEPT or Readium LCP, so a DRM'd library loan cannot be
rendered by Citation's own reader; a lending catalog's borrow links are shown honestly rather than
offered as downloads. Fixed-layout EPUB, audiobooks, RTL and vertical writing modes, and comic
formats are each a separate track, not a feature — none is started. **Audiobook files** are among
them and are not the same thing as reading aloud: a book with no text has no anchors, no character
positions and nothing to annotate, so an `.m4b` needs its own model and a real player, not a voice.

## Cross-cutting principles (already encoded in `:core`)

- **Offline-first** — minting keys, capturing notes, reading cached content all work with no
  connection; failure is degraded, not crashed.
- **Degrade, don't crash** — a bad EPUB yields recovered chapters; a lost source yields an orphaned
  note; an unresolvable anchor yields NONE, never a wrong jump.
- **Structural safety** — owned data can't be reached by eviction because it isn't in the disposable
  store, not because a check remembered to exclude it.
