# Operations Sandbox

Operations Sandbox is the **container** the whole suite ships inside — think a lightweight
Docker-style host crossed with a single sign-on hub. It is the one installed app and the one
launcher icon. Opening it gives you a **phone home screen**: a tile per app, each with its own icon
and colour, over a dock holding the gear and the backups. From there it:

- opens any of the apps we build (**LifeOps** — the standard app — **Citation**, **Logistics**,
  **Advisor**, **Health**, **People**, **Project**, and **Maintenance**),
- **paints all eight of them**: one preset, one light/dark mode, and one accent per app, chosen in the
  gear and obeyed everywhere,
- **wears a wallpaper of your choosing** on its own home screen — a shipped design, a gradient you
  mixed, or (the default) the suite's own colours, and
- backs the **whole suite up into a single `.zip`** and **restores from that same zip**.

The GUI, the look and the backups are unified: one hub, one theme, one archive.

---

## Why a container (and what changed)

LifeOps and Citation used to be two separately-installed apps. They are now **library modules**
hosted inside one application: **`:app`** (applicationId `com.operations.sandbox`), which is the
Operations Sandbox container and the default Android Studio run target. One process, one private
storage area. That single fact is what makes an honest cross-app "back up everything, then restore
it" possible **without any inter-process plumbing** — the container can read both apps' databases
and files directly. `:app` is deliberately the central hub: future suite apps plug into it.

What this cost, module by module (LifeOps moved out of `:app` into `:lifeops` so the container can
own the `:app` name):

| Module | Directory | Before | After |
|---|---|---|---|
| `:app` | `app/` | LifeOps' code | **the container**: `com.android.application`, the launcher, home GUI, backup center, the single `Application` |
| `:lifeops` | `lifeops/` | — (was `:app`) | LifeOps as `com.android.library`; no launcher; `LifeOpsApp` is a runtime holder |
| `:citation` | `citation/` | `com.android.application`, own launcher + `Application` | `com.android.library`; no launcher; `CitationApplication` is a runtime holder |
| `:backupkit` | `backupkit/` | — | **new** pure-JVM library: the archive format + engine, unit-tested |
| `:core` | `core/` | Citation's JVM spine | unchanged |

### The single Application

There can be only one `Application`. `SandboxApplication.onCreate` installs both runtimes:

```kotlin
LifeOpsApp.install(this)        // LifeOps' repos + heavy startup (week rollover, reminders, …)
CitationApplication.install(this) // Citation's repository (async) + periodic RR/sync jobs
```

`LifeOpsApp` and `CitationApplication` kept their class names but are no longer `Application`
subclasses — they are plain holders reached through a process-global accessor (`get`/`getOrNull`).
Activities call `LifeOpsApp.get(this)`; background entry points (workers, receivers, the widget)
call `getOrNull()` so they still degrade gracefully if they fire before wiring.

### Resource & manifest merge

Merging two feature libraries into one app means their resources merge too. Collisions were handled
deliberately:

- `app_name`, the launcher icons (`ic_launcher*`, `ic_launcher_foreground`) — the `:app`
  application module defines its own, which **override** the library duplicates (app-module wins),
  so there is no duplicate-resource error and the launcher identity is unambiguously the container.
- `file_paths.xml` — renamed per module (`lifeops_file_paths` / `citation_file_paths`) because a
  FileProvider must get *its own* paths, not a merged one.
- FileProvider authorities — namespaced to `${applicationId}.lifeops.fileprovider` and
  `${applicationId}.citation.fileprovider` so the two providers don't collide under one applicationId.

### Who answers for a file type

The modules' intent filters merge into one app, so two activities advertising the same MIME type
would put the *same* app on the chooser twice. One module owns each type outright:

