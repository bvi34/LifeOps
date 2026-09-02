# LifeOps

A single-user, offline-first **weekly operations log** for your life — part task
manager, part time tracker, part honest mirror. You plan a week, do the work, log
the hours, and close the week. Completed work earns **resources**; every week is
permanently recorded as one ring in a **Growth Record** that can't be faked, ground,
or back-dated.

LifeOps is opinionated and a little sardonic. It is not a gentle habit app — it keeps
the receipts.

> **New here?** The full end-user manual is **[docs/USER_GUIDE.md](docs/USER_GUIDE.md)** —
> written for someone who just installed the app and wants to know how to use it.

> **Citation** (the companion reading app) is a separate module and a peer on the sync spine —
> see **[docs/CITATION.md](docs/CITATION.md)**. Its framework-independent core lives in `:core`
> (pure JVM, unit-tested); the Android reader is `:citation`.

> **Operations Sandbox** is the container these apps now ship inside — it's the `:app` module, the
> single installed application and the central hub the whole suite opens through. It opens on a
> **phone-style home screen**: a tile per app in that app's own hand-drawn mark and colour — no two
> share a silhouette, so a tile is recognisable before its colour registers — over a dock holding
> the gear and the backups, with a **weather tile** under the clock for wherever the phone is. One launcher that opens LifeOps (`:lifeops`, the standard app), Citation
> (`:citation`), Logistics (`:logistics`), Advisor (`:advisor`), Health (`:health`), People
> (`:people`), Project (`:project`), or Maintenance (`:maintenance`); one place to back the whole suite up into a single `.zip` and restore from it; and one
> place that decides what all eight of them **look** like — a shared preset and light/dark mode, plus
> an accent per app, applied by every hosted screen, and a **wallpaper** for its own home screen
> (a shipped design, your own gradient, or the suite's colours). LifeOps, Citation, Logistics, Advisor, Health,
> People, Project and Maintenance are library modules hosted in that one process —
> see **[docs/OPERATIONS_SANDBOX.md](docs/OPERATIONS_SANDBOX.md)**. The backup format/engine is the
> pure-JVM, unit-tested `:backupkit`; the appearance contract is the pure-JVM, unit-tested
> `:suitekit`, with its Compose theme in `:suiteui`.

> **Advisor** (the private, on-device assistant) is a peer module — see **[docs/ADVISOR.md](docs/ADVISOR.md)**.
> It's the suite's **RAG** layer: it answers questions grounded in your own data across LifeOps,
> Citation, Logistics, Health and People, under an explicit **per-app permission gate** (denied by default). It also
> keeps **identity-based data** as a portable JSON file, a set of **standing named profiles** (user,
> LLM persona, projects) it references by name and can write to via a `@remember` directive, and a
> **dedicated, heavily-tagged long-term memory** database it recalls from. A **unifying engine (C3A)**
> coordinates all of it and decides whether to answer, ask, or flag a missing source — on the rule
> *"not knowing is acceptable; being wrong without asking is not"*, so when it's unsure it asks
> instead of guessing. The
> language model is a local **Qwen3-4B** (Q4_K_M GGUF) run on-device via llama.cpp, wired in behind a
> single `LocalLlmEngine` seam; until the weights are dropped onto the device it falls back to a
> deterministic, grounded placeholder, so Advisor works either way. The retrieval, permissions, recall
> and prompt assembly around it are real and JVM-unit-tested in `advisor/logic/`. It requests no
> `INTERNET`; nothing leaves the device.

> **People** (the household directory) is a peer module — see **[docs/PEOPLE.md](docs/PEOPLE.md)**.
> It owns *who*: the roster, how to reach someone, the dates that come round, the notes you keep
> about them, and a **daily check-in** — a small form you write for one person and answer once a day
> ("Lunch", "Enjoyed", "How the day went"), with the questions, their kinds and their order all
> yours. A question you rename is renamed on every day it already recorded; one you take off the form
> keeps its answers rather than taking six months of lunches with it; and a day exists only if it
> says something, so an untouched form records nothing. Check-ins stay in People — they ride neither
> the sync seam nor a partner pairing — and it keeps **LifeOps in step over a two-way sync seam**, the same mailbox spine
> Citation rides. Both apps can edit the same person (LifeOps mints them from calendar attendees;
> you type birth dates into People), so the roster is **replicated rather than borrowed**: each peer
> keeps its own rows and they reconcile. **Health is on the seam too**, and takes only the people
> ticked as **household members** in the directory: tick somebody and Health grows a profile for
> them with their birth date, which is exactly what its age-aware fever rules need; leave them
> unticked and it never hears about them. Un-ticking stops Health being offered them and never
> deletes what it already recorded. The merge rule is *newer wins field by field, but a blank never beats a value* —
> record-level last-write-wins would let whichever app you touched last erase the other's half of
> the person. LifeOps' `persons` table and all five foreign keys into it were
> left exactly as they were; the migration that made it a peer is purely additive. The contract —
> packet, binder, merge, engine — is pure JVM in `people/sync/` and unit-tested, including a full
> two-peer round.
>
> People also carries a **second, separate seam: partner sync**, which pairs two *different*
> households by QR code. Each person scans the other's code — one scan yields half a secret, and the
> token that gates the seam needs both, so the connection is two-way by construction and an install
> that was never scanned can address nobody. Once paired, **People → a person → View LifeOps** shows
> that partner's current week: their real LifeOps tasks, ticked as they tick them, mirrored into
> People's own tables and shown on their own screen. It is **never merged into your LifeOps** — not
> your week, not your aspects, not your capacity. Exactly one thing crosses into a planner, and only
> because somebody asked for it by name: a task you add *to their* week, which becomes a real task on
> theirs (and is taken once, for good). Rounds run on app open and on demand — People's roster has a
> **Partner sync** card that sets the seam up on a household that has never paired (identity,
> exchange folder, first envelope) and runs a round on request, and each person's page has the same
> button for the moment mid-handshake when you need to know whether they have scanned yet — so what a
> partner changed is kept and reported the next time you look. Sharing expires by itself — the seam
> only ever publishes the current week.

> **Health** (the household health tracker) is a peer module — see **[docs/HEALTH.md](docs/HEALTH.md)**.
> It keeps a **profile per person** and, against each of them, the temperatures and other readings
> taken, the symptoms they've got, the medicines they're on with the spacing and daily limits from
> the label, and the **illnesses** all of it hangs off — so "when was the last dose", "is this higher
> than the last one" and "which day did the fever start" are already answered rather than
> reconstructed at 3am — and an illness reads back two ways: a summary of how it went, and a
> **history** of everything that was done, hour by hour. Anything that wasn't recorded at the time can
> be added later, including a whole illness that has already been and gone; records are filed by *when
> they happened*, and the ones written up from memory say so.
>
> Its Meds tab is a **medicine cabinet**: the household's actual stock, what is
> expired or running low, and — looked up from **RxNorm and openFDA** — what each product is made of
> and what its label says, shown beside the dose rules you typed in rather than instead of them. It
> also **reminds you**, either at set times or when the next dose is due.
>
> Its Care tab covers **who pays for this and who do we take her to**. Insurance is stored as *the
> card*, not the policy — the insurer, each person's member number on the household's plan, the
> numbers on the back, and photographs of it saved when you attach them, so Health can produce **a
> card-sized PDF on demand** for a reception desk or a school form. There is deliberately no field for
> a copay or a deductible: those are an eighty-page contract, and a box to type one into is an
> invitation to plan around a number nobody checked. Doctors are kept **separately from the
> insurance** — the plan changes every January and the paediatrician doesn't — and Health can check
> each of them against the insurer's own **published provider directory** (the public FHIR endpoints
> payers publish under the CMS interoperability rule). Every check is kept rather than overwritten,
> which is what lets it say *"listed in March's directory, not in today's"* — a doctor who has left
> the network — as against *"never listed"*, and to say *"couldn't tell them apart"* rather than
> guessing between two people with the same surname.
>
>
> Health has **no household screen of its own**: no People tab, no add-a-person form, no
> remove-a-person button. The household belongs to the People app and Health is a peer on its sync
> seam — a second place to add or rename somebody would be a second answer to "who lives here". The
> profile bar switches between people on every tab, and its last chip opens People for anything else.
> What Health *does* own about a person — their usual temperature and their medical note, neither of
> which is ever published — lives on its **Information** tab, renamed from Illness because the
> question asked far more often than "is anyone ill right now" is **what does normal look like for
> this person**: a 37.6 means one thing for somebody who runs at 36.4 and another for somebody who
> runs at 37.1. That tab now reads as who they are, what their normal is, how temperatures are shown,
> and then the illnesses.
>
> Its Record tab holds **what is true about a person between illnesses**. Allergies and conditions are
> now rows rather than a sentence in a free-text note — which means an allergy can be listed, ordered
> by how badly it went last time, and **checked against a medicine as you add it**. That check matches
> what you wrote down against what the label says and nothing else: it will not tell you that
> penicillin and amoxicillin are relatives, because that is pharmacology and this app is no more
> qualified to do it than to read a dose off a label — and **no warning is never an all-clear**, which
> every surface says rather than showing a reassuring tick. Conditions are deliberately *not* illness
> episodes: an episode has an end, and asthma doesn't. The tab also holds the **vaccination record** —
> the card in the drawer, typed up, reported as what is *recorded* and never as "up to date", because
> Health ships no schedule and a schedule varies by country, birth year and risk group — and the
> **paperwork**: after-visit summaries, lab results, referral letters, school forms, stored exactly as
> they arrived and **never read**. Nothing is parsed out of a document; that is a separate feature and
> belongs to the change that owns it.
>
> The judgements it makes — whether a reading is a fever, given where it was taken and how old the
> person is; whether the next dose is due yet under both the interval and a rolling 24-hour allowance;
>  whether a bottle is out of date or out of doses; where a doctor stands against a plan, read out of
> every check ever made; whether a medicine matches something somebody is allergic to — live in
> `health/logic/` and are JVM-unit-tested. **No record ever leaves the
> device**: the drug lookup asks what a medicine *is*, never who takes it, and the directory check
> asks about a *doctor*, never about anybody in the household — no member number, no profile, ever.
> That is the line it holds. It records; it does not give medical advice.

