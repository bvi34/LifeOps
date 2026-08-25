# People — the household directory

People is the app that owns *who*. It keeps the household roster — names, relationships, how to
reach someone, the dates that come round again, and the running notes you keep about them — and it
keeps LifeOps in step with that roster over a **two-way sync seam**, the same mailbox spine Citation
rides.

It is a hosted library module inside the Operations Sandbox container (`:app`), a peer to LifeOps,
Citation, Logistics, Advisor and Health.

## Why sync, and not simply "one owner"

Logistics doesn't keep its own food catalog: it reads LifeOps' through `LifeOpsCatalog`, live, in the
same process. That is the right answer when exactly one app can meaningfully edit the data.

People is not that case, and pretending otherwise is what makes household directories rot. LifeOps
**mints people on its own** — a Google Calendar attendee it has never seen becomes a person the
moment a synced event arrives — and it hangs five foreign keys off them (`task_people`,
`busy_block_people`, `busy_blocks`, `milestones`, `person_notes`). Meanwhile you type birth dates and
phone numbers into People. Both ends write; neither can be demoted to a reader without either
breaking LifeOps' calendar ingest or making People a read-only viewer of a table it is supposed to
own.

So People **replicates** rather than borrows: each peer keeps its own roster, and they reconcile.
That also means LifeOps' schema was left exactly as it was — its `persons` table and every key into
it are untouched. The migration that made LifeOps a peer is purely additive (three columns and a
tombstone table); nothing was moved, dropped, or de-keyed.

## What it does

| Screen | Purpose |
|---|---|
| **Roster** | The household, plus **Coming up** — every birthday, anniversary and yearly appointment within the next two months, soonest first. Sync status and a **Sync now** button live at the bottom. |
| **Person** | One person: their details (shared over the seam), the dates that come round, and the timeline notes (which stay in People), with archive/restore. |

A person's birth date doubles as a birthday automatically, so nobody enters the same date twice.

## The sync seam

```
                         shared folder (filesDir/people-sync)
People    ──▶ people-people.json  ◀──┐
LifeOps   ──▶ lifeops-people.json ◀──┼── every peer writes its own, reads the others'
Health    ──▶ health-people.json  ◀──┘
```

Three peers, full mesh. Each writes one envelope and reads everyone else's; merge is convergent and
idempotent, so no peer has to be the hub and no round has to happen in a particular order.

Both peers are in one process and share a `filesDir`, so the transport is a folder both can see —
`filesDir/people-sync`, a sibling of the `sovereign/sync` folder Citation uses. The point of a folder
rather than a direct call is that either side can be restarted, replaced or tested on its own.

### Symmetric, unlike the Citation seam

The Citation seam has two envelope types because it is asymmetric: Citation sends telemetry *up* and
LifeOps sends acquire-intents *down*. Here both peers say the same kind of thing about the same
people, so there is **one** envelope type and each peer writes its own copy of it. `acks` is a map
keyed by peer name rather than a single number, which is what let Health join the folder later
without a format change or a cursor migration.

### The outbox is derived, not stored

Every local edit bumps that row's `syncVersion` from a counter, and the outbound envelope is simply
*"every row above what the peer acknowledged"*. There is no separate durable queue to fall out of
step with the rows after a crash. LifeOps draws its counter across both `persons` and
`person_tombstones` so an edit and a withdrawal can never share a version — the peer orders by
version, and a tie would leave the order of a delete and an edit to luck.

The rule that keeps a round from echoing for ever: **a local edit stamps a new version and
`updatedAt`; a write that arrived over the seam stamps neither.** A merged row that got re-stamped
would look, to us, exactly like something worth publishing, and the two peers would hand the same
person back and forth until one of them was closed.

### Who creates, and who only keeps up

Not every peer should hold the household outright.

| Peer | Creates people from the seam? | Why |
|---|---|---|
| **People** | yes | It *is* the directory. |
| **LifeOps** | yes | It already mints people on its own from calendar attendees; a person it learns about is one the household has. |
| **Health** | **no** | It annotates people rather than holding them. It tracks temperatures and doses for whoever is actually ill, and a roster that silently grew a medical profile for every adult in the house would be worse than no sync at all. |

A **bind-only** peer (`PeopleSyncEngine(createUnknown = false)`) still keeps every person it *has*
chosen in step — names, birth dates, withdrawals — and adding someone stays an explicit act in that
app. It also removes an awkward corollary: a peer that never auto-creates can delete its own row
without the next round handing the person straight back, which is why Health needs no tombstone table
while LifeOps does. Removing somebody from Health means *stop tracking their health*, not *remove
them from the household*.

