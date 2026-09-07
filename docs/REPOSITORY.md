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

### Grabbed off a drive

The third way in, and the one that stops the shelf being a place you *would* file things if it were
not four taps per document. *"Get those project docs out of OneDrive so I can attach them to the
project"* is one flow: choose a drive, pick as many files as you like, **review what you picked**,
file the lot.

> **There is no Google Drive API here and no OneDrive API here.**

That is the decision the whole feature rests on. Both drives — and Dropbox, and the phone itself —
already publish themselves to Android as **document providers**; asking for a file from one is a
picker with a starting point, not a network client. The alternative was an OAuth flow per drive, two
SDKs, API keys in the apk, a token store, a refresh path and a sync loop, all to end up holding the
same bytes the picker hands over for free — and it would break the promise the app makes about
itself, because a client that can list your whole Drive has read a great deal more than the four
files you asked for. Repository still declares **no permissions at all**.

Three things follow, and each is deliberate:

- **The drive chips are a starting point, not a filter.** `EXTRA_INITIAL_URI` opens the picker where
  that drive was last used; the picker will let you walk anywhere from there, and it should. So the
  app never claims a file came from the chip that was lit — it reads the authority off the URI and
  says what is true (`logic/Drives`).
- **The review list is the point.** A folder on a drive is organised for the drive —
  `docs-final-v3-REAL.docx` next to two dead drafts — and the shelf is organised for the household.
  Between the picker closing and anything being copied there is one screen to untick the drafts and
  call the third one what it is. Afterwards is too late: that is three documents filed and three
  dialogs to fix them.
- **A copy, not a link.** What lands is a copy taken at a moment, exactly like a document
  photographed off a kitchen table. Nothing is watched, polled or written back, and the drive is not
  consulted again. A copy the household can see is a copy is honest; a sync that goes wrong is a
  document that quietly changed.

Filing a batch is one row at a time and each row independently: a file that cannot be read takes its
own row down and nothing else. Three of four documents filed is three documents the household has,
and failures come back **named** — "Q3 budget couldn't be read" is a sentence somebody can act on.

### Attached afterwards, not imported twice

A document already on the shelf is **attached** to a project or an asset from that app's own
documents section. Nothing is copied: attaching re-files the row into another drawer, and *Remove
from here* puts it back in the household's. That is what makes the grab-then-attach flow the right
one — importing into the project directly, then importing the same file again for the other thing it
also belongs to, is how one document becomes three copies that then disagree.

## Leaving the device

Exactly one road out: somebody presses **Open** or **Send a copy**.

A copy is written into `cacheDir/exports`, named after the document's title, and handed over through
`RepositoryFileProvider` — which exposes **only that directory**. The documents themselves are never
behind a URI another app could hold. Widening the provider to the store would make every statement in
the house readable by anything that could guess a URI.

Lent documents leave the same way: the owning app supplies its file, the copy is made here. One road,
however the document got onto the shelf.

**Save to a drive** is that same road with a destination attached. The share sheet is right for one
document going to one person; what it cannot do is *this folder, these six documents, again next
month*, because every send is a fresh chooser and a fresh walk through somebody's folder tree. So the
folder is chosen once, per drive, and remembered — "OneDrive · Project docs" is a place the household
named and granted, not one the app guessed at. Each document is written under its own title as a new
file; if one of that name is already there the provider makes "Statement (1).pdf", and that is left
alone deliberately. An export that silently overwrote a file on somebody's drive would be this app
destroying data it does not own to save a rename.

The remembered folder is **not** carried by the backup. It is a URI granted by a provider on this
device to this install; restored onto a new phone it names a folder nothing has permission to open.
Forgetting is honest — the first export after a restore asks once, and remembers again.

## Being pointed at

The shelf can be opened **at a place**, not only started. `logic/RepositoryDestination` is the
vocabulary — the shelf, one drawer, one record's documents, one document — and `RepositoryLinks`
writes each as an address (`drawer/maintenance/a3f2`, `lent/health/lab-1`) for
`MainActivity.intentFor` to put on an intent.

Three things follow from what this app is:

- **A destination is a filter on the one list, never a screen.** The deepest link still lands
  somebody on the shelf, narrowed, with a line saying what they are looking at and everything else
  one press away. A drawer you have to open to see into is a folder by another name, and this app
  exists because a household should not have had to file things correctly to find them. The drawer
  chips and a deep link are consequently the *same* piece of state.
- **A lent document is addressed by its lender as well as its id** (`lent/health/lab-1`). An id is
  only unique inside the app that minted it, which is why the shelf keys its rows on the pair;
  addressing one by id alone is a link that lands on somebody else's document the first time two
  apps mint the same one.
- **An address is checked before the shelf is narrowed to it.** `DocumentRepository.resolve` looks
  for the thing on the merged shelf — sources included — and a destination naming something that has
  gone comes back null, which every caller reads as "open the shelf". Advisor can ground an answer in
  a statement thrown away since; landing somebody on an empty screen insisting nothing is filed is
  worse than landing them on the list.

The intent is **explicit**, with no URL scheme and no exported filter beyond the activity itself.
This module declares no permissions and holds the household's paperwork; a `repository://` scheme
would let anything on the phone address a mortgage statement by URI, which is a surface a filing
cabinet has no reason to offer for a suite that shares one process.

The first caller is the panel itself: a record's documents section carries an **On the shelf** button
that opens the shelf narrowed to that record, which is the second door being opened from inside the
first. Advisor is the obvious next one — it already reads the shelf through
`RepositoryKnowledgeSource` and can now take somebody to what it cited.