> **Project** (the document and planning repository) is a peer module — see **[docs/PROJECT.md](docs/PROJECT.md)**.
> It owns *the work you are making*: a shelf of projects, and inside each one five ways of looking at
> the same thing — the **Outline** it is shaped by, the **Docs** it is written in, the **Lore** it has
> to stay consistent with, the **Timeline** it happens on, and the **Board** it gets built through.
> Notion's blocks, Reedsy's manuscript outline and a kanban board, in one place, because they are
> five views of one project rather than five apps.
>
> The outline's rows are *parts of the work* — they nest, they carry a status through drafting and
> revision, and they carry a **length**: words roll up from the leaves, so an act's number is the sum
> of its scenes and nobody maintains it. **Cut** is a status rather than a delete, because the scene
> you cut in August is the one you want back in October. Documents are **blocks**, and the
> interchange in both directions is **Markdown** — paste a chapter in, get it back out — with the
> word count taken over prose only, so a pasted config file cannot inflate it. Link a document to a
> scene and that scene's length becomes the document's, kept in step on every edit; the outline stops
> being a plan you maintain beside the writing and becomes a view of it.
>
> Lore links with `[[double brackets]]` and **only** with those — nothing is linked because a word
> appeared in a sentence — and it reports the two things a wiki knows and you don't: **backlinks**
> (where an entry is spoken of, collected without anyone recording them) and **broken links** (every
> name the project referred to and never wrote down, which is the list of pages worth writing next).
> An ambiguous name resolves to *nothing* rather than to whichever row came first. On the timeline,
> the author's order is the timeline and the "when" is a note about it — free text, read as a date,
> year, day or chapter where it can be — so the app can say **"you put the coronation before the
> battle and dated it after"**, which is the error a story timeline exists to catch and is only
> catchable because the two are kept apart. The board's **WIP limits warn and never refuse** (a board
> that rejects the card in your hand teaches you to lie to it), and deleting a column **keeps its
> cards**, stranded on purpose, with a banner to re-file them.
>
> Two things tie the sections into one app rather than five. **Compile** walks the whole outline,
> pulls in every document linked to it, and hands you the manuscript — and it reports the holes
> rather than hiding them: pieces with nothing written are listed by name, and documents belonging to
> no piece are counted even when excluded, because "12 documents are not in this export" is the
> sentence that saves you. **Search** covers all five sections at once, matching every term against
> the whole record rather than title-and-body separately (so "kestrel smuggler" finds the entry whose
> name is in one and description in the other), ranked title-before-body and fully deterministic.
>
> Project deliberately does **not** schedule anything — no dates on cards; deciding what today looks
> like is LifeOps' job — and it is **not on the sync spine**: nothing else in the suite writes into a
> project, so there is nothing to reconcile. The tree walks, the Markdown round trip, the wiki index,
> the timeline reading, the board moves, the compile and the search are pure JVM in `project/logic/`
> and covered by 102 unit tests. It requests no permissions and has no `INTERNET`; nothing it holds
> leaves the device.

