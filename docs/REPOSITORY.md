# Repository — the suite's shelf

Every app in this suite eventually hits the same wall: a thing it tracks has a piece of paper
attached to it. The mortgage statement belongs to the house, the manual to the furnace, the warranty
to the mower, the lab result to a person. Solved once per app, that becomes five stores, five
backups, and a household that has to remember which app it filed something in.

Repository is the shelf. One place the paperwork lives, and **two doors onto the same documents**:

- **Its own screen** — everything filed, in one list, searchable, without knowing which app it came
  in through. This is the door for *"where is the mortgage statement"* when you do not want to think
  about Maintenance at all.
- **The section it lends** — a composable an owning app drops onto its own screen, so the furnace's
  manual sits on the furnace. Same rows, worked on from either end.

That is the whole product. Everything below is how it stays that simple.

## What it does not do

**Repository stores documents. It does not read them.** No OCR, no extraction, no parsing, no "we
noticed your rate went up". A stored document is handed back exactly as it arrived, and every fact
the app holds about one — what kind it is, what it is called, what it is about — is something a
person typed or an owning app supplied.

That line is what makes it safe to put a mortgage statement and a lab result in the same drawer:
nothing in the module is capable of knowing what is in either. It declares **no permissions at all**,
and unlike the apps that do, it has no lookup that could ever earn one.

Parsing a statement into a balance is a real feature and a useful one. It belongs in the change that
owns it, not smuggled in underneath a file picker.

## The one architectural decision

**A document knows what it is about by carrying a label, not a foreign key.**

When Maintenance files a manual it says: *app `maintenance`, record `a3f2`, and that record is called
"2018 Jeep Wrangler"*. Repository stores all three and understands none of them. It cannot ask
Maintenance what `a3f2` is, because it does not know Maintenance exists — the dependency arrow points
**into** this module and never out.

```
:maintenance ──▶ :repository ◀── :health
      files into        lends to
```

The cost is that a label can drift when a record is renamed. The panel pays it: it calls `relabel`
every time it is composed, which is cheap, idempotent, and rewrites the truck's documents the moment
you rename the truck. The alternative — this module knowing what an asset, a person and a project are
— is a structural cost that never stops being paid.

`logic/` is Android-free and holds the whole of the reasoning: what a kind is, what a size reads as,
what a search matches, how the drawers order. It is unit-tested with no emulator.

## Two ways a document gets onto the shelf

### Filed here

The bytes are copied into `filesDir/documents/` and a row names the file. That shape is **Health's**,
which proved it: the bytes sit beside the database rather than inside it, the row stores only a file
name, and the backup carries the folder and restores it *before* the rows that name it.

One rule inside that is worth stating out loud:

> **Pictures are re-encoded. Everything else is copied byte for byte.**

A *photograph* of a document is a photograph — twelve megapixels of a sheet of A4, most of it noise —
so it is downsampled and written as JPEG. A *PDF* is the document itself, very often the lender's own
file with the letterhead and the signature on it. Re-encoding it would produce a different file from
the one the household was given, and "different from what the bank sent" is exactly the property a
record must not have.

### Lent by the app that already has it

Health kept documents before Repository existed, and kept them properly. Moving them would be a
migration of the most sensitive rows in the suite to buy a tidier diagram, and the household would
get nothing for the risk.

So the shelf reads them where they are. `source/DocumentSource` is an app saying *here is what I am
holding, and here is the file when somebody presses Open*. It is **read-only on purpose**: the shelf
can find a lab result and send it to a printer, but cannot rename, re-file or delete one, because the
rules for that are Health's — a person's documents are deleted with the person — and a second writer
would either duplicate those rules or break them.

Registration is a registry rather than a dependency list, for the same reason LifeOps announces task
completions on a bus: a build without Health simply has one fewer drawer. Nothing to stub, nothing to
keep in sync.

## Leaving the device

Exactly one road out: somebody presses **Open** or **Send a copy**.

A copy is written into `cacheDir/exports`, named after the document's title, and handed over through
`RepositoryFileProvider` — which exposes **only that directory**. The documents themselves are never
behind a URI another app could hold. Widening the provider to the store would make every statement in
the house readable by anything that could guess a URI.

Lent documents leave the same way: the owning app supplies its file, the copy is made here. One road,
however the document got onto the shelf.

## The backup

`repository.db` **and the documents themselves**.

This is the one contributor in the suite where the files matter more than the database. Every other
app's rows could, at worst, be typed again. Here the rows are captions — lose the folder and what is
left is a list of the names of documents nobody has any more. So the files are carried in full, and
on restore they are written **before** the rows that name them.

## Layout

```
repository/src/main/java/com/repository/app/
├── logic/          Pure JVM, unit-tested: DocumentKind · DocumentOwner · DocumentFacts · Documents · Shelf
├── data/
│   ├── db/         Room, one table: rows that name files
│   ├── store/      DocumentFiles — filesDir/documents, and the one road out through cacheDir/exports
│   └── repository/ DocumentRepository — the shelf as the rest of the suite sees it
├── source/         DocumentSource + DocumentSources — the read-only seam for apps that keep their own
├── ui/
│   ├── shelf/      ShelfScreen — one list, one search box, no folders
│   └── attach/     DocumentsPanel — the section lent to an owning app
└── backup/         RepositoryBackupContributor — the database and the files
```

## Wiring a new app in

One dependency and one composable:

```kotlin
// build.gradle.kts
implementation(project(":repository"))

// wherever the record is shown
DocumentsPanel(
    appKey = AppId.PROJECT.key,
    recordKey = project.id,
    recordLabel = project.name
)
```

The panel owns its own state, its own picker, its own dialogs. The only other thing an app should do
is say what deleting one of its records means — Repository does not cascade on somebody else's rules:

```kotlin
shelf.documents.deleteFiledOn(AppId.PROJECT.key, project.id)
```

An app that already stores its own paperwork implements `DocumentSource` instead and registers it in
its `install`, which is what Health does.

## Tests

`gradle :repository:test` — 18 JVM tests over `logic/`, no SDK or emulator needed.

- `ShelfTest` — newest filed first (the only date this app has, because it does not read documents),
  a search that finds the truck's manual by the word "wrangler" while the module still has no idea
  what a Wrangler is, the household's drawer leading, a drawer from an app this build does not have
  still being a drawer, and the two-key match that keeps two apps' record numbering apart.
- `DocumentsTest` — the file's own extension winning, an unknown type being honestly `bin` rather
  than a guess, a title cleaned up but never guessed at, and the path-climbing names that are refused
  rather than resolved.

## What is not here

Named so it is a decision rather than an omission:

- **No `DocumentsProvider`.** Repository does not appear in the system Files app or in other apps'
  Open/Save pickers. It would be the natural next step and is a genuinely large piece of work; the
  request this was built for was reaching a mortgage statement without opening Maintenance, which is
  what the two doors already do.
- **No migration of what already exists.** LifeOps' task attachments, Citation's books and Project's
  documents stay where they are. Health's are *lent* rather than moved.
- **Six apps not wired.** Maintenance files into the shelf and Health lends to it. The rest is one
  dependency and one composable each, per the section above — deliberately left until somebody
  decides where documents belong in those domains.
- **No versions.** Filing a newer statement adds a document; it does not supersede one. A household
  keeping both is the normal case, and a version chain nobody asked for is a feature that makes the
  list harder to read.
- **No full-text search.** Search covers what a document is called, what it is about, its note and
  its kind — all things a person wrote. Searching *inside* a document would mean reading it, which is
  the line this app does not cross.