## The backup

`repository.db` **and the documents themselves**.

This is the one contributor in the suite where the files matter more than the database. Every other
app's rows could, at worst, be typed again. Here the rows are captions — lose the folder and what is
left is a list of the names of documents nobody has any more. So the files are carried in full, and
on restore they are written **before** the rows that name them.

## Layout

```
repository/src/main/java/com/repository/app/
├── logic/          Pure JVM, unit-tested: DocumentKind · DocumentOwner · DocumentFacts · Documents ·
│                   Shelf · Drive/Drives · Transfer · RepositoryDestination/RepositoryLinks
├── data/
│   ├── db/         Room, one table: rows that name files
│   ├── prefs/      RepositoryPrefs — which drive, and where on it. Never backed up; see below
│   ├── store/      DocumentFiles — filesDir/documents, and the one road out through cacheDir/exports
│   │               DriveTransfer — the picker's side of a drive: describing, granting, writing
│   └── repository/ DocumentRepository — the shelf as the rest of the suite sees it
├── source/         DocumentSource + DocumentSources — the read-only seam for apps that keep their own
├── ui/          (the suite's fields throughout — `SuiteTextField`/`SuiteNoteField`, never a raw one)
│   ├── shelf/      ShelfScreen — one list, one search box, no folders
│   ├── drive/      DriveGrabDialog · DriveSaveDialog — off a drive and back onto one, targeted
│   └── attach/     DocumentsPanel — the section lent to an owning app, and Attach from the shelf
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

`gradle :repository:test` — 76 JVM tests, no emulator needed.

Forty-five of them are over `logic/` and need no SDK at all. The other thirty-one are the store's,
and they are the ones that matter most here, because **the rows in this app are captions**: every
other module's rows could at worst be typed in again, whereas a row that outlives its file is a
document the household believes it has and cannot open, and a file that outlives its row is a
mortgage statement nothing will ever delete.

- `DocumentRepositoryTest` — the store, against a real Room database and a real folder of files. That
  a file which cannot be read leaves *nothing* behind — no row and no half-written file; that
  deleting takes the bytes with the row, through the single delete and through the cascade an owning
  app calls when one of its records goes; that the cascade is keyed on the app **and** the record,
  because two apps number their records from one; that a document another app is only lending cannot
  be renamed, re-filed or deleted from here however it is addressed; that a drawer appears the moment
  its app registers, without the shelf being closed and reopened; that a lender which cannot answer
  costs a drawer and never the list; and that a document leaves by one road under its own title
  whether this app holds it or a lender does.

  Its fixtures are the two things the store is actually wired to and neither is a stub:
  `FakePicker` is a real `ContentProvider` registered with the real `ContentResolver`, so what a
  document is called, what it is stored as and whether a row is written at all are decided the way
  they are decided on a phone — including the file that describes itself happily and then fails to
  open, which is a Google Doc with no exportable bytes and not a contrived case. `FakeSource` is a
  real `DocumentSource`, which is all Repository has ever known about any lender.
- `DeepLinkTest` — the address vocabulary: every destination surviving the round trip, a lent
  document addressed by its lender as well as its id, a key that would split into extra segments
  refused at the point of *writing* rather than misread at the point of parsing, and anything
  unrecognised opening the shelf plainly. The rule the file is about is that a bad address is never a
  plausible one — failing to link somebody to a document is a disappointment, and taking them to a
  different one looks like it worked.
- `ShelfTest` — newest filed first (the only date this app has, because it does not read documents),
  a search that finds the truck's manual by the word "wrangler" while the module still has no idea
  what a Wrangler is, the household's drawer leading, a drawer from an app this build does not have
  still being a drawer, and the two-key match that keeps two apps' record numbering apart.
- `DocumentsTest` — the file's own extension winning, an unknown type being honestly `bin` rather
  than a guess, a title cleaned up but never guessed at, the path-climbing names that are refused
  rather than resolved, and the one name a document leaves under whichever road it takes.
- `DrivesTest` — a drive recognised by the authority on the URI rather than by the chip somebody
  pressed, an old authority still being that drive, and a provider nobody has named importing exactly
  the same way.
- `TransferTest` — what the review list says you are about to file, a total that counts only the
  sizes it was told (a cloud file often reports none), two files that would land under one name being
  *reported* rather than renamed, and failures coming back named.

## What is not here

Named so it is a decision rather than an omission:

- **No `DocumentsProvider`.** Repository does not appear in the system Files app or in other apps'
  Open/Save pickers. It would be the natural next step and is a genuinely large piece of work; the
  request this was built for was reaching a mortgage statement without opening Maintenance, which is
  what the two doors already do.
- **No migration of what already exists.** LifeOps' task attachments, Citation's books and Project's
  documents stay where they are. Health's are *lent* rather than moved.
- **Five apps not wired.** Maintenance and Project file into the shelf and Health lends to it. The
  rest is one dependency and one composable each, per the section above — deliberately left until
  somebody decides where documents belong in those domains.
- **No sync with a drive.** Import and export are targeted and one-shot on purpose: these files,
  now. Watching a folder would mean a credential, a poll, and documents changing under a household
  that believes it filed them. The copy on the shelf is a copy, and the app says so.
- **No versions.** Filing a newer statement adds a document; it does not supersede one. A household
  keeping both is the normal case, and a version chain nobody asked for is a feature that makes the
  list harder to read.
- **No full-text search.** Search covers what a document is called, what it is about, its note and
  its kind — all things a person wrote. Searching *inside* a document would mean reading it, which is
  the line this app does not cross.