> **Maintenance** (the asset and upkeep register) is a peer module — see **[docs/MAINTENANCE.md](docs/MAINTENANCE.md)**.
> It owns *the things you own*: the house, the cars, the furnace, the mower — what each one **is**,
> what is **owed** on it, what it **costs** to keep, and what it **next needs done**. The shape is
> borrowed from [Homi](https://github.com/Yuss9/homi) and then narrowed hard: no accounts, no roles,
> no invitations, no object store — none of that is purpose-built for one person on one device inside
> an app that already ships as a single install.
>
> Kind-specific fields are **declared as data, not as columns**: a vehicle asks for the whole of the
> paperwork (VIN, trim, body style, engine, fuel, transmission, drivetrain, colour, plate, where it is
> registered, tyre size and oil spec); a home asks for an address, a year built and a parcel number;
> an appliance asks for a serial number and where it lives. Adding a kind is authoring — one entry in
> `logic/AssetKind` grows its own fields, already validated, everywhere they are shown — and the add
> dialog and the edit dialog draw from that one list, so **you are asked for everything the moment you
> add the thing**, with nothing but the name required. A **meter belongs to a kind** too, so mileage
> intervals are only offered where there is an odometer to measure them against.
>
> Schedules carry **either or both** intervals — *"every 5,000 miles or 6 months, whichever comes
> first"*, the way an owner's manual writes it — and the app says which leg won. Two odometer readings
> give a **rate**, and a rate turns 5,000 miles into a date; one reading gives no date rather than a
> guessed one. A mileage interval with no baseline says *"log one service to start the clock"* instead
> of quietly granting itself a free 5,000 miles. **Logging the work is the only thing that moves a
> clock** — there is no silent reset, which is why the history has no holes in it.
>
> Costs are asked **sideways as well as down**: a third tab totals what the whole register cost over
> the last year or ever, ranks the things by what they ate, and says who has been paid — the question
> "who did the brakes last time" only has an answer across assets. What you have sold still counts as
> spend (history includes the truck you had until March) but not as worth or owed. And an asset's
> history **leaves as CSV** through the system file picker, because a service history is worth money
> on exactly one day and on that day a whole-suite backup is no use to the buyer.
>
> The **mortgage** is typed as the note reads (principal, rate, term, first payment) and everything
> else is derived: balance today, principal and interest paid, payoff month — or *never*, when the
> payment doesn't cover the interest — and equity, negative when it is. There is deliberately **no
> stored balance**, which is the field that makes every other app's mortgage page wrong within a
> month; rates are basis points and money is whole cents, so nothing drifts. **VINs are checked
> offline** by their own check digit, and a digit that disagrees is *reported, not rejected* — plenty
> of vehicles built outside North America carry a valid VIN that fails that arithmetic. Costs refuse
> to annualise a history shorter than a year.
>
> Upkeep goes **on the LifeOps week**. A plan publishes itself as a task dated the day it falls due
> — `Truck: Oil change`, 30 May — which LifeOps parks in its **Future Tasks** queue and wakes into the
> week that contains that date, so a service five months out lands in the right week five months out.
> **Tick it in LifeOps and the tick comes back**: the service is logged here, the clock restarts from
> the completion, and the next occurrence goes on the week. Never as a *recurring* LifeOps task —
> that would put two engines in charge of when the next oil change is — and never with a hard
> deadline, which would bin the job at week close. It is a per-plan switch, on by default. **Which
> aspect those tasks are filed under is LifeOps' call, not Maintenance's** — `Settings → Maintenance
> upkeep`, the same arrangement Citation's reading time has — read at publish, so changing it re-files
> what goes on the week from then on and leaves anything you moved by hand exactly where you put it.
>
> The seam is a **reconciliation, not an event handler**: LifeOps announces the tick as it happens
> (one small outbound bus in its connection layer), but the same round also runs when Maintenance
> comes to the foreground and after every edit, and reaches the same answer. So the awkward cases are
> ordinary — a task you deleted stays deleted until the plan moves on, one carried into a new week is
> *followed* rather than duplicated, one stranded in a closed week is put on this one, a job you had
> already written by hand is **adopted** rather than duplicated, and a plan you paused takes its task
> off the week. Maintenance raises **no notifications of its own**; deciding
> what today looks like stays LifeOps' job.
>
> A vehicle's **VIN opens three things**, and the app sends **eleven of its seventeen characters** to
> do it: the half that describes the model. The six dropped are the serial — the part on your title,
> the part a history service is keyed on — and they are dropped because they identify your vehicle
> *and* because NHTSA's decoder returns an identical answer without them, which was checked against
> the live API. The rule is a unit-tested function, not a habit. What comes back is **offered, never
> applied**: it fills in only the fields you left blank, and it **chooses a maintenance schedule**
> rather than fetching one — there is no public OEM API for service intervals, so a schedule is
> transcribed by hand from the manual and shipped as data (a Jeep Wrangler JL 3.6 Schedule A pack and
> a generic fallback), carrying its source and flagged provisional until somebody checks it. Applying
> one turns its items into ordinary plans you own; applying it again adds only what is new. The third
> thing is **safety recalls**, keyed by make/model/year with no VIN at all — NHTSA's *do not drive*
> and *do not park indoors* flags arrive as overdue, everything else as scheduled, because fourteen
> red lines on the day you add a used truck is a docket you stop reading. That answer is the only one
> here that **goes stale while the vehicle sits still** — campaigns open years after a car is built —
> so every vehicle schedule carries a standing six-monthly *check recalls*, and running the check is
> what ticks it off.
>
> Schedules understand **odometer milestones** as well as intervals — "spark plugs at 100,000 miles"
> is not "100,000 miles from now", which on a car bought at 60,000 is four years of being wrong — and
> milestones already behind you when a schedule is applied are taken as done, because nobody knows
> what the last owner did. A vehicle also gets a weekly **odometer prompt**, which is the one thing in
> the suite that completes a LifeOps task rather than reacting to one: a task can't carry a number, so
> typing the reading here ticks it off there. The recall check is the only other thing shaped like
> that, for the same reason — a task can't go and ask NHTSA anything either.
>
> Nothing derived is stored, so nothing goes stale in a drawer. Its logic lives in
> `maintenance/logic/` under **120 JVM unit tests**. It holds `INTERNET` for those two keyless
> government lookups and nothing else — the mortgage, the parcel number, the service history and the
> odometer have no code path to the network at all.

> **Logistics** (the pantry/inventory app) is a peer module — see **[docs/LOGISTICS.md](docs/LOGISTICS.md)**.
> It fills a virtual pantry from a Walmart order (PDF or pasted text), draws it down as you log the
> meals you cooked ("for *X* meal, here's what I used"), **builds a grocery list** from what's running
> low or a recipe's missing ingredients — and shelves it back into the pantry when you've shopped —
> and grabs recipes from links **or from screenshots of one** (on-device OCR; the picture is kept with
> the recipe), reusing LifeOps' food & recipe catalog rather than keeping its own. It also carries
> **the whole food-and-calorie side of LifeOps** — the day's diary, its planned-vs-confirmed totals,
> Confirm/Adjust, ad-hoc entries, custom foods and planning a recipe onto a day — as its own **Food**
> tab, writing through LifeOps' food service into LifeOps' diary rather than keeping a second one, so
> a bowl of chili logged in either app is one row. Cooking a known recipe can put its calories in the
> diary in the same tap that deducts it from the shelf. Its framework-free parsers live in
> `logistics/logic/` and are JVM-unit-tested.

---

## What it does

| Screen | Purpose |
|---|---|
| **This Week** | The cockpit. Create tasks, set priority/estimate/due date, log time (timer, Pomodoro, or manual), complete/skip/carry tasks, then **close the week**. |
| **Game** | An arcade wave-holdout run funded by the resources your real work earns. Spend Energy to enter, commit banked resources into your loadout, and fight a week-seeded run. See **[DESIGN.md](DESIGN.md)**. |
| **Resources** | RPG-style resource slots that fill as you complete work, mapped from your aspects. The economy behind the Game. |
| **Reports** | Trends: completion rate, aspect balance, time spent, scoring, operation health, priority breakdown, resource usage, carryover. |
| **Growth Record** | A permanent, per-week concentric-ring history. One ring per week; effort shows as colour; skipped weeks leave grey scars. |
| **Settings** | Aspects & categories, operations, cost resources, notifications, theme, and all backup/export actions. |

### Core concepts

- **Aspect** — a top-level area of life (e.g. *Body*, *Craft*, *Mind*). Has a name and colour.
- **Category** — a sub-grouping inside an aspect.
- **Operation** — a longer effort that groups related tasks within an aspect. (Called a
  *Project* until the suite grew a Project app of its own; the word now belongs to that app.)
- **Week** — Monday→Sunday. The current week is open; you **close** it manually, which
  snapshots it, carries forward what you chose to keep, and starts the next one.
- **Task** — a unit of work with a priority, optional estimate, due date, and logged time.
- **Commitment** — the handful of a week's tasks whose completion decides whether the week
  worked. Marked with a star, worth no extra points, and the one thing that lets the app say
  *"rest is earned"* rather than quote a percentage.
- **Resource value & scoring** — completing a task earns `resourceValue × accuracy`, where
  accuracy rewards estimating well (see the guide).

---

## The Growth Record (rings) — design note

Each closed week is drawn as **one concentric ring**, fixed thickness, accumulating
outward from a seed. Bands within a ring are the aspects you spent hours on; colour and
glow encode effort; a zero-hour week is a permanent grey **scar**.

**The one invariant:** *a ring, once drawn, never changes.* Adding week N+1 must not move,
resize, recolour, or restack any earlier ring. Everything is subordinate to that — thickness
is flat (the view zooms, the geometry never does), a ring's radius depends only on the weeks
before it, and colour/track map to a **stable aspect id**, never an array index. The aspect
list is append-only.

To keep history faithful even when an aspect is later **deleted, renamed, or recoloured**,
week-close seals a per-aspect `{minutes, name, colour}` blob into the week snapshot
(`aspectHistory`). Sealed history is immutable; the open week (and any pre-feature week) falls
back to live time-entry derivation. This matters because `tasks.aspectId` is `ON DELETE SET
NULL`, so a live join alone could not survive a deletion.

The render layer emits a **shape-agnostic primitive list** (crisp `fill` + `glow` bands), which
feeds both the on-screen Compose canvas and the **SVG** exporter — so vector export stays a
first-class feature.

---

## Tech stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- **Room** (SQLite) for persistence, **Gson** for backup/import
- **MVVM**: `Screen` (Compose) → `ViewModel` (`StateFlow`) → `Repository` → `Dao`
- **WorkManager** for reminders, **Glance** for the home-screen widget
- Min SDK 26 · Target/Compile SDK 35 · JDK 11

### Project layout

```
app/src/main/java/com/lifeops/app/
├── data/
│   ├── db/            Room database, entities, DAOs, migrations
│   ├── model/         Plain domain models (Models.kt, Weather.kt)
│   ├── repository/    Repositories (the only thing ViewModels talk to)
│   └── weather/       NWS (api.weather.gov) network client — the only thing that touches the net
├── ui/
│   ├── screens/       thisweek · resources · reports · growth · settings · operationdetail
│   ├── components/    Reusable composables (header, dialogs, task rows, …)
│   └── theme/         LifeOpsTheme — a one-line wrapper over the suite's theme (:suiteui)
├── util/              Pure logic: scoring, dates, growth rings/colour/export, CSV, weather
├── widget/            Glance app widget
└── worker/            WorkManager workers (reminders)
```

The **growth** logic lives in pure, Android-free modules so it is unit-testable on the JVM:
`util/GrowthRings.kt` (geometry/scene), `util/GrowthColor.kt` (hex/HSL + intensity),
`util/GrowthData.kt` (assemble live + sealed snapshots), `util/GrowthExport.kt` (CSV + SVG),
`util/Csv.kt` (shared CSV quoting).

---

## Weather foundation (Phase 1) — design note

LifeOps grows a **source-agnostic weather layer**. The rest of the app asks *"what are current
conditions?"* against plain models in `data/model/Weather.kt` (`WeatherReport`,
`CurrentConditions`, `ForecastPeriod`, `WeatherAlert`) and never learns where the numbers came
from. Today they come from the US National Weather Service (`api.weather.gov` — free, keyless,
US-only), but that lives entirely behind `WeatherRepository`.