| Gesture | Type | Lands in | Why |
|---|---|---|---|
| **Open** (`ACTION_VIEW`) | `application/pdf`, `application/epub+zip`, `application/epub` | **Citation** | Reading is Citation's whole job. Tapping a book — in either of its formats — opens the reader, which imports the file and picks up on the page you left. |
| **Share** (`ACTION_SEND`) | `application/pdf` | **Logistics** | A Walmart order PDF is a *grocery* document, not something to read. Sharing it is the deliberate gesture, so it doesn't have to compete with every book tap. |
| **Share** (`ACTION_SEND`) | `text/plain` | LifeOps (JSON import), Logistics (recipe link / order text), Citation (a passage) | Ambiguous by nature — a chooser here is honest, so all three offer themselves. |
| **Share** (`ACTION_SEND` / `SEND_MULTIPLE`) | `image/*` | **Logistics** | A picture shared *into* the suite is a screenshot of a recipe — Logistics reads it on-device and offers the parse. No other module wants images, so it owns the type outright. |
| **Select text** (`PROCESS_TEXT`) | `text/plain` | Citation | The capture ladder — see `CITATION.md`. |

Citation sniffs the opened file's **magic number** (`%PDF`, `PK`) rather than trusting the intent's
MIME type, which senders get wrong routinely (an EPUB commonly arrives as `application/octet-stream`).

---

## The backup archive (`:backupkit`)

`:backupkit` is a **pure JVM** module (same discipline as `:core`): no Android types, so the whole
archive mechanism is unit-testable without an emulator.

### Layout

```
manifest.json            ← table of contents (written last, read first)
lifeops/data.json        ← one directory per app, keyed by AppId.key
citation/citation.db
citation/sovereign/…
```

`manifest.json` (see `BackupManifest`) records when the archive was taken, the sandbox version, and
per-app: display name, the app's own data version, and the entry list. **Restore walks the
manifest, not the raw zip entries**, so a truncated archive can't be half-applied, and an app the
current sandbox doesn't know about is skipped rather than failing the whole restore.

### Contract

Each hosted app implements `BackupContributor` (framework-free — it only ever sees streams and
relative entry names):

```kotlin
interface BackupContributor {
    val appId: AppId
    val dataVersion: Int
    fun backup(sink: BackupSink)      // open one entry per file, write, close
    fun restore(source: BackupSource) // read back the entries it wrote
}
```

`BackupEngine.backup/restore` (pure) turns contributors into a zip and back:

- **backup** streams each contributor's entries under `<appId>/…`, recording names, then writes the
  manifest last.
- **restore** extracts to a scratch dir (so large owned files never sit in memory), guards against
  **zip-slip** (an entry that escapes the work dir is rejected), then hands each contributor a source
  over its own directory.

### What each app contributes

Both apps back up **whole-file**, so a "full backup" is complete *by construction* — every table,
no per-entity allow-list to fall out of date.

- **LifeOps** — WAL-checkpoints and copies the entire `lifeops.db` (all 45 tables: tasks, aspects,
  weeks, **game resources + their ledger + mappings**, **counters + events**, runbooks, templates,
  subtasks, food log, growth snapshots, wellness, game scores/unlocks, weather locations, …), plus
  LifeOps' own `shared_prefs/lifeops_*.xml` (theme, reminders, reading rewards, onboarding, custom
  palette). This replaced an earlier per-table JSON snapshot that silently omitted resources,
  counters, runbooks, templates, food log, and more. The in-app JSON export (Settings → Backup)
  still exists separately.
