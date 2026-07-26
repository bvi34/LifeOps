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
│   └── theme/         Colours, theming, parseColor
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

## Build & run

The project targets the standard Android toolchain.

**Android Studio (recommended):** open the project root; let it sync; run the `app`
configuration on a device/emulator (API 26+).

**Command line:** you need an Android SDK. Point the build at it via a `local.properties`
with `sdk.dir=/path/to/Android/Sdk` (the checked-in value is a placeholder), or set
`ANDROID_HOME`. Then:

```bash
gradle :app:assembleDebug        # build the debug APK
gradle :app:testDebugUnitTest    # run JVM unit tests
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