**Cache-first, so opening LifeOps never waits on weather.** Reads stream straight from a local
Room cache (`weather_locations`, `weather_snapshots`, `weather_alerts`, migration 28→29); the
network is touched only by an explicit `WeatherRepository.refresh(...)`, which folds any failure
into a `Result` so a dropped connection just keeps the last cached report on screen. Each refresh
stores the current conditions as flat columns plus the full hourly/daily forecast as JSON blobs,
so an entire `WeatherReport` rebuilds from cache with zero network. The weather cache is
intentionally **left out of backup/restore** — it's regenerable.

The Android-free, JVM-testable pieces sit in `util/`: `WeatherMath.kt` (NWS heat-index /
wind-chill "feels like") and `NwsParser.kt` (raw api.weather.gov JSON → models). Only
`data/weather/NwsClient.kt` performs I/O (via `HttpURLConnection` — no new dependencies), which is
why the app gains the `INTERNET` permission for the first time.

**Phase 2 — awareness.** `worker/WeatherRefreshWorker.kt` keeps the cache warm in the background:
a ~2-hour periodic WorkManager job (network-constrained, `KEEP` so relaunches don't reset it,
scheduled from `LifeOpsApp`) that refreshes every location, prunes expired alerts, and — while any
alert is active — chains a shorter one-time follow-up for a denser cadence. `util/OutdoorScore.kt`
is the rules-based scorer: a pure 0–100 *discomfort* total (0–25 Excellent … 76+ Avoid) built from
feels-like, humidity, UV, wind, rain probability and storm risk, returning human-readable
positives/warnings.

