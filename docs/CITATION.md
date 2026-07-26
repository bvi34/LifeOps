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
resolver, the sync protocol, and the PDF/O'Reilly pieces below, **`:core` has 120 passing JVM unit
tests** across 24 suites (run `gradle :core:test`).

### `:citation` (Android)

Built on `:core`, following LifeOps' Screen → ViewModel → Repository shape:

- **Storage** (`data/db`): Room *sovereign* DB (books, chapters, notes, highlights, key watermarks,
  sync state); `data/store/FileStores` gives the two physical directories — owned files under
  `filesDir/sovereign`, borrowed bodies under `cacheDir/disposable` (the only dir eviction is ever
  handed). Anchors/references serialize via `AnchorCodec`.
- **Repository** (`data/CitationRepository`): restores the key allocator + mailbox from the DB
  (minting and sync versions survive restarts), then drives import → persist → capture → queue.
  Offline-first throughout.
- **Reader** (`ui/ReaderScreen`, `ReaderViewModel`): renders the internal model — format-blind —
  with typography (font size), chapter paging, position save/restore, and note capture over a text
  selection.

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
| **O'Reilly deep-link** | `oreilly/OreillyLink` | Build/parse the URL to a book + position, so reopening lands one tap from your spot; re-auth is left to the WebView by design. |

**Android wiring:** `PdfReaderScreen` renders pages via the platform `PdfRenderer` (own track, page
nav, page-anchored notes); PDF import hashes + dedups into the owned store. `OreillyReaderScreen`
hosts O'Reilly's own reader in a WebView (**no content cache** — licensed), tracks your position from
the URL, and captures **annotations only** (quote + location token as an `External` anchor) into the
sovereign store. Library gains "Import PDF" and "Add O'Reilly book"; opens route by source type to the
right track.

## Roadmap (task-list milestone 7, not yet built)

7. **Storage visibility** — per-item + aggregate size, recoverability-tagged; visibility only, no
   auto-eviction of favourites/owned.

## Cross-cutting principles (already encoded in `:core`)

- **Offline-first** — minting keys, capturing notes, reading cached content all work with no
  connection; failure is degraded, not crashed.
- **Degrade, don't crash** — a bad EPUB yields recovered chapters; a lost source yields an orphaned
  note; an unresolvable anchor yields NONE, never a wrong jump.
- **Structural safety** — owned data can't be reached by eviction because it isn't in the disposable
  store, not because a check remembered to exclude it.
