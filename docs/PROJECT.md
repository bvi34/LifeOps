# Project — the document and planning repository

Project is the app that owns *the work you are making*. A shelf of projects; and inside each one,
five ways of looking at the same thing: the **Outline** it is shaped by, the **Docs** it is written
in, the **Lore** it has to stay consistent with, the **Timeline** it happens on, and the **Board**
it gets built through.

It is a hosted library module inside the Operations Sandbox container (`:app`), a peer to LifeOps,
Citation, Logistics, Advisor, Health and People.

```
Operations Sandbox  →  Project  →  a specific project  →  Outline · Docs · Lore · Timeline · Board
                                                          ↳ search · compile
```

## What it is, and what it deliberately is not

Three tools got stirred together here, and it is worth naming which part came from where:

| From | What it contributes |
|---|---|
| **Notion** | Documents made of *blocks* — headings, lists, to-dos, quotes, code — that can be retyped, reordered and ticked one line at a time. |
| **Reedsy** | An *outline* whose rows are parts of the work itself: they nest, they carry a status through drafting and revision, and they carry a **length** against a target. |
| **Kanban** | A board of columns and cards, with limits that warn, for what is actually being done this week. |

What it is **not** is a scheduler, and the line is finer than "no dates" — which is where this
started and is no longer quite right. A card can carry a **due date**: the competition closes on the
14th, the draft is promised on the 30th. What Project will never hold is **when you will do it**.

That distinction is the whole of it. A due date is a *fact about the work* and belongs with the
work; a plan is a *decision about your time* and belongs to LifeOps, which is where a week is
planned. It is exactly the line Maintenance already draws — it knows the furnace is due a service
and says nothing about which evening you will spend on it — and holding it is what stops there
being a second answer to "what am I doing today", which in practice means both stop being true.

Concretely, that rule shows up as things Project does not have: no agenda, no calendar, no today
screen, no lane that reorders itself by date, no card hidden for being far off. A dated card looks
exactly like an undated one but for the chip saying when it is due, coloured because a board is read
at a glance and "overdue" has to survive that glance. Nothing else on the board changes.

Project is also **not on the suite's sync spine**. People replicates because two apps genuinely
write the same person; nothing else in the suite writes into a project, so there is nothing to
reconcile and no `syncVersion` column anywhere in the schema. Adding one later would be a design
change, not a migration.

## The sections

### Outline — what the thing is made of

A tree, stored as a parent pointer plus a sort order among siblings. Four edits rearrange it: **up**,
**down**, **indent**, **outdent** — on the row itself rather than behind a drag gesture, because
dragging a nested tree on a phone is a coin toss between "moved" and "re-parented", and the moment
you most need certainty is the moment you are moving a scene between chapters.

Every row carries a **status** on a drafting ladder — Idea, Outlined, Drafting, Drafted, Revised,
Done — plus **Cut**. Cut is the entry that matters: cut material stays in the tree, keeps its words,
and stops counting towards anything. Deleting it instead is how people lose the scene they cut in
August and wanted back in October.

Word counts **roll up**, and only from leaves. A chapter that holds scenes has no words of its own,
so a stale number on the parent can never double-count the manuscript. A project's headline number is
the sum of every root's rollup.

Two structural decisions are held deliberately in `logic/Outline`:

- **Orphans are shown, not dropped.** A node whose parent has gone is drawn as a root. Walking only
  from the real roots would quietly hide rows that still exist in the database, and a piece of
  writing you cannot see is a piece of writing you cannot rescue.
- **A cycle cannot hang the screen.** Parent pointers get edited by a UI and restored from backups,
  so a loop is possible. The walk emits each node at most once: the worst a cycle can do is leave
  part of the tree unreachable, rather than spin.

### Docs — the writing itself

Documents are stacks of blocks. The store is blocks because that is what an editor needs in order to
reorder a line, turn a paragraph into a heading, or tick a to-do without rewriting the document —
and because a to-do's ticked state is a fact about a line, not a character in it.