**Phase 3 — LifeOps integration.** Tasks gain optional weather constraints via a
`task_weather_requirements` 1:1 side table (outdoor-preferred, duration, max/min temp, avoid-rain,
max wind) — kept off the core `tasks` schema so the create/edit pipeline is untouched.
`util/BestTime.kt` is the pure recommendation engine: it scores each forecast window with
OutdoorScore, disqualifies windows that break the task's hard limits or a household member's
comfort ceilings (and optional calendar busy-labels), and ranks the rest best-first with a friendly
match-%. `util/WeatherCards.kt` assembles the dynamic cards (severe-weather **Warning** → **Morning**
conditions → per-task **recommendation**). It all surfaces on a self-contained **Weather** screen
(Planning hub → Weather): add a location, see current conditions + outdoor rating + alert cards, set
per-task weather needs, and get best-time suggestions for the week's outdoor tasks. Requirements are
included in backup/restore (v9).

**Phase 4 — saved activities.** The **Activities** screen (Planning hub → Activities) is a library
of reusable weather profiles (`activity_templates`, migration 31→32). Eight built-ins (Mowing,
Gardening, Car Washing, …) are seeded once on first launch, but every template — built-in or not —
is a fully editable/deletable row, and users can **build their own from scratch**. Applying one from
the Weather screen's requirement editor stamps its defaults onto the task's weather requirement.
Templates are in backup/restore (v10). The other half of Phase 4 — household profiles — already
shipped as the People feature, which the best-time engine consumes.