What Health gets out of the seam is worth having on its own: a person added in People arrives with
their **birth date**, which is exactly what the age-aware fever thresholds need — and which nobody
wants to type twice.

### Binding: is this arriving packet somebody we already have?

`PersonBinder` — People's answer to `:core`'s `BindOrCreate` — tries three rungs and stops at the
first that matches:

1. **The shared `personKey`.** Already linked.
2. **Email**, case-insensitively. The one field in a household roster that is genuinely an identifier.
3. **A normalized name** — case, spacing, punctuation and accents folded away, so
   `Dr. José  García-López` and `dr jose garcia lopez` are one person. This is a *guess*, and it is
   last on purpose: two people in one household really can share a first name, so it only applies
   when the local candidate has no email of its own to contradict it.

### Merging: what happens when both sides edited the same person

**The newer record wins field by field, but a blank never beats a value.**

Whole-record last-write-wins is simpler and is wrong here in a way that shows up immediately: LifeOps
holds an email and usually nothing else (it minted the person from a calendar attendee); you typed
the birth date and phone number into People. Under record-LWW, whichever app you touched last
silently erases the other's half of the person. Merging per field — with the newer record given
precedence only where it actually says something — keeps both halves and still converges, because
both peers apply the same rule to the same pair and get the same answer.

**Identity converges on the lower key.** Two peers that bound by email or name are holding the same
human under two invented keys. Leaving each with its own works only while the *name* keeps matching —
rename the person on one side and the next round binds nothing and creates a duplicate. Adopting the
incoming key doesn't fix it either: both sides would adopt the other's and swap, round after round.
Taking the lower of the two is deterministic, so both peers reach the same key from either direction
and stay there. A peer only adopts a key no *other* local row already holds; when it's taken the row
keeps its own and the peers go on binding by name — weaker, but never two people sharing one identity.

Two deliberate asymmetries:

- **`archived` is a state, not a gap**, so the newer record's answer stands outright.
- **A delete archives; it never erases.** A peer can withdraw somebody from its own list without
  deciding the household's whole record of them should stop existing.

### Deletes, and why LifeOps needs tombstones

Removing a person in People *archives* the row, so there is still something to publish. LifeOps
genuinely deletes — `task_people`, `busy_block_people`, `busy_blocks` and `milestones` all cascade
off it, and that behaviour is older than the seam. But a deleted row is precisely the thing that
would have carried the news, so without a trace of it People would hand the person straight back on
the next round. `person_tombstones` is that trace, and nothing more: the key, the name, and a version
so the withdrawal takes its turn in the envelope like any other change.

### What is *not* on the wire

The packet is smaller than any peer's row, on purpose. LifeOps' weather-comfort tolerances
(feels-like ceilings, UV max, sun sensitivity) and sort order stay in LifeOps; People's per-person
colour and timeline notes stay in People; Health's readings, baseline temperature and **medical
notes** stay in Health.

That last one is the sharpest case and is refused explicitly rather than left to whoever next edits
the mapper: Health's `notes` field holds allergies, conditions and the doctor's number, while
People's `note` means "likes hiking, hates crowds". They share a name and nothing else. Mapping one
onto the other would copy a person's conditions into the household directory and from there into
LifeOps — the kind of leak a shared wire makes a one-line mistake. **A field only one peer
can edit has no business on a shared wire** — syncing it would make every other peer an authority
on data it cannot show, cannot change, and would happily overwrite with a stale copy.

One field crosses a type boundary and is handled explicitly: LifeOps files `relationship` as a closed
enum, People keeps it as free text. The wire carries the stable enum *value* (so it round-trips), and
anything that doesn't parse is kept out rather than written in. Storing `"Daughter"` in a column
every reader parses as an enum wouldn't merely be lossy — `Relationship.from` returns null for it, so
the person would silently drop out of the relationship-balance analytics that column exists to feed.

## Module layout