The **interchange is Markdown, in both directions**. Paste a chapter in and it becomes blocks; the
same document goes back out to the clipboard or the share sheet as Markdown, with numbered runs
renumbered from 1 so a list that lost its middle item does not export as 1, 2, 4. A repository you
cannot get writing *out* of is a hostage situation, not a tool — and an export buried in a settings
screen is one most people never find, which is why both live in the document's own menu.

Documents **nest**. A project keeps its chapters in a folder and its research beside them rather than
in one flat list of forty, and the nesting is the same parent-pointer tree as the outline, walked by
the same code — so a document orphaned by a deleted folder is still drawn, at the top level, rather
than vanishing with it. A document cannot be filed inside itself or anything under it; the picker
never offers the move rather than refusing it afterwards.

**Reading mode** drops the fields and draws the blocks as Markdown. Emphasis reads as emphasis, a
quote gets a rule down its side instead of a literal `|`, code sits on its own ground, a numbered
list counts up, and links open. Nine tenths of the time a document is opened it is to be read, and a
page of outlined input boxes reads like a form. Everything a reader can act on stays live: to-dos are
tickable, because a checklist you cannot tick is a picture of a checklist.

**Nothing is rewritten to make it draw prettily.** The asterisks you typed stay in the block, in the
export and in the field you edit; the emphasis is a *reading* of the text computed on the way to the
screen. That is what keeps the round trip honest, and it is why unmatched markers stay literal — a
lone `*` at the end of a line is an asterisk, not the start of an italic run that eats the paragraph,
and a `*` inside a code span is never touched at all.

**Tables are blocks.** A table's rows are not paragraphs, and joining them the way hard-wrapped prose
is joined is exactly how a stat block becomes a grey wall of pipes — which is what a pasted table
used to look like here. So a table is one block holding its own source, drawn as a grid: header band,
ruled and banded rows, columns sized to their content, alignment as the delimiter row's colons ask
for, scrolled sideways when it is wider than the phone. A wide table can also be read **a row at a
time**, each row a card of labelled values, which is the only honest way to show eight columns on a
phone; which way round you read tables is remembered, like reading mode, because it is a fact about
the screen in your hand rather than about any one table.

Tables written before this **repair themselves**: a table that lost its line breaks kept its pipes,
and the shape can be recovered from them — the delimiter row and the header propose a column count,
and whichever divides the cells into rows that end where rows should end wins, so a hand-written rule
one `---` short does not shunt every cell a column to the left. "Rebuild tables" appears in a
document's menu only when that document has one to rebuild.

**Word count is prose only** — code blocks and dividers are excluded, and a table counts its cells
rather than its scaffolding. A count that jumps by four hundred because you pasted a config file is a
count nobody trusts again, and one that counts `|` and `---` as words you wrote is the same lie in a
smaller font. Search reads documents the same way: it matches the words, so `**left**` is found by
searching for *left*, and a hit inside a table shows the cells rather than the pipes between them.

A document with headings has a **contents sheet** — the headings indented by level, tapping one
scrolling to it. A long draft without one is a scroll bar and a hope.

A document can be **linked to an outline node**, and that link is the join that makes this one app
rather than five. Link a doc to a scene and the scene's word count becomes the sum of the documents
written for it, kept in step on every edit. The outline stops being a plan you maintain beside the
writing and becomes a view of it.

**Files are not writing, and are not kept here.** A brief that arrived as a PDF, the signed contract,
the reference images somebody was sent — none of them are documents this app should be re-typing into
blocks, and all of them are things a household later goes looking for without remembering which app
they came in through. So the Docs screen opens with a **Files** card that is Repository's section,
lent in place (`com.repository.app.ui.attach.DocumentsPanel`): the same rows appear on the suite's
shelf, searchable there, while sitting on the project here. Files can be added from this phone,
**grabbed off Google Drive, OneDrive or Dropbox** in one batch, or **attached from the shelf** —
attaching re-files a document rather than copying it, so a brief that is already filed does not become
a second copy that then disagrees with the first. Deleting a project takes its attached files with it,
because Repository does not cascade on somebody else's rules and this app has said what deleting one
of its records means.