**Phase 5 — advanced.** `util/SevereWeatherIntel.kt` turns alerts + the hourly forecast into
actionable advisories ("Storm approaching · clearing by 4 PM", with a *Delay ~90 min* hint),
surfaced as a new `WeatherCard.Advisory`. Radar is deliberately on-demand only (the roadmap's "avoid
a constant feature"): a **View radar** action resolves the nearest NWS station (`/points`
`radarStation`) and opens the official radar in the browser — no image library, network only when
asked. `util/PreferenceLearning.kt` closes the loop: when you seed a task from an activity then
change a limit, the delta is logged (`activity_overrides`, migration 32→33); once a field trends the
same way enough times, the **Activities** screen suggests updating that activity's default ("You keep
mowing above the recommended temperature — raise the threshold?") with one-tap Apply/Dismiss. All in
backup/restore (v11).

**Phase 6 — where you actually are.** A location stops being a latitude you type in.
`data/weather/DeviceLocationProvider.kt` asks the device where it is — platform `LocationManager`
only (no Play Services), `ACCESS_COARSE_LOCATION` only (a forecast cell is ~2.5 km square, so fine
location would buy nothing), cheapest-first: a recent last-known fix, else one bounded active
request, else the stale fix rather than nothing. `WeatherRepository.setDeviceLocation(...)` keeps
**one reserved row** pointed there instead of accumulating a location per fix; it sorts ahead of
hand-added places, making it the weather screen's default and the "primary" that stamps counter
ticks. A fix within 2 km of the stored one is treated as no movement and changes nothing, so a phone
on a table can't discard a good forecast; past that the row moves and its now-wrong snapshots and
alerts are dropped. Every failure is a value (`Fix.PermissionMissing` / `LocationDisabled` /
`Unavailable`), never an exception, so each one can be phrased for the user. This is what the
**Operations Sandbox home screen's weather tile** is built on (see
[docs/OPERATIONS_SANDBOX.md](docs/OPERATIONS_SANDBOX.md)): current conditions, today's high/low, and
an alert strip, read from the same cache LifeOps uses — cache-first, refreshed only past 45 minutes,
tapping through to the full Weather screen. `util/TodayOutlook.kt` is the pure piece that turns NWS's
alternating day/night halves into "the rest of today"; `util/Geo.kt` is the pure distance check
behind the move threshold.

---

## People (Planning) — design note

The **People** page (Planning hub → People) holds household profiles: a name, weather-comfort
preferences (max/min feels-like, UV / wind / rain ceilings, sun sensitivity), a freeform activity
note, and a timeline of notes. Every preference is nullable — "no opinion" never rules a time slot
out. These are the **Phase 4 household profiles** the weather roadmap's outdoor scoring consumes.

Tasks are marked as *involving* people through a `task_people` many-to-many join (both sides
cascade). Involvement is managed from a person's detail screen, which lists the tasks that involve
them and attaches more from the current week — so the task-creation flow stays untouched. Tables
(`persons`, `person_notes`, `task_people`, migration 29→30) are additive and, unlike the weather
cache, **included in backup/restore** (backup v8) since profiles are real user data.

---

## Milestones (History) — design note

The **Milestones** page (History hub → Milestones) records the rare, once-in-a-lifetime
accomplishments that don't fit the weekly task rhythm. A milestone is a title, an optional
description, a point value, an *achieved on* date, and an optional attachment to an **aspect**
and/or a **person** (nullable FKs with `ON DELETE SET NULL`, the same shape as `tasks.operationId`,
so removing an aspect or person leaves the milestone standing with its link cleared).