- **Citation** — WAL-checkpoints and copies `citation.db`, plus every owned file under
  `filesDir/sovereign` (imported EPUB/PDF and the sync envelope store). The disposable Royal Road
  cache is intentionally **not** backed up (it's refetchable by design). The O'Reilly card/PIN lives
  in Keystore-bound `EncryptedSharedPreferences`, which can't be portably restored (the Keystore key
  doesn't survive a reinstall), so it is deliberately left out — it's a re-enterable credential.
- **Project** — WAL-checkpoints and copies `project.db` (the shelf, and every project's outline,
  documents and their blocks, lore, timeline and board), plus `shared_prefs/project_*.xml`. The
  database is copied as bytes rather than re-serialised, and that matters more here than elsewhere: a
  project's documents may be the only copy of that writing anywhere — there is no cloud workspace
  holding a second one — so the backup has to be the file, not a re-export a future schema change
  could quietly narrow.

- **Maintenance** — WAL-checkpoints and copies `maintenance.db` (the assets, their kind-specific
  attributes, the upkeep schedules, the service log, the meter readings, the loans, the coverages,
  and the safety recalls with whether this household has dealt with each), plus
  `shared_prefs/maintenance_*.xml`. Nothing in it is derived-and-stored — balances,
  due dates and costs are all computed on read — so a restored file cannot come back internally
  inconsistent; what it does hold is a VIN off a door jamb and a parcel number off a tax bill, which
  is exactly the kind of thing nobody can reconstruct from memory.

Because the apps share one process/package, `shared_prefs/` holds everyone's prefs together, so
each contributor scopes strictly to its own files by name.

**Restore requires a restart.** Swapping database files closes the live Room handle for the lifetime
of the process, so after a restore the sandbox tells you to **fully close Operations Sandbox and
reopen it** — reopening just the hosted screen would reuse the now-closed database.

---

## The GUI (`:app`)

The sandbox opens on a **phone-style home screen**, because that is the honest picture of what it
is: seven apps behind one icon.

- A **tile per app**, laid out three to a row, each carrying that app's own mark and colour — so
  "the thermometer" and "the green basket" mean something before you have read a word. The mark is
  drawn straight onto the wallpaper, with no block behind it. Tapping a tile
  launches that app's (now non-launcher) `MainActivity` in the same process; **pressing and holding**
  jumps straight to where that app's colour is chosen. The marks are the suite's own — see *Seven
  marks* below.
- A **clock strip** above the grid and a **dock** below it. The dock holds what belongs to the
  container rather than to any app: **Settings** (the gear) and **Backups**.
- A **weather tile** between the clock and the grid — see *Weather on the home screen* below.
- A **wallpaper** behind the lot — see *The launcher's wallpaper* below. Out of the box it is mixed
  from the suite's own colours, so the home screen is already wearing the chosen preset before an
  app is opened; a user who wants something else picks it in the gear.

`SandboxActivity` is a two-route shell (`when` over a route, not a navigation graph). The settings
screen's state — which tab, which app is being recoloured — is hoisted into it, and so is the
`BackupController`: an archive can take a while, and backing out to the home screen mid-backup must
not cancel it. The `WeatherWidgetController` is hoisted for the same reason — a location fix or a
forecast fetch must survive a trip to the gear.

### Weather on the home screen

The clock earns its place on this screen by being true without being asked. Weather is the only
other fact like that — *is it raining, and will it be?* gets asked more often than any tile here
gets tapped — so it sits directly beneath the clock as one glance-deep card: the current
temperature, what the sky is doing, the day's high and low, and a line of place / chance of
precipitation / wind. An active NWS watch or warning adds a strip across the top, tinted red at
Severe and above. Tapping it opens LifeOps' full **Weather** screen, which is where the hourly
strip, radar and outdoor-task windows live.

Two decisions are worth naming.

**It owns no weather data.** LifeOps already has the whole stack — the NWS client, the offline-first
Room cache, the 2-hour refresh worker — so `WeatherWidgetController` borrows `WeatherRepository`
rather than growing a second one. The reading on the home screen is the same reading LifeOps' weather
screen shows and the same one that stamps a counter tick. It reads from the cache, so the tile paints
on the first frame and a phone in airplane mode shows this morning's forecast instead of an error;
the network is touched only once that cache passes 45 minutes old, and a failed touch becomes a quiet
line under a real reading rather than replacing it. Refreshing again is cheap and idempotent, so the
tile re-checks on every return to the home screen.

**What it adds is the choice of place.** Before this, a location was a latitude and a longitude you
typed into LifeOps. The sandbox asks the device instead (`data/weather/DeviceLocationProvider.kt`)
and keeps one reserved row — `WeatherRepository.DEVICE_LOCATION_ID` — pointed wherever the phone is;
it sorts ahead of every hand-added place, which makes it the weather screen's default and the
"primary" whose conditions stamp a counter tick, on the principle that where you are outranks a place
you once typed in. A fix within 2 km of the stored one is treated as no movement at all and the row
is left untouched, so the ordinary jitter of a phone on a table cannot throw away a good forecast;
beyond that the coordinates move, the cached snapshots and alerts are dropped as describing the place
you left, and the name is blanked for the next refresh to re-resolve.

The permission asked for is `ACCESS_COARSE_LOCATION` and nothing more — a forecast grid cell is about
2.5 km square, so fine location would buy nothing and ask more of the user. There is no Play Services
dependency: the platform `LocationManager` is enough, cheapest-first (a recent last-known fix, then
one bounded active request, then the stale fix as a fallback). Every failure is a value rather than an
exception — no permission, location switched off device-wide, no fix, no forecast for this spot — and
each one the tile can phrase for the user, with the button that clears it where a button exists. None
of them is fatal: a user who never grants location gets the first place they added in LifeOps,
refreshed, and a "Use my location" button that removes itself once taken up.

### The gear: appearance and backups

**Appearance** (see *One look for seven apps* below) is the suite's, not LifeOps': one preset, one
light/dark mode, one custom palette, an accent per app — and the home screen's own wallpaper, which
is the container's alone.

**Backups** is what the hub has always done, moved behind the gear:

- A row per app with a **selection checkbox** and its backup format version (LifeOps is tagged
  `STANDARD`).
- **Full Backup** → the system *create-document* picker → the selected apps stream into one `.zip`.
- **Restore from zip…** → the system *open-document* picker → the archive's manifest is read, then
  the apps that are both selected and present in the archive are restored.

---

## One look for eight apps (`:suitekit` + `:suiteui`)

Every hosted app used to own its palette: Citation's warm paper, Health's clinical teal, LifeOps'
five presets. That made the apps that happened to be installed together look like apps that
happened to be installed together. Appearance now belongs to the **container**, and an app names
itself rather than choosing colours.

| Module | Kind | Holds |
|---|---|---|
| `:suitekit` | pure JVM, unit-tested | the presets, the custom palette, each app's colour identity, the ARGB and HSL maths that resolves them into a full `SuiteScheme`, and the swatch palette the picker offers |
| `:suiteui` | Android library | `SuiteAppearanceStore` (the one preferences document), `SuiteTheme` (the single Compose theme every app wraps itself in), and `ui/pickers` — the suite's shared input controls |

The split is the same discipline as `:core` and `:backupkit`: no colour decision is made in Android
code, so all of it is testable on the JVM without an emulator.

### One picker per question (`:suiteui/ui/pickers`)

Appearance was the first thing the container took over; the **controls** are the second. Five apps
had each grown their own colour picker, date picker and time picker, and they had drifted — one
cleared a date with a button where another used the Cancel slot, one wrote `17:00` next to a dial
that said `5:00 PM`, and three separately re-derived that Material's date picker hands back *UTC*
midnight. There is now one of each, and the container owns them:

| Control | What it is for | Value it speaks |
|---|---|---|
| `SuiteColorField` / `SuiteColorPickerDialog` | any colour a person chooses | `#RRGGBB` text |
| `SuiteDateButton` / `SuiteDateField` | a calendar day | `LocalDate`, with overloads for ISO text and local-midnight millis |
| `SuiteTimeButton` / `SuiteTimePickerDialog` | a time of day | minutes from midnight |
| `SuiteWhenField` / `SuiteWhenPickerDialog` | a moment that has already happened | epoch millis |
| `SuiteDates` / `SuiteClock` / `SuiteElapsed` | how a day, a clock time and a gap are *written* | — |

Three rules they all keep. **Nothing is typed** — not a hex code, not a date: a hex code typed into
a live palette flashes the whole suite through the colours `#2`, `#2C`, `#2C7` happen to parse as,
and a date typed on a phone is how 2025 becomes 2205. **A picker never offers an impossible
choice**, so `notBefore` / `notAfter` bound the calendar itself instead of an error message
afterwards — Health's "you cannot record a temperature that hasn't been taken yet" is one
`notAfter` and nothing else. And **the conversion happens at the edge**: callers hand over the type
they store and the picker deals with UTC midnight, so the trap is handled once.

### How an app gets its colour

```kotlin
@Composable
fun HealthTheme(content: @Composable () -> Unit) {
    SuiteTheme(appId = AppId.HEALTH, content = content)
}
```

That is the whole of each app's `Theme.kt` now. `SuiteTheme` resolves two things, in order:

1. **The shared look** — the preset (Default, Beacon, Ocean, Sunset or Custom) and light/dark mode
   chosen once in the sandbox. This is what makes the suite feel like one product. The presets are
   LifeOps' own, promoted value-for-value rather than replaced, so an existing install's look
   survives the move.
2. **The app's accent** — its identity colour, also chosen in the sandbox. It repaints the
   primary/secondary roles and washes a *tint* (10% dark, 6% light) into the surfaces, while the
   tertiary stays the preset's: one colour in every app that is the suite's rather than the app's.

An accent that would be illegible in the current mode is lifted or dropped to the nearest readable
brightness (`SuiteColors.fitForMode`) — never further, so a chosen hue survives exactly when it can.
Turning **"Tint each app with its colour"** off silences step 2 everywhere; home-screen icons keep
their colours regardless, because that is how apps are told apart. An app's *icon* colours are a
separate choice again — see *Icon colours, per app* below.

### One store, one write

`SuiteAppearanceStore` is a process-wide singleton over one preferences file. The sandbox and the
hosted apps share a process, so an edit in settings reaches every composed screen through a
`StateFlow` — no broadcast, no restart. LifeOps' own Appearance card writes the *same* setting
(`PreferencesRepository` delegates to the store, and `ThemePreset`/`CustomPalette` are now aliases
of the suite's types), so the two doors cannot disagree. On first read the store adopts LifeOps'
existing preset, mode and palette, so nobody's theme resets.

Adding an app's identity is one entry in `SuiteApps` (label, tagline, icon name, default accent, and
— for an app that has icon colours of its own — those) and one mark in `SuiteGlyphs` under that name — `:suitekit` stays Android-free by naming glyphs rather
than importing them.

### Eight marks

The home screen first shipped with stock Material icons, one per app, and they were wrong twice
over. They named a *category* — a dashboard, a box, a group of people — where the screen needed a
picture of the work. And four of the seven were rectangles with something inside them, so the grid
read as four grey boxes and a book, leaving colour to do all the identifying on its own.

`SuiteGlyphs` replaces them with a drawn mark per app: a dial, a thermometer, a roofline, a board, a
basket, an open book, a bubble with a tail, a spanner on the diagonal. The rule they are drawn to is that **no two share a
silhouette**, so a tile is recognisable in peripheral vision — before the colour registers, and well
before the label is read. A unit test holds the weaker half of that line: every app resolves to a
mark of its own, no two marks carry the same geometry, and nothing falls back.

LifeOps' is not a new drawing. It is the mark LifeOps already wore as its launcher icon
(`ic_app_logo`: a dial, four quarter ticks, a checkmark for a needle), redrawn at icon scale — its
proportions adapted rather than transcribed, since a hairline ring at 35% alpha reads as a delicate
dial on a 120dp logo and disappears entirely at 18dp. The ring is heavier and less faint; the
1 : 1.6 : 2.6 weight ladder from ring to ticks to needle is kept, and a test asserts it.

Every mark is drawn in the same 24×24 viewport as line art with selective solid fills, and every one
has to *work* in a single colour, so a mark can never lean on a second hue to be legible. What it
*can* lean on is alpha — a stroke at 0.5 survives tinting, and is how secondary detail (a book's text
lines, a board's header rule) stays subordinate to the shape carrying the identity.

Every mark can also be *built* in two colours, for an app given icon colours of its own. The split is
the same in all eight and it is the one LifeOps' launcher icon already draws: `Ink.line` takes the
structure that holds the mark up — a dial's ring, a thermometer's tube, a house's walls — and
`Ink.highlight` takes the one element the mark exists to show: the checkmark standing in for a
needle, the mercury in the tube, the figure under the roof, the cards on the board, the spark in the
bubble, the jaws of the spanner, the text on the pages, the handle over the basket. A mark is a
*function* of its ink (`marks: Map<String, (Ink) -> ImageVector>`) rather than a finished vector, so
the tintable form and the two-colour one cannot drift into being different drawings — a test asserts
they are the same geometry, and that both roles are actually used in each mark, since a role nothing
is drawn in is a settings field with no effect.

### No tile behind them

The mark *is* the icon: `AppGlyph` draws it straight onto the wallpaper (or, in settings, onto the
surface), with nothing behind it. It used to sit on a rounded tile flooded with the app's colour,
which made the grid a row of coloured boxes with a small glyph punched out of each — the tile was
carrying the identity and the drawing was decoration on it. Without the tile the drawing carries it,
which is what the silhouette rule above was for, and the wallpaper stays visible between the apps.

Losing the tile means the mark itself is now the coloured thing, so it is drawn in the app's accent
rather than in an ink contrasting it, nudged only as far as the backdrop demands
(`SuiteColors.fitForMode`) — a near-black accent on a night wallpaper lifts until it reads, and no
further.

That is the default, and it is what an app with no **icon colours** gets. An app that has a pair is
drawn in it instead — `SuiteGlyphs.inColour` builds the same geometry wearing the two inks, fitted to
the backdrop the same way, since a palette written for a dark launcher background is still asked to
read on a light wallpaper.

A pair comes from one of two places. LifeOps **ships** one (`SuiteAppInfo.iconColors`, the purple and
amber of `icon_dial` / `icon_check`), so the dial on the home screen is the icon on the launcher
rather than a monochrome copy of it. And any app can be **given** one in the sandbox settings — see
*Icon colours, per app* below, which is also where the one rule about who wins lives.

### Icon colours, per app

What LifeOps arrived with, every app can be given: two colours for its mark instead of one tint. The
gear's **App colours** card holds it, under each app's accent — a switch, and a `Line` and
`Highlight` hex field behind it (`SuiteAppearance.iconPaints`, keyed by `AppId.key` exactly as the
accents are).

Switching it on for an app that ships nothing starts from the suite's own **secondary and tertiary
for that app** rather than from a random pair. That mapping is not invented here: it is the one
LifeOps' icon has always used — `icon_dial` is a secondary, `icon_check` a tertiary, in
`assets/icon-themes.css` for every preset — so an app given icon colours starts out looking like it
belongs to the same suite. A blank or half-typed field falls back to that seed too, so editing a hex
code never blanks a mark mid-keystroke.

Switching it *off* is remembered as a choice rather than as an absence: the pair stays stored with
`enabled = false`, so switching back on returns the colours instead of re-seeding them, and an app
that ships its own can be told to stop using them.

**Sandbox always wins** (the card's second switch, on by default) settles the one place two
deliberate choices can disagree: an app *ships* icon colours **and** has been repainted here. On, the
colour picked in the gear wins and the mark is tinted with it — a setting that visibly does nothing
is worse than a mark that loses its shipped hues. Off, the app keeps the colours it came with and the
accent is left to tint its screens instead. It settles nothing else: colours chosen *here* for an app
are the sandbox's own choice, so they are drawn either way, and an app nobody has repainted keeps its
shipped pair under both settings.

---

## The launcher's wallpaper

The home screen is the one screen in the suite that belongs to nobody's app, so it is the one screen
worth decorating. The wallpaper is **the container's alone**: it never reaches a hosted app, whose
look is still the preset, the mode and its accent.

The decision lives in `:suitekit` (`SuiteWallpaper.kt`) exactly as colour schemes do — `:suiteui`
only turns the answer into Compose brushes:

| Type | Is |
|---|---|
| `SuiteWallpaper` | what the user chose: a design, the custom knobs behind it, and a dim |
| `WallpaperDesign` | the shipped looks — `THEME`, ten fixed designs, and `CUSTOM` |
| `WallpaperStyle` | how colours are laid out: `SOLID`, `LINEAR`, `RADIAL` (one glow), `AURORA` (several) |
| `WallpaperSpec` | the resolved answer: a base colour, gradient layers over it, a veil, and the ink |

**`THEME` is the default and the only design that is not a fixed picture** — it mixes itself from
whatever preset and mode the suite is wearing, which is exactly what the home screen did before
wallpapers were selectable, so an install that never opens the picker looks unchanged. The ten fixed
designs (Midnight, Nebula, Aurora, Tide, Ember, Sunrise, Forest, Graphite, Paper, Daylight) are
deliberate choices of colour and hold their look when the preset changes — that is the point of
choosing one. **Custom** hands over the same four styles, a direction for the gradient, and the two
or three colours it runs between; the custom colours are kept while a shipped design is showing, so
trying one and coming back does not lose what was typed.

Two things are decided for the user, and both are about being able to read the screen:

- **Ink.** `WallpaperSpec.ink` is resolved from the colours that actually end up on screen — a
  two-colour gradient reads as the blend of its ends, a glow as its field stained by the light — so
  the clock, the date and the tile labels are written in whichever of the suite's two inks contrasts
  the backdrop. A user cannot pick a wallpaper that hides their own clock. The status and navigation
  bar icons follow the same answer, since the shell draws edge to edge and those bars sit *on* the
  wallpaper.
- **Dim.** A veil of black, capped at 70%, applies to *any* design — which keeps "a picture I like"
  and "a clock I can read" from being the same decision. It is applied before the ink is chosen, so
  dimming a pale wallpaper far enough flips its text to light on its own.

In settings, each card in the picker paints itself with `Modifier.suiteWallpaper(spec)` — the very
modifier the home screen uses — so a preview *is* the design at swatch size rather than an artist's
impression of it.

---

## Testing

`:backupkit` has full JVM unit tests (`gradle :backupkit:test`, no SDK required): manifest
round-trip, backup→restore payload fidelity across apps, selection, skipping unknown/absent apps,
the no-manifest case, and the zip-slip guard.

`:suitekit` is tested the same way (`gradle :suitekit:test`, no SDK required): hex parsing of every
form the settings field accepts (and the fallback for a half-typed one), the exact luminance
`lighten`/`darken`/`fitForMode` promise, appearance-document round-trip and graceful decay of a
partial or unknown document, accents keyed by app key, and — across every preset × mode × app — that
each slot of the resolved scheme is opaque and that its text contrasts its surface. The wallpapers
are held to the same bar: every shipped design resolves to an opaque field with stops on the
gradient and one of the two inks, dimming a pale design flips its text before it can be swallowed,
a fixed design ignores the preset while `THEME` follows it, and a document written before wallpapers
existed (or one naming a design this build has never heard of) lands on the default backdrop.

`:suiteui` is an Android module but its icon set isn't: `SuiteGlyphsTest`
(`gradle :suiteui:testDebugUnitTest`) runs on the JVM because an `ImageVector` is data until
something draws it. It holds the wiring a code review can't see — every app resolves to a mark of
its own rather than the fallback, no mark exists for an app that doesn't, none is empty, all share
the 24×24 viewport that makes stroke weights comparable, and no two carry the same geometry — plus
LifeOps' ring-ticks-needle weight ladder, which is the part of its inherited mark that a well-meaning
tidy-up would flatten.

The rest of the Android glue (contributors, the home screen and settings, the module surgery) is
verified by building and running the container app.

> Note: code shrinking (`minifyEnabled`) is off in `:app`'s release build for now — the merged
> LifeOps + Citation code needs a vetted keep-rule set (Room/Gson/Glance/WorkManager reflection)
> before minify can be trusted. It's a deliberate follow-up.
