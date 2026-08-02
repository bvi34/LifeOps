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
| **Internal model** | `model/Book`, `Chapter`, `BookMetadata`, `SourceType` | The one normalized representation every format produces and the reader alone consumes. A Book = ordered Chapters of flowing text + metadata. |
| **Entity keys** | `key/EntityKey`, `KeyAllocator` | Partitioned, Jira-style keys (`ER-Book-3`) minted **offline with zero coordination**; each app owns a namespace so peers never collide. Provenance is baked into the key. |
| **Dedup** | `identity/IdentityKey`, `IdentitySet`, `DedupValidator` | Typed identity per source: RR fiction id (authoritative), ISBN (**edition-aware** — DDIA 1st ≠ 2nd; ISBN-10/13 of one edition match), PDF SHA (strong positive, **weak negative**). Matches on the strongest shared evidence. |
| **EPUB** | `epub/EpubParser`, `Html` | Zip + OPF + spine → `Book`; deterministic HTML→flowing-text reduction (the stable surface notes anchor against). Dependency-free (`java.util.zip` + regex). Degrades to recovered chapters rather than throwing. |
| **Manifest** | `manifest/IntegrityManifest`, `StorageReport` | Per-favourite expected-vs-cached, gap detection, per-chapter bytes. One object answers "is it whole?" and "what does it cost?", tagged reclaimable vs irreplaceable. |
| **Anchors** | `anchor/TextAnchor`, `FuzzyAnchor` | Typed anchors (flowing quote+locator; PDF page+quads). **Quote + fuzzy match** re-resolution survives RR edits and re-exports; a deleted passage orphans (NONE) rather than misjumping. |
| **Notes** | `note/Note`, `Highlight`, `PassageReference`, `SourceDescriptor` | Two kinds kept distinct: **passage-anchored** (one highlight) and **freestanding synthesis** (may cite several). Each carries a **frozen quoted snapshot** + typed anchor, so a note **outlives its source** — intact-but-orphaned, never lost. |
| **Ownership** | `store/Ownership`, `Store` | The two-store split as policy: only borrowed (RR) content is `DISPOSABLE`/evictable; owned files and all notes are `SOVEREIGN`. Eviction structurally cannot reach owned data. |
| **Sync** | `sync/Mailbox`, `Packets`, `BookLifecycle`, `BindOrCreate` | Mailbox pattern (state/outbox + inbox, **monotonic version**, idempotent + resumable). Up: telemetry + note packets carrying `{sourceType, sourceId, frozen-context}`. Down: acquire-book intents. **Bind-or-create** reconciles a fuzzy center-authored book to a resolved artifact at one checkpoint. **Two orthogonal state machines** — acquisition (`wanted→resolving→acquired/unavailable`) and reading (`to-read→reading→done`) — never collapsed. |

The walking skeleton is covered by JVM unit tests; together with the Royal Road engine, the note
resolver, the sync protocol, the PDF/O'Reilly pieces, and the storage aggregator below, **`:core`
has 125 passing JVM unit tests** across 25 suites (run `gradle :core:test`).

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
    pure, unit-tested line-walker); only the last/first page crosses a chapter boundary. A **Scroll**
    mode (one continuous column, chapter-at-a-time turns) stays one tap away in the format sheet.
  - **Resume** actually restores: reopening lands on the saved chapter *and* position — a scroll offset
    in scroll mode, or the page's start **character offset** (font-size independent) in paged mode —
    persisted as you read.
  - **Reading comfort:** a format sheet with a paged/scroll toggle, text size, line spacing, margins,
    serif/sans, and Paper / Sepia / Night / System themes.

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
| **PDF render track** | `pdf/PdfTrack` | Positioned glyphs don't reflow, so a PDF renders *pages*, never through the flowing reader. View↔PDF coordinate transform (Y-flip, **zoom-stable quads**), page/quad anchor construction, and the SHA-256 import identity (strong-positive dedup). |
| **External anchor** | `anchor/TextAnchor.External` | A read-in-place anchor for content Citation never holds: the source reader's opaque **location token** + the frozen quote. Resolves via deep link (best-effort), not text matching; `NoteResolver` + `SyncCodec` handle it. |
| **O'Reilly deep-link** | `oreilly/OreillyLink` | Build/parse the URL to a book + position, so reopening lands one tap from your spot. Optionally routes through a library proxy (see below); parsing is host-agnostic, so a proxied position round-trips unchanged. Also builds the **browse** URL (O'Reilly's catalog, proxied) and pulls a book's `slug` out of a catalog link, title-cased into a readable library name (`titleFromSlug`). |
| **Library proxy** | `oreilly/OreillyLibraryProxy` | EZproxy host rewriting — `learning.oreilly.com` → `learning-oreilly-com.mcpl.idm.oclc.org` — so you reach the licensed content on a **library card**, not a personal O'Reilly account. Encode (`.`→`-`, `-`→`--`) is reversible, so hosts round-trip; `rewrite` is idempotent. Defaults to Mid-Continent Public Library, any EZproxy host is configurable. |
| **Warm page cache** | `oreilly/OreillyCachePolicy` | Policy for the WebView's HTTP cache of pages you've opened — so a reopen is fast and a page re-reads during a brief disconnect. The invariant it enforces: this is **never a permanent copy of licensed content**. It's always `Store.DISPOSABLE`, always `RECLAIMABLE` (refetchable by reopening online), and TTL-bounded — `shouldPurge` drops it once it's sat untouched past 7 days (and never while the reader is open). |
| **Auto-reauth** | `oreilly/EzproxyLogin` | The reauth brain: detect an OCLC/EZproxy sign-in page (`isLoginPage`) and build a safe credential-fill/submit script (`fillScript`). Selectors lead with MCPL's confirmed form (`POST mcpl.idm.oclc.org/login`; card `name=user id=cardnum`, PIN `name=pass id=pin` — the card field is itself `type=password`, so the card never falls back to a `type=password` selector), then generic fallbacks for other libraries. Credentials are embedded as JSON string literals so a card/PIN can't break out of the script. It's the *user's own* library credential into the *library's* form — it never touches O'Reilly's DRM or federated tokens. |