### Lore — what it has to stay consistent with

A wiki: characters, places, factions, items, events, concepts. Entries link with `[[double
brackets]]` and **only** with those. Nothing is linked because a word happened to appear in a
sentence — automatic entity detection in a fiction wiki produces a graph of coincidences, and the one
link you actually wanted is then indistinguishable from the forty you didn't.

Entries carry **aliases**, so "Kestrel", "the Captain" and "Kes" resolve to one person.

Two lists here earn their place over any amount of browsing:

- **Backlinks** — where an entry is spoken of, collected without anybody having recorded them. Write
  "trained under `[[Kestrel]]`" anywhere, and Kestrel's page knows.
- **Broken links** — every name the project has referred to and never written down. That is exactly
  the list of pages worth writing next, and each one is a tap from becoming an entry.

And one rule that is easy to get wrong: **an ambiguous name resolves to nothing.** If two entries
answer to "the Captain", the link is reported ambiguous rather than pointed at whichever row the
database returned first. A wiki that quietly picks one will be wrong on precisely the entry you were
relying on it for, and will never say so. Ambiguous names are listed separately from broken ones —
they need a rename, not a new page.

### Timeline — what happens, in what order

The design decision everything else follows from: **the author's order is the timeline; the "when"
label is a note about it.**

A project timeline has to hold "2026-03-04", "Year 412 of the Concord", "Day 3", "Chapter 8" and
"the night before the coronation" — often in the same project. Any scheme that insists on a sortable
value either refuses the vague entries or invents a number for them. So an event carries an explicit
order *and* a free-text label, and the app reads a number out of the label where it can (dates as
epoch days, years — with BC as a negative — day counts, chapter numbers).

What it does with that number is not to rearrange the list. It is to say: **you put the coronation
before the battle, and dated it after.** That is the error a story timeline exists to catch, and it
is only catchable because the two facts are kept apart instead of one being derived from the other.

Reordering by the labels is offered as a button, and only when every label reads on one scale — the
author may well be right and the label a typo. A chapter number going backwards is never reported as
a contradiction: that is a flashback.

Events group into **eras** as consecutive runs, so an era that recurs later is a second band rather
than a merge — which is what "the war, then peace, then the war again" actually looks like.

### Board — what is being done about it

Columns and cards, seeded on creation with names that match the kind of project (a manuscript gets
Ideas / Drafting / Revising / Done; software gets Backlog / In progress / Review / Shipped). A card
can point at an outline node or a document.

**WIP limits warn and never refuse.** A board that rejects the card in your hand teaches you to lie
to it — you park the work somewhere it doesn't belong, and the board stops describing reality, which
was the only thing it was for. The done column is never "over limit": finishing things is not a
problem.

**Deleting a column does not delete its cards.** They are stranded on purpose, and a banner at the
top of the board finds them and offers to re-file them. Losing a column is an organisational
decision; losing the work that was in it is never one anybody made on purpose.

Moving a card is a menu of destinations rather than a drag, for the same reason as the outline's
arrows: dragging between two columns that are half off-screen is a guess, and the one thing a board
must never be is unsure where it just put your work.

## Due dates — the only date here

A board card, and nothing else, can be given a day it is due. Not an outline node: a chapter is a
*part of the thing being made*, and giving structure a deadline is how an outline quietly turns into
a schedule. Not a project either — you do not put "The Kestrel" on Tuesday's list.

It is stored as an **epoch day**, nullable, and null is what almost every card holds. A day rather
than an instant because a deadline is a date on a calendar, not a moment; nullable rather than zero
because epoch day zero is a real date, and a zero here would file every undated card fifty years
overdue.

`logic/Due` reads a date against a `today` that is **passed in**, never taken from the clock inside
itself. That is what makes "overdue by 3 days" testable at all, and on the screen it is why a lane
reads the day once rather than per card — a list that asks the clock per row can disagree with
itself as it scrolls past midnight.

Three rules it holds:

- **A finished card is never late**, however late it was. Leaving it red would have the board carry
  a permanent accusation about work that is already behind you, which is not what anybody keeps a
  board to be told. It says *"Was due 12 Feb"* instead.
