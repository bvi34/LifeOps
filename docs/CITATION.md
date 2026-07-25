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

**62 JVM unit tests** across 12 suites cover all of the above (run `gradle :core:test`).

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

## Roadmap (task-list milestones 2–7, not yet built)

2. **RR read loop** — in-app WebView as skim/catalog only; on story-start fetch+extract ch1 through
   the reader; rolling ~5-chapter prefetch buffer.
3. **RR favorites** — full backfill (slow lane), integrity manifest wired to the disposable store,
   per-fiction RSS feed as the update *detector* + scrape as the *body puller*, 7-day eviction via
   WorkManager, two rate budgets, cross-lane priority queue (buffer always wins).
4. **Notes complete** — selection toolbar → anchor, both note types surfaced, orphaned-note UI.
5. **Sync seam** — the transport that drains the outbox to LifeOps and consumes acquire intents;
   bind-or-create at resolution; both state machines surfaced.
6. **PDF + O'Reilly** — PDF render track (positioned glyphs, page+quads anchors); O'Reilly
   read-in-place (their WebView is the reader, no local content cache, deep-link back to position).
7. **Storage visibility** — per-item + aggregate size, recoverability-tagged; visibility only, no
   auto-eviction of favourites/owned.

## Cross-cutting principles (already encoded in `:core`)

- **Offline-first** — minting keys, capturing notes, reading cached content all work with no
  connection; failure is degraded, not crashed.
- **Degrade, don't crash** — a bad EPUB yields recovered chapters; a lost source yields an orphaned
  note; an unresolvable anchor yields NONE, never a wrong jump.
- **Structural safety** — owned data can't be reached by eviction because it isn't in the disposable
  store, not because a check remembered to exclude it.