**Android wiring:** `PdfReaderScreen` renders pages via the platform `PdfRenderer` (own track, page
nav, page-anchored notes); PDF import hashes + dedups into the owned store. `OreillyReaderScreen`
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
| **Triage** | `capture/CaptureTriage` | The unresolved / **thin-context view**: surfaces captures whose best identifier is only an app name or a timestamp, so you tag them while you remember. Read-only, derived, optional-and-later — never at capture time. |
| **Kindle import** | `kindle/KindleNotebook` | Parses the Kindle **notebook export** (HTML) into per-highlight captures — a highlight-*import* source, not a live one. **Non-realtime** and bounded by Amazon's per-book clipping limit; ingests exactly what the file holds. Clusters on the book title (the export carries no ISBN) and promotes like any other capture. |
| **Kindle browse** | `kindle/KindleLink.libraryUrl` / `libraryProbeScript` / `cleanTitle` | The **browse-your-shelf** helpers behind "Browse Kindle library" (the read-in-place counterpart to Browse O'Reilly): open your own library on `read.amazon.com`, and when you tap a book, learn its **ASIN** (`asinOf` on the reader URL) + **title** without typing either. The Cloud Reader is an SPA, so a tap swaps in the reader without a navigation — `libraryProbeScript` is polled and hands off once the ASIN appears; `cleanTitle` strips Amazon's `- Kindle edition`-style chrome and rejects the reader's own generic labels so an early poll falls back to the ASIN rather than naming the book "Kindle". |

**Android wiring:** a chromeless `CaptureActivity` backs the sanctioned entry points — a `PROCESS_TEXT`
"Save to Citation" item in the system text-selection toolbar (any app with selectable text), a
`text/plain` + `text/html` **share target** (including Kindle's highlight-share and notebook export) —
resolves provenance and finishes with a toast, no app switch. A `QuickCaptureBubbleService` +
`ManualCaptureActivity` provide the "display over other apps" **floating bubble** for typing a note
over anything (the catch-all; captures what you type, never the screen). The repository files captures
offline, and importing an EPUB/PDF/RR serial runs **promotion** to adopt any waiting provisional
captures. The Personal tab shows a **triage banner**; New has "Import Kindle notebook"; Settings gates
the bubble behind the overlay permission.

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
category (**O'Reilly → Learning, Royal Road → Fun**) without ever holding the book. The packet has
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

All seven task-list milestones plus cross-app highlight capture are implemented; the notes layer now
closes the loop from capture to **retrieval** — search, tags, and Markdown export — and reading now
emits **engaged-time telemetry** (honest, idle-proof, source-tagged) up the sync seam. The
framework-independent spine — internal model, keys, dedup, EPUB/RR/PDF/O'Reilly ingestion, notes +
degradation + retrieval, the sync seam, storage visibility, and the capture
provenance/clustering/promotion/triage logic + Kindle notebook parser — lives in `:core` and is fully
JVM-tested; the Android reader (`:citation`) adds Room storage, the Compose readers, the capture entry
points, WorkManager jobs, and the sync transport on top (buildable with the Android SDK).

## Cross-cutting principles (already encoded in `:core`)

- **Offline-first** — minting keys, capturing notes, reading cached content all work with no
  connection; failure is degraded, not crashed.
- **Degrade, don't crash** — a bad EPUB yields recovered chapters; a lost source yields an orphaned
  note; an unresolvable anchor yields NONE, never a wrong jump.
- **Structural safety** — owned data can't be reached by eviction because it isn't in the disposable
  store, not because a check remembered to exclude it.