- **Nothing is ever refused.** A date that has already gone is remarked on and accepted, because
  people write down deadlines they have missed and that is how a board comes to reflect reality
  rather than the plan somebody had in January. The remark comes through the suite's own
  `SuiteVerdict`, which is the mechanism by which a shared picker offers every date and asks the app
  what it makes of the one chosen.
- **Near dates are counted in days, far ones given as a date.** "In 3 days" lands where "17 Mar" has
  to be worked out; past a week, "in 74 days" is a number nobody converts back into a day of the
  year. The month names are spelled out in `logic/` rather than taken from a formatter, so a test
  that passes in London passes in Berlin.

This is also what makes a **hand-off to the LifeOps week** possible, which was not before: a card
with no date is nothing a planner can place. That hand-off does not exist yet — see
[MAINTENANCE.md](MAINTENANCE.md#the-lifeops-week) for the shape it will take.

## Versions — the way back

Two edits in this app can throw a whole document away in one tap: **replacing it with pasted
Markdown**, and **rebuilding its flattened tables**. What a project holds may be the only copy of
that writing anywhere, and a confirmation dialog is not a safety net — it asks somebody who has
already decided.

So a version is kept first, automatically, and the document's menu offers **Keep this version** for
the rewrites the app cannot see coming — the ones you do yourself, a paragraph at a time. The
history lists them newest-first, each saying **why** it was kept rather than only when, because a
column of timestamps is not something anybody can choose from. Open one to read what the document
said; restore it to go back.

Four rules make it a safety net rather than a log:

- **A restore keeps the current text first.** The moment you most want what you just replaced is
  the moment after replacing it, so going back is itself undoable and the history is not a one-way
  door. A version is also not consumed by being restored — the same one can be returned to twice.
- **Nothing empty is filed.** A new document holds one empty paragraph; without this, opening one
  and pasting into it would file a version of nothing and put it at the top of the list.
- **Nothing identical is filed.** If the version at the top already holds exactly what is about to
  be replaced, a second copy buys nothing and costs a slot — so restoring twice, or pasting back
  what was already there, does not push four real versions off the end.
- **The last twenty are kept**, oldest dropped, and the screen says so rather than leaving it to be
  discovered when a version somebody wanted has quietly gone.

A version stores its **blocks**, not rendered Markdown, and that is the whole reason the table has
the shape it does. Markdown is this app's *interchange* format and is lossy in the ways interchange
formats are: an empty paragraph does not survive the round trip, a paragraph that happens to begin
`- ` comes back as a list item, and a numbered run is renumbered. Every one of those is fine when
exporting and none of them is acceptable when the copy is the thing you are restoring from.

Versions belong to a document and **cascade with it**. That is the honest scope: this is a history
of a document, not a wastebasket for deleted ones. Undeleting a document would be a different
feature with a different lifetime, and pretending this one covers it would be worse than not
offering it.

## Compile — the whole thing as one document

The operation the app exists to make possible, and the reason the outline↔document link is worth
maintaining on every edit. The outline knows the order, the documents hold the text, so "give me the
manuscript" is a walk rather than a feature. Nothing is stored; a compile is derived on demand and
thrown away, because a cached one would be a second answer to "how long is this" that goes stale the
moment somebody types.

Options — headings, synopses, cut material, unplaced documents — recompile as you toggle them, so
"what does it look like with the cut scenes back in?" is a question you answer by asking it. The
options are carried on the result and the renderer takes none of its own, which removes a whole class
of bug: a summary that counts a cut scene as omitted while the text beside it prints the scene anyway.

The rule that shapes the screen: **a hole is reported, never hidden.**

- Pieces of the outline with nothing written for them are listed *by name* before the preview. A
  compiler that silently skipped them would hand you a manuscript with a scene missing and no way to
  know — the failure you discover after sending the file to somebody.
- Documents belonging to no piece of the outline are counted even when they are excluded, because
  "12 documents are not in this export" is the sentence that saves you.
- Only *leaves* count as holes: an act is supposed to have no text of its own.

The result goes out to the clipboard or the share sheet as Markdown, like everything else here.

## Opening at a place

Until recently the only thing anything outside Project could do was *start* it. Advisor could quote
a scene and never take you to it; the sandbox could open the app but not a project; a notification
would have had nowhere to land. `MainActivity` now takes one intent extra —
`com.project.app.extra.OPEN_DESTINATION` — holding an address in the vocabulary of
`logic/ProjectLinks`:

| Address | Opens |
|---|---|
| `shelf` | The shelf, explicitly — "open Project and do *not* reopen what I had last time". |
| `project/<id>` | That project, on whichever section you last used. |
| `project/<id>/<section>` | That project, on `outline`, `docs`, `lore`, `timeline` or `board`. |
| `project/<id>/doc/<id>` | That document in the editor, with its project underneath it on the back stack, so Back means what it means everywhere else here. |

The addresses are deliberately the app's own nav routes, so there is one vocabulary for "where in
Project" rather than a private one for callers and another for the nav graph. Build the intent with
`MainActivity.intentFor(context, destination)`; it carries `CLEAR_TOP | SINGLE_TOP`, so a second
link reaches the instance already running rather than stacking another copy of the app on it.

Three rules keep a link from being a way to break the app:

- **A stale address opens the shelf.** Every one names rows by id, and by the time it is followed
  the row may be gone — Advisor answers from a snapshot and can quote a document thrown away since.
  So `ProjectRepository.resolve` checks it first, and a document is checked *against its project*
  rather than merely for existing: an address pairing a real document with a different real project
  would open the editor with a back stack leading somewhere it was never filed.
- **Nonsense opens the app.** Parsing is total and returns `null` for anything unrecognised,
  including an address written by an older or newer build. An unknown *section*, though, is refused
  rather than read as the outline: landing somewhere plausible is how a caller's typo survives to
  ship, appearing to work while showing the wrong screen every time.
- **There is no URL scheme and no exported filter beyond the activity.** Project requests no
  permissions and holds writing that never leaves the device; a `project://` scheme would let any
  app on the phone address its rows, which is a surface it has no reason to offer inside a suite
  that shares one process.

The app's own **"reopen the project you left"** goes through the same path rather than being a
special case in the nav graph — it is a destination like any other. That is the point of the shape:
the remembered place is exercised on every single launch and a link is exercised rarely, so sharing
one implementation means the rare one is not the untested one.

## Search — across all five sections

Until this existed only Lore could be searched, which meant that past about forty documents the
repository stopped being a repository and became a pile.

The matching is deliberately plain: **case-insensitive substring, all terms required, matched against
the whole record.** That last part is the one that matters — "kestrel smuggler" has the name in the
title and the description in the body, and requiring every term to land on the same side would find
nothing while the entry sits there containing both words.

No stemming and no fuzzy matching. For a corpus of one person's own project that is not a compromise:
you are looking for a phrase you wrote yourself and can spell, and fuzzy matching over a small corpus
mostly produces confident wrong answers. A search that returns the scene you did not mean is worse
than one that returns nothing and lets you retype.

Ranking is title-before-body, then section order, then alphabetical — fully deterministic, so the same
query always gives the same list. Results are grouped by section; a document hit opens the document,
and anything else takes you to the section that holds it.

## Vocabulary

A project has a **kind** — Writing, Software, Research, General — and it decides nothing except what
the app calls things. A manuscript's outline is made of chapters and scenes; software's is made of
features and tasks; research has sections and findings. Same tree, same storage. Calling a scene a
"task" is the small indignity that makes a writing tool feel like a ticket system, and it costs one
enum to avoid. Only Writing and Research are measured in words.

## Where the thinking lives

Everything that decides anything is pure Kotlin in `project/logic/`, unit-tested on the JVM:

| File | What it decides |
|---|---|
| `Tree.kt` | Walking a parent-pointer tree — the outline's and the docs' folders alike: depth-first order, numbering, orphans drawn rather than hidden, cycles that terminate, and whether a re-parent is legal. |
| `Outline.kt` | Rolling up words and finished pieces from the leaves, and the four structural edits. |
| `Manuscript.kt` | Compiling the outline and its documents into one document, and finding the holes in it. |
| `ProjectSearch.kt` | Matching, snippets and ranking across all five sections. |
| `DocBlocks.kt` | The Markdown ↔ blocks round trip, word counts, headings, list numbering, to-do progress, previews. |
| `MarkdownInline.kt` | Emphasis, code, strikethrough and links inside a line — read, never rewritten. |
| `MarkdownTables.kt` | Pipe tables: reading them, rendering them back, and rebuilding ones that lost their line breaks. |
| `Lore.kt` | Wiki links, the name/alias index, backlinks, broken links, ambiguity. |
| `Timeline.kt` | Reading a "when" label, era bands, gaps, and order contradictions. |
| `Board.kt` | Lanes, WIP-limit state, moving a card between columns, orphans, default columns. |
| `ProjectPulse.kt` | The one line under a project's name on the shelf. |
| `Revisions.kt` | Which versions of a document are worth keeping, which are copies of each other, and which fall off the end of the cap. |
| `DeepLink.kt` | The addresses that say where in Project to open, and what is not a valid one. |
| `Due.kt` | How a due date stands against a day, what to call it, and what Project makes of one being picked. |

That is 164 JVM unit tests. The two guarantees the tree walk makes — orphans drawn, cycles
terminating — are each asserted directly, because both are the kind of thing that is invisible until
the day it costs somebody a folder full of writing.
| `ProjectKind.kt` | The vocabulary each kind of project speaks. |

The Android side (`data/`, `ui/`) adds Room storage and the Compose screens and decides nothing else:
every structural edit is a pure function that returns *the rows that changed*, and the repository's
job is to write exactly those.

## The controls it does not own

Every field on every screen here is the suite's — `SuiteTextField`, `SuiteNoteField`,
`SuiteNumberField` from `:suiteui`. Project used to write `OutlinedTextField` out longhand
twenty-five times, which is how the rest of the suite got the way it was before `:suiteui` existed:
one screen's field filtered non-digits and the one beside it did not, one filled its row and another
did not, and each had a private opinion about supporting text. None of that is a decision worth an
app making twice.

Three things changed by adopting them rather than merely tidying up:

- **The two count fields are counts.** A WIP limit and a word target now take digits and nothing
  else. The board had hand-rolled that filter; the outline had not, so "about 2000" typed into a
  word target went through `toIntOrNull()` and quietly became no target at all. The filter that
  replaces both is `SuiteInput`, which is pure JVM in `:suitekit` and unit-tested there — coverage
  this app now inherits instead of duplicating.
- **Fields capitalise sentences; search boxes do not.** A raw `OutlinedTextField` capitalises
  nothing, so every name and title here used to start lowercase unless you reached for shift. The
  shared field has the suite's opinion instead — and the two search boxes opt out of it, because a
  query is not a sentence.
- **Prose fields have a ceiling.** They grew without limit before, which in a dialog means a long
  note pushes the buttons off the bottom of the screen.

What is *not* shared is the pickers, and that is the honest answer rather than an oversight.
`:suiteui` offers a colour, date, time and when picker; Project has no date, time or when to pick —
deciding when something happens is LifeOps' job, and the timeline's "when" is free text on purpose
(see above). Its own `ui/common/Pickers.kt` picks a piece of the outline or a document, which is a
question about *this app's* tree and has no suite equivalent. Colours are assigned from a rotating
palette and there is no UI to change one, here or in Maintenance, which stores the same column.

## Storage

One Room database, `project.db`, with ten tables and one project id threaded through all of them.
That column is the architecture: every section is scoped to a project and cascades with it, so
deleting a project cannot leave a doc or a card behind for a query that forgot to filter. The two
exceptions are `doc_revisions` and `doc_revision_blocks`, which hang off a *document* rather than a
project — they are versions of one document and go with it, and the project cascade reaches them
through it.

The **cross-section links are soft** — a card's outline node and document, a doc's outline node, an
event's scene — declared without a foreign key so deleting a scene does not delete the notes written
about it. When an outline subtree *is* deleted, those links are explicitly cut in the same
transaction: a link that dangles for ever is indistinguishable from one that was never made. Every
one of them is settable from the section that owns it, and always alongside a **Not linked** option,
since un-linking has to be as easy as linking or the link becomes a trap.

Derived numbers are **written down, not recomputed on read**. A document's word count lives on the
document and a scene's on the scene, because the shelf draws hundreds of them at once. Every write
that could change a count updates it in the same breath, in one place, so the stored number and the
blocks cannot drift.

### The three claims above, tested

Every promise on this page so far is a promise about SQLite rather than about Kotlin, and none of
them can be reached by reasoning the way `logic/` can. `ProjectRepositoryTest` asks the database
directly, against a real in-memory Room instance rather than a fake DAO — a fake would answer each
question with whatever the test assumed:

- **the cascade**, including the two-step one: blocks belong to a document, which belongs to a
  project, so deleting a project has to reach through two foreign keys — and has to leave the
  project sitting next to it entirely alone;
- **the soft links**, from both ends: deleting a scene keeps the document written for it, the event
  dated to it and the card about it, each with its link cut rather than left to dangle; deleting a
  document leaves the card that was about it;
- **the word counts**, through every path that can move them — typing, adding, deleting and
  reordering a block, pasting a chapter in, filing a document under a different scene, deleting the
  last document a scene had, and renaming a scene, which must *not* move them.

It also holds the two board decisions that look like bugs until you know them: deleting a column
strands its cards instead of deleting them, and a card cannot be moved onto another project's board.

### The upgrade path

`ProjectMigrationTest` is the one this app's backup story rests on. It reads `project/schemas/`,
builds a real database from **each version that has ever shipped**, and opens it through the same
builder the app uses (`ProjectDatabase.builder`) — so a migration added to the app is a migration
the test is already running. Opening is the assertion: Room compares every table, column, index and
foreign key it finds against what the entities describe, and throws with the difference.

The subtle part is what stops that test agreeing with itself. Change an entity without raising
`PROJECT_DB_VERSION` and Room does not complain — it silently **rewrites** the exported schema for
the version already on disk, so the test would build a database from the new shape and cheerfully
confirm it. The phone that has had the app since release still holds the old shape. So the test
pins each released version's **identity hash** as a constant of its own, where regenerating a schema
cannot reach it, and fails naming the version that moved. There is deliberately no destructive
fallback in the builder: for most of the suite that would cost a re-sync, and here it would delete
the only copy of somebody's writing.

## Backup

`ProjectBackupContributor` contributes the whole `project.db` plus Project's own `project_*`
preferences to the Operations Sandbox archive, keyed under `project/`. Because it is the whole file,
the versions kept for each document come with it and need no handling of their own.

Restoring an archive written by an **older** version of the app puts that older database on disk, so
the restore path and the upgrade path are the same path: the file is swapped in, Project reopens it,
and the migration runs. That is why `ProjectMigrationTest` opening a version 1 file through the
production builder is not an abstract exercise — it is the restore.

The database is copied as **bytes**, not re-serialised as JSON, and that matters more here than
anywhere else in the suite: a project's documents may be the only copy of that writing that exists —
there is no cloud workspace holding a second one — so the backup has to be the file, not a
re-export that a future schema change could quietly narrow. A WAL checkpoint runs first, so the
copied file is the whole database and not a stale main file beside a log holding this morning's
chapter.

## Privacy

Project requests **no permissions** and has no `INTERNET`. Nothing it holds leaves the device. There
is no cloud workspace, no collaborator, and no share sheet — the way writing leaves is that you copy
the Markdown out, or you take a sandbox backup.

That is still true with the Files card in the Docs section. Getting a file off Google Drive or
OneDrive is **Android's own picker** — those drives publish themselves as document providers — so
there is no client, no credential and no permission behind it, and nothing is fetched that somebody
did not point at. See [REPOSITORY.md](REPOSITORY.md).