Milestones are the **deliberate exception to "only closed weeks emit resources."** Because they are
logged after the fact for something already accomplished, their points are **granted immediately**
rather than at week-close: on creation, an aspect-attached milestone mints its points into that
aspect's mapped game resources through the *exact same path* week-close uses
(`GameResourceMappingDao.getByAspect` scaled by each mapping's weight → `GameResourceDao.addValue`),
and records a `milestone` **resource transaction** so the grant is visible in the Resources ledger.
A milestone with no aspect (or an aspect with no resource mappings) simply keeps its point value as
part of the record. Like every mint in LifeOps the grant is **permanent** — deleting a milestone
removes the record but never claws back already-granted points. The `milestones` table (migration
44→45) is additive and **included in backup/restore** (backup v15); restore upserts the rows
without re-running the grant, so restoring never double-mints.

---

## Week-close review — design note

Closing the week is LifeOps' one **mint** — the moment work becomes resources and a ring is sealed —
so the close dialog is a **review**, not a rubber stamp. Before you confirm, it holds the week you're
about to close against the trailing sealed history and shows: headline metrics with honest deltas
(completion vs last week in percentage points, time logged vs the trailing average, hard-deadline hit
rate), an **aspect-balance** read that surfaces *grey scars* (aspects gone two-plus weeks with zero
minutes — the same neglect the Growth Record marks), and a short set of **earned observations** —
the sardonic honest-mirror lines the app promises, each fired only when the numbers justify it
("Body has been a grey scar 3 weeks running", "You keep underestimating — most tasks ran over").
Pick a self-rating and, if it disagrees with the board, the mirror says so ("You rated this an 8; the
board says 40% done").

The whole retrospective is pure, Android-free logic in `util/WeekReview.kt` (`WeekReviewBuilder` +
`ClosingWeekStats` → `WeekReview`), unit-tested on the JVM like `GrowthRings` / `BestTime` /
`ScoringUtils`. The estimate window matches `ScoringUtils` (±15 min), and historical time comes from
each snapshot's sealed `aspectHistory`, so the review reads the same faithful record the rings do. It
is **read-only** — nothing new is persisted; the existing self-rating/note still seal into the
snapshot, and the close path (mint next week, snapshot/settle, seed recurring) is unchanged.

---

## The week's bar — design note

LifeOps' completion rate answers *how much of the list moved*. It cannot answer *am I done* —
a percentage has no idea which of the tasks mattered — and "am I done" is the question the whole
week-shaped rhythm exists to answer. So a task can be marked as one of the week's **commitments**
(`tasks.isCommitment`, migration 52→53): the few whose completion decides whether the week worked.
The header reads `Bar: 3/5`, and on a clean pass says so outright.

**The flag is deliberately outside the economy.** Marking a task essential mints nothing extra and
changes no resource value. Every scoring path — `ImportParser.computeResourceValue`, the
aspect→resource mapping, the week-close mint — is untouched, and `TaskRepository.setCommitment` is
a bare column write rather than an upsert so it cannot disturb status, scoring or a reminder. If
the star paid out, every task would end up wearing one and the bar would stop selecting anything.
It moves exactly one thing: what the week reads back as.

The counts are **sealed into the snapshot** (`week_snapshots.commitmentTotal` /
`commitmentCompleted`) on the same principle as `aspectHistory`: the flag stays editable, and
un-ticking a commitment next month must not rewrite whether last month's week was met. Weeks
closed before the feature seal `0/0`, which reads as *"no bar was set"* — never as a missed one,
which is why `WeekReview.commitmentMet` is a nullable `Boolean` rather than a `false`.

Propagation is two opposite calls, both made explicitly rather than inherited from a `copy`:
a **carried** task keeps its star (something you called essential and didn't do has not stopped
being essential because the week ended), while a **recurring** seed clears it (marking one
instance essential says something about *that* week; inheriting it would silently re-mark the same
chores forever until the bar covered the list). The mirror polices the same failure directly —
past 60% of a week's tasks it says *"That's not a bar, that's the list."*

At close, **Commitment** leads the Week in Review with a bar-to-bar delta (never against a
completion rate, and never against a week that set no bar). A cleared bar is reported as the
week's *result*, above the observations, rather than as one of them — it must not lose a slot to
the three-observation cap — while a missed one is stated among the sharp lines, where it also
suppresses the dry "completion climbed" nod: congratulating a climb while essential work sat
undone is the mirror flattering.

---

## Week capacity — design note

The honest mirror runs at close, which is the moment it can change nothing. A week you
over-committed on Monday is a week you cannot rest at the end of — and the app already holds every
number needed to have said so on Monday: the estimates you typed, and the sealed weeks behind you.

`util/WeekCapacity.kt` is the plan-time counterpart to `util/WeekReview.kt` — pure, Android-free
and JVM-unit-tested like `GrowthRings` / `BestTime` / `ScoringUtils`. It sums the week's estimates
and holds them against the **median** logged minutes of the trailing eight sealed weeks, banding
the ratio into `ROOM` / `REALISTIC` / `STRETCHED` / `OVERCOMMITTED`.

**Median, not mean** — the same window `WeekReviewBuilder` compares against, read differently on
purpose: a mean lets one 40-hour crunch week raise the very bar it is supposed to be measured
against, quietly licensing the next one.

It measures the **whole** plan, pending and completed alike, rather than what's left. Measuring
the remainder against a full week's baseline would relax as the week ran down and read
"realistic" on Friday for work that now has a day to happen in — and that is week-pacing, a
different feature. The question here is whether the week you signed up for was ever a week's
worth, and that answer shouldn't change because you've done some of it.

It **never blocks and never re-plans**, and it stays silent unless it has earned the right to
speak: under three sealed weeks with logged time there is no baseline, and a guess dressed as a
baseline is worse than nothing (`NO_BASELINE`, headline `null`). When some tasks carry no
estimate, the headline says the total is a **floor** — a partial number presented as the whole
plan is the same over-commitment wearing a badge.

---

## Reading rewards — design note

Reading (in the **Citation** companion app) is the one activity rewarded *by time* rather than by
task completion, so the time has to be honest: Citation measures **engaged** minutes only — a page
left open past a short idle timeout stops the clock (see `docs/CITATION.md`) — and reports them to
LifeOps. Those minutes earn resource points at a flat rate (**Settings → Reading rewards**: pick the
aspect they earn into, default rate 5 pts/hour). Both reading categories — *Learning* (O'Reilly,
owned books) and *Fun* (Royal Road) — fold into the one chosen aspect; the category split is a report
dimension, not a second economy.

The reward is minted the normal way: at week-close, engaged reading minutes logged in the week window
become points (`util/ReadingRewards`, pure + unit-tested) and are added to the chosen aspect's
earnings, which then flow through the existing **aspect → resource** mapping. So reading obeys every
economy invariant — *only closed weeks emit*, and it mints an aspect's own resource rather than
converting between resources (**non-fungibility** holds; reading is genuine effort, not a purchase).
The rate and aspect are user settings; reading rewards are **off until an aspect is chosen**.

Reading is **cumulative over the week, never a single-session gate**: every session's minutes are
*summed first* over the week window (`BookDao.sumReadingMinutesBetween`) and turned into points
*once* — so six ten-minute sittings earn exactly what one unbroken hour does, and no per-session
remainder is floored away. To make that visible before the mint, the pre-close **Week in Review**
shows a *Reading* line with the points the open week has already banked
(`TaskRepository.expectedReadingReward`), computed from the same code the close uses.

---

## Build & run

The project targets the standard Android toolchain.

**Android Studio (recommended):** open the project root; let it sync; **Debug ▶** the default
`app` configuration on a device/emulator (API 26+) — `:app` is the **Operations Sandbox** container
(the only runnable app), and LifeOps and Citation open from its home screen.

**Command line:** you need an Android SDK. Point the build at it via a `local.properties`
with `sdk.dir=/path/to/Android/Sdk` (the checked-in value is a placeholder), or set
`ANDROID_HOME`. Then:

```bash
gradle :app:assembleDebug        # build the Operations Sandbox container APK
gradle :lifeops:testDebugUnitTest # run LifeOps' JVM unit tests
gradle :backupkit:test           # run the backup format/engine tests (pure JVM, no SDK needed)
gradle :suitekit:test            # run the suite appearance tests (pure JVM, no SDK needed)
gradle :maintenance:test         # run Maintenance's logic tests (VIN, due dates, amortisation)
```

> Note: the Gradle wrapper jar/scripts are not committed, so use a locally installed
> `gradle` (8.7+) or Android Studio's bundled Gradle. Running `gradle wrapper` once will
> generate `./gradlew` if you want it.

---

## Tests

JVM unit tests live in `app/src/test/`. Notable suites:

- `ScoringUtilsTest` — accuracy multiplier.
- `GrowthRingsTest` — band proportions, colour mapping (h = 0/25/50/90), glow bounds,
  **the immutability invariant**, CSV round-trip, SVG export.
- `GrowthDataTest` — sealed-vs-live source selection, stable aspect ordering, and
  `deleteAspect_preservesHistoricalSnapshots`.
- `CsvTest` — CSV quoting/round-trip.
- `WeekCapacityTest` — median baseline (one crunch week can't raise the bar), the trailing
  window, the minimum-history silence, verdict bands, and the unestimated-tasks "floor" caveat.
- `WeekReviewTest` — commitment metric/verdict, bar-to-bar deltas, no delta against a week that
  set no bar, the over-marking call-out and its small-week floor, plus the existing headline,
  grey-scar, estimate-bias and observation-cap coverage.
- `WeatherMathTest` — heat-index / wind-chill "feels like", including the humidity/wind extremes.
- `NwsParserTest` — api.weather.gov `/points`, forecast, and alert parsing (wind-text → mph,
  nested unit-values, graceful empty/malformed payloads).
- `OutdoorScoreTest` — rules-based OutdoorScore band thresholds, storm-risk override, alert
  folding, and the 0–100 clamp.
- `BestTimeTest` — "best time" ranking: task max-temp / avoid-rain limits, per-person heat
  ceilings, calendar busy-labels, and match-% for a pleasant window.
- `WeatherCardsTest` — dynamic-card ordering (severe warning → morning → task) and summaries.
- `ActivityTemplateTest` — saved-activity → task-requirement projection and entity round-trip.
- `SevereWeatherIntelTest` — alert-expiry / approaching-storm delays, quiet-forecast no-op,
  distant-storm horizon, and delay-hint formatting.
- `PreferenceLearningTest` — override-trend suggestions, min-observations gate, and the
  no-change-when-median-equals-default guard.
- `TodayOutlookTest` — "rest of today" from NWS's day/night halves: high/low taken from each
  period's own daytime flag (so an evening open reads tonight's low and tomorrow's high), worst
  precipitation across the window, and a missing half reported rather than guessed.
- `GeoTest` — great-circle distance behind the device-location move threshold: known city pair,
  symmetry, a short hop staying under the threshold, and the antipodal arcsine guard.
- `PersonMapperTest` — Person ↔ entity round-trip and SunSensitivity fallback.

The hosted apps keep their own JVM suites beside their logic — `maintenance/src/test/` covers the
VIN check digit and its thirty-year model-year cycle, whichever-comes-first service intervals, a
meter that went backwards, textbook amortisation to the cent, the refusal to annualise a history
shorter than a year, and the LifeOps seam driven end to end against a fake week planner: publish,
tick, log, republish; a deleted task that stays deleted; a carried-forward one that is followed
rather than duplicated (`gradle :maintenance:test`). LifeOps' half of that seam has
`TaskCompletionBusTest` — a listener that throws cannot break a tick.

---

## Data & privacy

LifeOps is **local-only** — there is no backend and nothing leaves your device unless you
explicitly export or share it. Back up regularly from **Settings → Data**:

- **Backup JSON** — the complete, restorable snapshot of everything.
- **Export Tasks CSV** — a flat, spreadsheet-friendly view of every task.
- **Rings CSV / SVG** — the Growth Record as a `week × aspect` hours grid, or a vector image.

Exports are written as plain text (no compression). The JSON is the only format that can
be restored.

**The one thing that goes out.** Weather is fetched from the US National Weather Service
(`api.weather.gov`) — no key, no account, and nothing sent but the coordinates a forecast needs.
When you let the Operations Sandbox's weather tile use your location, those coordinates are your
approximate position (`ACCESS_COARSE_LOCATION`, so already fuzzed by the OS) rather than a place you
typed in. The fix itself is never stored anywhere but the local weather cache, and declining leaves
every other part of the suite untouched — you can still add a location by hand on the Weather
screen.
