# Operations Sandbox

Operations Sandbox is the **container** the whole suite ships inside — think a lightweight
Docker-style host crossed with a single sign-on hub. It is the one installed app and the one
launcher icon. Opening it gives you a home screen that:

- lists the apps we build (**LifeOps** — the standard app — and **Citation**),
- opens either one, and
- backs the **whole suite up into a single `.zip`** and **restores from that same zip**.

The GUI and the backups are unified: one hub, one archive.

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

- **LifeOps** — reuses its existing lossless JSON snapshot (`BackupRepository`), the same bytes the
  in-app Settings → Backup produces, as a single `lifeops/data.json`. Restore is an idempotent
  row-merge into the live DB, so **no restart needed**.
- **Citation** — WAL-checkpoints and copies `citation.db`, plus every owned file under
  `filesDir/sovereign` (imported EPUB/PDF and the sync envelope store). The disposable Royal Road
  cache is intentionally **not** backed up (it's refetchable by design). Restore is a whole-file DB
  swap, so **a Citation restart is expected** afterwards — the sandbox says so in its status line.

---

## The GUI (`:app`)

`SandboxActivity` is a single Compose screen (`BackupCenter` does the work):

- A card per app with a **selection checkbox** and an **Open** button (LifeOps is tagged
  `STANDARD`). Open launches that app's (now non-launcher) `MainActivity` in the same process.
- **Full Backup** → the system *create-document* picker → the selected apps stream into one `.zip`.
- **Restore from zip…** → the system *open-document* picker → the archive's manifest is read, then
  the apps that are both selected and present in the archive are restored.

Adding a third hosted app later is authoring, not engineering: add an `AppId`, ship a
`BackupContributor`, and register it in `BackupCenter` (and add the module as an `:app` dependency).

---

## Testing

`:backupkit` has full JVM unit tests (`gradle :backupkit:test`, no SDK required): manifest
round-trip, backup→restore payload fidelity across apps, selection, skipping unknown/absent apps,
the no-manifest case, and the zip-slip guard. The Android glue (contributors, GUI, the module
surgery) is verified by building and running the container app.

> Note: code shrinking (`minifyEnabled`) is off in `:app`'s release build for now — the merged
> LifeOps + Citation code needs a vetted keep-rule set (Room/Gson/Glance/WorkManager reflection)
> before minify can be trusted. It's a deliberate follow-up.
