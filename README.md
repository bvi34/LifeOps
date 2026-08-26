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
> **phone-style home screen**: a tile per app in that app's own icon and colour, over a dock holding
> the gear and the backups. One launcher that opens LifeOps (`:lifeops`, the standard app), Citation
> (`:citation`), Logistics (`:logistics`), Advisor (`:advisor`), Health (`:health`), or People
> (`:people`); one place to back the whole suite up into a single `.zip` and restore from it; and one
> place that decides what all six of them **look** like — a shared preset and light/dark mode, plus
> an accent per app, applied by every hosted screen, and a **wallpaper** for its own home screen
> (a shipped design, your own gradient, or the suite's colours). LifeOps, Citation, Logistics, Advisor, Health
> and People are library modules hosted in that one process —
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
> It owns *who*: the roster, how to reach someone, the dates that come round, and the notes you keep
> about them — and it keeps **LifeOps in step over a two-way sync seam**, the same mailbox spine
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
> also **reminds you**, either at set times or when the next dose is due. The judgements it makes —
> whether a reading is a fever, given where it was taken and how old the person is; whether the next
> dose is due yet under both the interval and a rolling 24-hour allowance; whether a bottle is out of
> date or out of doses — live in `health/logic/` and are JVM-unit-tested. **No record ever leaves the
> device**: the drug lookup asks what a medicine *is*, never who takes it, and that is the line it
> holds. It records; it does not give medical advice.

> **Logistics** (the pantry/inventory app) is a peer module — see **[docs/LOGISTICS.md](docs/LOGISTICS.md)**.
> It fills a virtual pantry from a Walmart order (PDF or pasted text), draws it down as you log the
> meals you cooked ("for *X* meal, here's what I used"), **builds a grocery list** from what's running
> low or a recipe's missing ingredients — and shelves it back into the pantry when you've shopped —
> and grabs recipes from links, reusing LifeOps' food & recipe catalog rather than keeping its own.
> Its framework-free parsers live in `logistics/logic/` and are JVM-unit-tested.

---

## What it does

| Screen | Purpose |
|---|---|
| **This Week** | The cockpit. Create tasks, set priority/estimate/due date, log time (timer, Pomodoro, or manual), complete/skip/carry tasks, then **close the week**. |
| **Game** | An arcade wave-holdout run funded by the resources your real work earns. Spend Energy to enter, commit banked resources into your loadout, and fight a week-seeded run. See **[DESIGN.md](DESIGN.md)**. |
| **Resources** | RPG-style resource slots that fill as you complete work, mapped from your aspects. The economy behind the Game. |
| **Reports** | Trends: completion rate, aspect balance, time spent, scoring, project health, priority breakdown, resource usage, carryover. |
| **Growth Record** | A permanent, per-week concentric-ring history. One ring per week; effort shows as colour; skipped weeks leave grey scars. |
| **Settings** | Aspects & categories, projects, cost resources, notifications, theme, and all backup/export actions. |

### Core concepts

- **Aspect** — a top-level area of life (e.g. *Body*, *Craft*, *Mind*). Has a name and colour.
- **Category** — a sub-grouping inside an aspect.
- **Project** — a longer effort that groups related tasks within an aspect.
- **Week** — Monday→Sunday. The current week is open; you **close** it manually, which
  snapshots it, carries forward what you chose to keep, and starts the next one.
- **Task** — a unit of work with a priority, optional estimate, due date, and logged time.
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
│   ├── screens/       thisweek · resources · reports · growth · settings · projectdetail
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
and/or a **person** (nullable FKs with `ON DELETE SET NULL`, the same shape as `tasks.projectId`,
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
- `PersonMapperTest` — Person ↔ entity round-trip and SunSensitivity fallback.

---

## Data & privacy

LifeOps is **local-only** — there is no backend and nothing leaves your device unless you
explicitly export or share it. Back up regularly from **Settings → Data**:

- **Backup JSON** — the complete, restorable snapshot of everything.
- **Export Tasks CSV** — a flat, spreadsheet-friendly view of every task.
- **Rings CSV / SVG** — the Growth Record as a `week × aspect` hours grid, or a vector image.

Exports are written as plain text (no compression). The JSON is the only format that can
be restored.
