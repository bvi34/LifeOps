# Project — the document and planning repository

Project is the app that owns *the work you are making*. A shelf of projects; and inside each one,
five ways of looking at the same thing: the **Outline** it is shaped by, the **Docs** it is written
in, the **Lore** it has to stay consistent with, the **Timeline** it happens on, and the **Board**
it gets built through.

It is a hosted library module inside the Operations Sandbox container (`:app`), a peer to LifeOps,
Citation, Logistics, Advisor, Health and People.

```
Operations Sandbox  →  Project  →  a specific project  →  Outline · Docs · Lore · Timeline · Board
```

## What it is, and what it deliberately is not

Three tools got stirred together here, and it is worth naming which part came from where:

| From | What it contributes |
|---|---|
| **Notion** | Documents made of *blocks* — headings, lists, to-dos, quotes, code — that can be retyped, reordered and ticked one line at a time. |
| **Reedsy** | An *outline* whose rows are parts of the work itself: they nest, they carry a status through drafting and revision, and they carry a **length** against a target. |
| **Kanban** | A board of columns and cards, with limits that warn, for what is actually being done this week. |

What it is **not** is a scheduler. Project never says when you will do something, and there is no
date on a card. Deciding what today looks like is LifeOps' job, and a second planner would be a
second answer to "what am I doing today" — which, in practice, means both of them stop being true.

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
same document renders back out as Markdown, with numbered runs renumbered from 1 so a list that lost
its middle item does not export as 1, 2, 4. A repository you cannot get writing *out* of is a hostage
situation, not a tool.

Inline formatting is left alone on purpose. Bold and italic stay as the asterisks you typed:
half-parsing Markdown — structure yes, emphasis no — is the version that loses least, and an editor
that silently eats a literal `*` in a shell command is worse than one that shows it.

**Word count is prose only** — code blocks and dividers are excluded. A count that jumps by four
hundred because you pasted a config file is a count nobody trusts again.

A document can be **linked to an outline node**, and that link is the join that makes this one app
rather than five. Link a doc to a scene and the scene's word count becomes the sum of the documents
written for it, kept in step on every edit. The outline stops being a plan you maintain beside the
writing and becomes a view of it.

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
| `Outline.kt` | Flattening the tree, numbering rows, rolling up words and pieces, and the four structural edits. |
| `DocBlocks.kt` | The Markdown ↔ blocks round trip, word counts, headings, to-do progress, previews. |
| `Lore.kt` | Wiki links, the name/alias index, backlinks, broken links, ambiguity. |
| `Timeline.kt` | Reading a "when" label, era bands, gaps, and order contradictions. |
| `Board.kt` | Lanes, WIP-limit state, moving a card between columns, orphans, default columns. |
| `ProjectPulse.kt` | The one line under a project's name on the shelf. |
| `ProjectKind.kt` | The vocabulary each kind of project speaks. |

The Android side (`data/`, `ui/`) adds Room storage and the Compose screens and decides nothing else:
every structural edit is a pure function that returns *the rows that changed*, and the repository's
job is to write exactly those.

## Storage

One Room database, `project.db`, with eight tables and one project id threaded through all of them.
That column is the architecture: every section is scoped to a project and cascades with it, so
deleting a project cannot leave a doc or a card behind for a query that forgot to filter.

The **cross-section links are soft** — a card's outline node, a doc's outline node, an event's scene
— declared without a foreign key so deleting a scene does not delete the notes written about it. When
an outline subtree *is* deleted, those links are explicitly cut in the same transaction: a link that
dangles for ever is indistinguishable from one that was never made.

Derived numbers are **written down, not recomputed on read**. A document's word count lives on the
document and a scene's on the scene, because the shelf draws hundreds of them at once. Every write
that could change a count updates it in the same breath, in one place, so the stored number and the
blocks cannot drift.

## Backup

`ProjectBackupContributor` contributes the whole `project.db` plus Project's own `project_*`
preferences to the Operations Sandbox archive, keyed under `project/`.

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