```
:people (Android library, com.people.app)
├── sync/             pure JVM, unit-tested — the contract both peers share
│   ├── PersonPacket      a person on the wire + the symmetric PeerEnvelope
│   ├── PersonBinder      key → email → normalized name
│   ├── PersonMerge       newer wins per field; a blank never beats a value; delete = archive
│   ├── PeopleSyncEngine  one round, over :core's Mailbox; createUnknown picks the peer's policy
│   └── PeopleSyncCodec   JSON codec + the per-peer file transport
├── logic/            pure JVM, unit-tested
│   └── ImportantDates    recurring dates, incl. the 29 February case
├── data/             Room (PeopleDatabase, entities, PeopleDao) + repository + prefs
├── ui/               Compose: roster · person detail (+ common, theme)
├── backup/           PeopleBackupContributor (whole-file people.db + people_* prefs)
├── PeopleApp.kt      tiny runtime container (install/get)
└── MainActivity.kt   roster → person, and a sync round on open
```

`:people` depends on `:core` for `Mailbox` — the monotonic-version bookkeeping every peer syncs over
— rather than growing a second implementation of the same protocol. Both peers run the *same* engine
and the *same* merge rule out of this module; a merge rule implemented twice is a merge rule that
disagrees with itself the first time either copy is edited.

## LifeOps' side

`PeopleSyncRepository` is the counterpart to `CitationSyncRepository`, and is built the same way:
cursor accessors are plain lambdas so a round can be driven in a test against a temp folder.
`LifeOpsApp` runs a round at startup, right after the Citation ingest, so the People screen and
LifeOps' own person pickers agree before either is rendered. On a first run that same round is how
the household LifeOps already knows about reaches a freshly-installed People — the seam does the work
an import step would have.

**MIGRATION_51_52** is additive only:

- `persons.personKey` — the cross-peer identity, backfilled from `id` so existing people already have
  one and publish under a key People will keep.
- `persons.syncVersion` — seeded at 1 for existing rows, precisely so the current household flows
  into People on the first run.
- `persons.updatedAt` — the merge clock, epoch millis. Zero for rows that predate the seam, which is
  the honest answer: we don't know when they were last touched, and a fabricated timestamp would win
  merges it has no right to.
- `person_tombstones` — the withdrawal trace described above.

## Backup

`PeopleBackupContributor` (registered as `AppId.PEOPLE`) copies the whole `people.db` plus People's
own `people_*` preferences — which is where the sync cursors live.

The cursors are carried on purpose, and it is worth saying why, since "restore the data, not the
bookkeeping" is the more obvious instinct. A restored device with its cursors reset to zero would
re-take every packet still sitting in the peers' envelopes. The merge is idempotent, so that sounds
harmless — but it would resurrect people who had since been archived on this side, because their old
packets would arrive looking newer than nothing at all. Restoring the cursor with the rows keeps the
seam where the rows left it.

## Privacy

People declares **no permissions**. It notably does *not* read the device's contacts: the household
directory is what you typed into it, not a mirror of your phone book, and the moment it could read
contacts it would become the app that quietly slurped them.

Advisor can read People behind the usual per-app gate, denied by default. It indexes **People's**
copy and not LifeOps' `persons` table — indexing both would put two documents about the same person
into a corpus that has no idea they are the same person, and the retriever would cite whichever is
staler.

## Tests

Pure-JVM suites under `people/src/test` (run with `gradle :people:testDebugUnitTest`) — 44 tests:

- `PersonBinderTest` — the key outranking everything, email binding across differently-typed names,
  a name binding through case/punctuation/accents, and the two-Alexes case where a contradicting
  email stops a name match.
- `PersonMergeTest` — the newer record winning per field, a blank never beating a value, an older
  record still filling gaps, convergence from either direction, `archived` as a state, a delete
  archiving rather than erasing, a stale delete not undoing a newer edit, an identical packet
  reporting no change so nothing echoes back, and two invented keys converging on one — from either
  direction, and stably once reached.
- `PeopleSyncEngineTest` — creation, idempotent re-application, the cursor skipping consumed packets
  *and* advancing past ones that merged to nothing, binding by email mid-round, a tombstone for
  somebody we never had creating nobody, a **bind-only peer** keeping up with its own people while
  ignoring the rest (and still accepting a withdrawal), name-binding surviving a later **rename**
  because the key converged, and a **full two-peer round** ending with both rosters agreeing.
- `PeopleSyncCodecTest` — envelope round-trip, unknown fields ignored, garbage decoding to null
  rather than throwing, and the file-per-peer store (including that no temp file is left behind).
- `ImportantDatesTest` — next-occurrence rollover, today counting as next, the **29 February** case
  landing on the 28th in common years, rejecting invented dates like 31 April, the age being turned,
  and countdowns that read the way a person would say them.
