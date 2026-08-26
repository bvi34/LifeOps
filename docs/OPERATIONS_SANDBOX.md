# Operations Sandbox

Operations Sandbox is the **container** the whole suite ships inside — think a lightweight
Docker-style host crossed with a single sign-on hub. It is the one installed app and the one
launcher icon. Opening it gives you a **phone home screen**: a tile per app, each with its own icon
and colour, over a dock holding the gear and the backups. From there it:

- opens any of the apps we build (**LifeOps** — the standard app — **Citation**, **Logistics**,
  **Advisor**, **Health**, and **People**),
- **paints all six of them**: one preset, one light/dark mode, and one accent per app, chosen in the
  gear and obeyed everywhere, and
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

Because the two apps share one process/package, `shared_prefs/` holds everyone's prefs together, so
each contributor scopes strictly to its own files by name.

**Restore requires a restart.** Swapping database files closes the live Room handle for the lifetime
of the process, so after a restore the sandbox tells you to **fully close Operations Sandbox and
reopen it** — reopening just the hosted screen would reuse the now-closed database.

---

## The GUI (`:app`)

The sandbox opens on a **phone-style home screen**, because that is the honest picture of what it
is: six apps behind one icon.

- A **tile per app**, laid out three to a row, each carrying that app's own glyph and colour — so
  "the teal one" and "the green one" mean something before you have read a word. Tapping a tile
  launches that app's (now non-launcher) `MainActivity` in the same process; **pressing and holding**
  jumps straight to where that app's colour is chosen.
- A **clock strip** above the grid and a **dock** below it. The dock holds what belongs to the
  container rather than to any app: **Settings** (the gear) and **Backups**.
- The wallpaper is mixed from the suite's own colours, so the home screen is already wearing the
  chosen preset before an app is opened.

`SandboxActivity` is a two-route shell (`when` over a route, not a navigation graph). The settings
screen's state — which tab, which app is being recoloured — is hoisted into it, and so is the
`BackupController`: an archive can take a while, and backing out to the home screen mid-backup must
not cancel it.

### The gear: appearance and backups

**Appearance** (see *One look for six apps* below) is the suite's, not LifeOps': one preset, one
light/dark mode, one custom palette, and an accent per app.

**Backups** is what the hub has always done, moved behind the gear:

- A row per app with a **selection checkbox** and its backup format version (LifeOps is tagged
  `STANDARD`).
- **Full Backup** → the system *create-document* picker → the selected apps stream into one `.zip`.
- **Restore from zip…** → the system *open-document* picker → the archive's manifest is read, then
  the apps that are both selected and present in the archive are restored.

---

## One look for six apps (`:suitekit` + `:suiteui`)

Every hosted app used to own its palette: Citation's warm paper, Health's clinical teal, LifeOps'
five presets. That made six apps that happened to be installed together look like six apps that
happened to be installed together. Appearance now belongs to the **container**, and an app names
itself rather than choosing colours.

| Module | Kind | Holds |
|---|---|---|
| `:suitekit` | pure JVM, unit-tested | the presets, the custom palette, each app's colour identity, and the ARGB maths that resolves them into a full `SuiteScheme` |
| `:suiteui` | Android library | `SuiteAppearanceStore` (the one preferences document) and `SuiteTheme`, the single Compose theme every app wraps itself in |

The split is the same discipline as `:core` and `:backupkit`: no colour decision is made in Android
code, so all of it is testable on the JVM without an emulator.

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
their colours regardless, because that is how apps are told apart.

### One store, one write

`SuiteAppearanceStore` is a process-wide singleton over one preferences file. The sandbox and the
hosted apps share a process, so an edit in settings reaches every composed screen through a
`StateFlow` — no broadcast, no restart. LifeOps' own Appearance card writes the *same* setting
(`PreferencesRepository` delegates to the store, and `ThemePreset`/`CustomPalette` are now aliases
of the suite's types), so the two doors cannot disagree. On first read the store adopts LifeOps'
existing preset, mode and palette, so nobody's theme resets.

Adding an app's identity is one entry in `SuiteApps` (label, tagline, icon name, default accent) and
one line in `SuiteIcons` mapping that name to a Material icon — `:suitekit` stays Android-free by
naming glyphs rather than importing them.

---

## Testing

`:backupkit` has full JVM unit tests (`gradle :backupkit:test`, no SDK required): manifest
round-trip, backup→restore payload fidelity across apps, selection, skipping unknown/absent apps,
the no-manifest case, and the zip-slip guard.

`:suitekit` is tested the same way (`gradle :suitekit:test`, no SDK required): hex parsing of every
form the settings field accepts (and the fallback for a half-typed one), the exact luminance
`lighten`/`darken`/`fitForMode` promise, appearance-document round-trip and graceful decay of a
partial or unknown document, accents keyed by app key, and — across every preset × mode × app — that
each slot of the resolved scheme is opaque and that its text contrasts its surface.

The Android glue (contributors, the home screen and settings, the module surgery) is verified by
building and running the container app.

> Note: code shrinking (`minifyEnabled`) is off in `:app`'s release build for now — the merged
> LifeOps + Citation code needs a vetted keep-rule set (Room/Gson/Glance/WorkManager reflection)
> before minify can be trusted. It's a deliberate follow-up.
