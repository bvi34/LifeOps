# Operations Sandbox

Operations Sandbox is the **container** the whole suite ships inside — think a lightweight
Docker-style host crossed with a single sign-on hub. It is the one installed app and the one
launcher icon. Opening it gives you a **phone home screen**: a tile per app, each with its own icon
and colour, over a dock holding the gear and the backups. From there it:

- opens any of the apps we build (**LifeOps** — the standard app — **Citation**, **Logistics**,
  **Advisor**, **Health**, **People**, **Project**, **Maintenance**, **Finance**, **Repository** and
  **Secrets**),
- **paints all of them**: one preset, one light/dark mode, and one accent per app, chosen in the
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

### Who asks for a permission

The same merge that makes one launcher icon makes **one package**, and a runtime permission is
granted to a package rather than to an app. That is easy to forget in a repository laid out as
eleven apps, and it had already been forgotten once.

`POST_NOTIFICATIONS` was asked for by LifeOps, on the first launch of LifeOps, because that is where
it lived when LifeOps was an app you installed. Health declares the same permission and posts
medication reminders from `MedicationReminderWorker`; Citation's narrator declares it too. So a
household that used Health and never opened LifeOps was **never asked**, and every reminder they set
up was posted into a void — nothing failed, nothing was logged, and the only symptom was a reminder
that did not arrive.

The ask therefore belongs to the container, which owns the one screen everybody passes through.
`SandboxActivity` puts the question once and does nothing with the answer: a refusal is not an error
and there is nothing on a home screen it should change. The record of having asked is
`SuiteNotifications` in `:suiteui` — not in `:app`, because every hosted app must be able to read it
and none of them may depend on the container. LifeOps still asks if it is somehow opened first (a
notification tap, the widget) and consults the same record, so the household sees one prompt
whichever door they came in by.

An app that needs the permission *now* is a different question and keeps asking it for itself:
Citation's Listen screen asks in context, where a refusal has something concrete to say.

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

- **Finance** — WAL-checkpoints and copies `finance.db` (the connections, the accounts and their
  balances, the transaction history, and the bills with what has been paid), plus
  `shared_prefs/finance_*.xml`. It is the one contributor that deliberately leaves something behind:
  **no credential travels**. The Plaid client secret, the per-connection access tokens, the Mercury
  API tokens and the sync cursors live in `secure_finance_access`, whose name does not match the
  `finance` prefix this contributor collects by — so the exclusion is a property of the name rather
  than of a filter somebody could relax later. The alternative would be a zip in a cloud drive
  carrying a standing read grant on a bank account, which cannot be rotated by changing a password
  and whose escape nobody would notice. What used to be the cost of that — reconnecting every bank on
  new hardware — is now paid by **Secrets**, below: the credentials are mirrored into the vault,
  which travels sealed. The older years of transaction history, on the other hand, genuinely are the
  only copy — providers hand over a rolling window — which is why the database is copied as bytes.

- **Secrets** — copies the vault, `vault.opsv`, byte for byte, plus `shared_prefs/secrets_*.xml`
  (auto-lock, clipboard timeout — preferences, not secrets). This is the one contributor that
  **deliberately puts credentials in the archive**, and the only one whose restore refuses to
  overwrite what it finds.

  Both need saying. What travels is not a credential but a *sealed file* whose key is a passphrase in
  somebody's head — 310,000 rounds of PBKDF2 away from a key that wraps the one the vault is
  encrypted with. Copy it out of the zip and you have what a thief holding the phone would have. That
  is what makes the credentials the rest of the suite mirrors into it (Finance's tokens, Citation's
  sign-ins, **and the container's own GitHub update token and Azure backup signature**) survive onto a
new phone at last. The
  shell's token is worth calling out because it is the case that proves the pattern is not an
  app-by-app courtesy: the sandbox kept it exactly as Finance kept its bank tokens, lost it on
  exactly the same restore, and afterwards could not tell anybody a new version existed. It files at
  `sandbox/self/github-token` under a `SecretOwner` that is the container rather than an `AppId` —
  the shell has no tile and no payload of its own, so it is deliberately not a twelfth app. The **device unlock** — the vault key wrapped by *this*
  phone's Keystore for the fingerprint shortcut — is excluded, by a file name
  (`secure_secrets_device`) that fails this contributor's prefix test: an archive holding both the
  sealed vault and a device-unwrappable copy of its key would be an archive holding the vault in
  plaintext.

  And the restore: onto a phone with **no** vault the archived one becomes the vault, which is the
  case this app was built for. Onto a phone that already **has** one it is staged beside it and
  offered in Secrets' settings as something to **merge** — because a wholesale swap here would delete
  every password added since the backup, and unlike a balance or a transaction there is nowhere to
  fetch those back from. See **[SECRETS.md](SECRETS.md)**.

Because the apps share one process/package, `shared_prefs/` holds everyone's prefs together, so
each contributor scopes strictly to its own files by name.

**Restore requires a restart.** Swapping database files closes the live Room handle for the lifetime
of the process, so after a restore the sandbox tells you to **fully close Operations Sandbox and
reopen it** — reopening just the hosted screen would reuse the now-closed database.

### Is it actually full? (`BackupCoverageTest`)

Every contributor says it copies its app whole. Nothing checked the claim *across* the suite, and
the failure mode is by definition the file nobody thought about — a second database added to an app,
a store that writes beside the one that is swept, a preferences file whose name slipped outside its
app's prefix.

So `:app` takes a **census**. It makes every app put its real files on disk — databases through each
app's own singleton, preferences through each app's own file-name constant, owned files through each
app's own store — takes one archive, and then walks the whole data directory asserting that each
file is either **in the archive** or **on a written list of deliberate exclusions, with the reason
attached**. Measuring against paths spelled out in the test would only prove the list matches itself;
measuring against what the apps themselves declare is what catches next year's database.

It also checks that every `AppId` has exactly one contributor, that the manifest names every app that
contributed and nothing it didn't, and that a **wipe-and-restore** returns every archived byte.

What the census found on its first run, both now fixed:

- **Citation's `speech_settings.json`** — the narrator's voice, speed and sleep timer — sits in
  `filesDir` *beside* `sovereign/` rather than inside it, so the sweep missed it. A restored phone
  had every book and no voice. It is now carried, named by the store that owns it.
- **Health and Repository share `filesDir/documents`.** One process, one package, one folder: each
  app's contributor sweeps it whole, so every document is in the archive twice and restoring either
  app brings back both apps' files. Harmless, surprising, and now a test that fails if either app
  moves.

The deliberate exclusions, each of which the test forces somebody to justify in a line: caches
(Citation's Royal Road bodies, Advisor's vector index), Advisor's downloaded models, Citation's
export copies, every credential store (the vault carries those), the device unlock, the container's
own preferences — and two that are worth knowing about:

| Left out | Why |
|---|---|
| `logistics_prefs` | a display toggle; `LogisticsBackupContributor` says so in its own words |
| `repository_prefs` | the last drive and folder a transfer used — SAF grants that do not survive a reinstall |
| `operations_suite_appearance` | **the suite's whole look** — preset, palette, per-app accents, wallpaper. It belongs to the container rather than to any hosted app, and the archive has no slice for the container, so it is re-chosen after a restore. The one gap here that is a *choice* rather than a reason |

### The other backup (Android Auto Backup)

Everything above is the archive somebody **takes**. Android runs one of its own that simply *happens*
to them — off a charger, onto Google's transport — and it is the one that actually restores this
suite when a household sets up a new phone and taps "restore from backup" long before they hear the
app has an archive of its own. What it carries is `app/src/main/res/xml/backup_rules.xml` (and
`data_extraction_rules.xml` for API 31+, which must say the same thing for a cloud restore and for a
phone-to-phone transfer).

Those rules named **files**, and the files they named were `lifeops.db` and `citation.db` — true when
the container held two apps. Nine databases arrived afterwards and not one was added, because nothing
anywhere failed when one wasn't. Preferences were swept by domain the whole time, so a restored phone
came up with everybody's settings, LifeOps' and Citation's data, and nine apps that had forgotten
everything: the worst shape this failure can take, because it looks like a working restore.

So the rules now take the `database` domain **whole**. A rule that names a domain cannot fall behind
the apps the way a rule that names files did, and `AutoBackupRulesTest` is what proves it hasn't — it
makes every app create its real database, through the app's own singleton, and evaluates the shipped
rules against what is on the disk.

What stays out, and why:

| Left out | Why |
|---|---|
| `filesDir` | Citation's books, Advisor's models, the household's documents — hundreds of megabytes against Auto Backup's 25 MB quota, and an app over the quota is not trimmed, it is **skipped**. These are the sandbox zip's job |
| every credential store | `EncryptedSharedPreferences`, whose Keystore key never travels: the restored file meets a key that cannot open it. The `_plain` fallbacks fail the other way, holding the credential in the clear. The vault carries these onto the new phone — see [SECRETS.md](SECRETS.md) |

The second half of `AutoBackupRulesTest` asserts that, because "back up everything" and "never back
up a key" are one policy, and a change that widened the first at the cost of the second would
otherwise pass unnoticed. It also asserts the three rule sections are identical: a policy that
disagrees with itself across two files is a bug waiting for the one restore nobody rehearsed.

---

## Scheduled backups to Azure (`:backupkit/cloud` + `:app/cloud`)

The Full Backup above is deliberate and manual: somebody picks a moment, picks a file, and knows
where the zip went. That is the right shape for a backup somebody *takes* and the wrong shape for
the one that *saves* them — the archive that matters is the one from the ordinary Tuesday nobody
thought about, and it has to be somewhere other than the phone that is about to be dropped in a
river. So the sandbox can also write the same archive, on a schedule, into a container in the
household's own Azure storage account.

Same archive, same engine, same contributors. Nothing about the format changes; this is a
destination and a clock.

### Why a SAS, and no Azure SDK

Authentication is a **shared access signature** — a query string the household generates on their own
container — and never an account key. An account key *is* the account: every container, read and
write and delete, with no expiry. A SAS is the same credential narrowed on all four axes that
matter: one container, create/write only, an expiry date, and revocable from the portal without
touching anything else.

It is also *just a query string*, which is what lets the Android side be one class of
`HttpURLConnection` (`AzureBlobStore`: `PUT` an archive, `GET` a listing, `DELETE` what rolled off).
The Azure Storage SDK would add several megabytes and a dependency tree to a sideloaded APK to save
about forty lines and a request signature nobody needs.

The cost of a SAS is that it expires — which is the point, and is why `AzureSas` reads `se` and `sp`
straight out of the token. The settings screen can say *"Signature allows upload, list, delete —
expires in 11 days"* on the day it is pasted, rather than the household finding out on the day it
stopped.

### What lives where

Everything that **decides** anything is pure JVM, in `:backupkit/cloud`, tested without an emulator:

| | |
|---|---|
| `AzureBlobTarget` | account + container + prefix + SAS → the blob and listing URLs; Azure's own naming rules, checked here rather than by a 400 at 2am. `TargetCheck` is either a usable target or the first thing wrong with it, so a screen cannot show a green tick over an address nothing will reach. |
| `AzureSas` | expiry and permissions, read off the token. An unparseable expiry is "unknown", never "expired" — a date format must not be what switches a household's backups off. |
| `CloudBackupSchedule` | whether a run is owed: never-run, a clock moved backwards, and a wake-up a few minutes early all have the answer the household would want, and a test each. |
| `CloudBackupNaming` | `operations-backup-20260912-020005Z.zip`, stamped in **UTC** so lexicographic order is chronological order — a phone that changes timezone must not be able to reorder the archives that retention deletes by. |
| `CloudBackupRetention` | which archives have rolled off the keep-count. |
| `AzureBlobListing` / `AzureBlobStatus` | reading a `List Blobs` answer and an error document; whether a status is worth retrying. |

The Android half (`:app`'s `com.operations.sandbox.cloud`) is glue: `CloudBackupPrefs` (settings, and
the signature), `AzureBlobStore` (three requests), `CloudBackupRunner` (write → upload → prune →
write down what happened) and `ScheduledCloudBackupWorker` (a periodic WorkManager job).

### The safety properties worth stating

- **Retention only ever deletes archives this app wrote.** Candidates are filtered by
  `CloudBackupNaming.isArchiveName`, so a photo, a `readme.txt`, or a zip copied into the same
  container by hand is never a candidate — whatever the keep-count says. "Keep everything" is what an
  unconfigured retention setting means.
- **Nothing is uploaded until it is switched on**, and off is genuinely off: the job is *cancelled*,
  not left to wake and find a flag false.
- **Wi-Fi only, by default.** A whole-suite archive is not a few kilobytes, and nobody should meet
  this feature through their mobile bill.
- **The archive is staged to the cache and deleted in a `finally`.** Blob storage wants a content
  length up front and a phone cannot hold a suite-sized archive in memory to find one; a failed
  upload that left a copy of the household's entire data set in the cache would be its own small
  disaster.
- **A failed run still counts as a run.** `lastRunAt` moves on every attempt, so a wrong container
  name is not retried as fast as WorkManager will allow; `lastSuccessAt` moves only when an archive
  actually landed, and that is what the screen reports.
- **Only the server's own failures are retried.** A 403 does not fix itself, and retrying it with
  backoff until the phone is replaced is how a broken setting becomes a battery complaint.
- **The destination never travels in the archive.** These settings are the container's, like the
  updater's, and deliberately outside the backup: an archive carrying its own upload credential would
  let anyone holding a copy keep writing into the household's storage account.

### The one thing that does travel

The signature mirrors into the **vault**, like every other credential the suite holds, at
`sandbox/self/azure-backup-sas` under the `SecretOwner` that is the container. It is the shell's
second credential and it makes the same case the first one did: kept in `EncryptedSharedPreferences`
behind a hardware-bound key, it would die on a restore, and a new phone would look exactly like a
working one while having uploaded nothing since the day it was set up. See **[SECRETS.md](SECRETS.md)**.

### A minimal SAS

In the portal, on the **container** (not the account): *Shared access tokens* → permissions
**Create** and **Write** (add **List** and **Delete** to let old archives be pruned), an expiry the
household is willing to renew, HTTPS only. Paste the token — or the whole URL — into the Backups tab;
the app takes it either way.

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
- **Notice lines** under the clock, at most two, each one row and neither dismissible: *"v1.4.2 is
  available — tap to update"*, and *"3 credentials are waiting for your vault — tap to unlock"*. Both
  are conditions that end by being dealt with rather than by being acknowledged, which is why there
  is no ✕ on either. The second is the only thing anywhere outside Secrets that says the vault has
  something waiting on it: a credential written while the vault was shut is queued in memory and
  does not survive the process, so before this it could be lost in silence. See
  *Saying so* in **[SECRETS.md](SECRETS.md)** for why a locked vault on its own is deliberately not
  worth a line, and why a phone with no vault is offered one rather than told to unlock.
- A **count on one tile**, when Secrets has writes waiting. Only Secrets can have one: a home screen
  where every tile carries a red circle is a home screen where none of them mean anything.
- A **wallpaper** behind the lot — see *The launcher's wallpaper* below. Out of the box it is mixed
  from the suite's own colours, so the home screen is already wearing the chosen preset before an
  app is opened; a user who wants something else picks it in the gear.

`SandboxActivity` is a two-route shell (`when` over a route, not a navigation graph). The settings
screen's state — which tab, which app is being recoloured — is hoisted into it, and so is the
`BackupController`: an archive can take a while, and backing out to the home screen mid-backup must
not cancel it. The `WeatherWidgetController` is hoisted for the same reason — a location fix or a
forecast fetch must survive a trip to the gear.

### Whose screen it is (`SuiteHomeLayout`)

The grid shipped in the order `SuiteApps.all` declares. That is a reasonable order and it is nobody
in particular's: with eleven apps, a household that lives in Logistics and Health reaches past nine
tiles to get to them, and three apps they have never opened take the same room as the two they open
daily.

So the arrangement is theirs. A tile can be **moved** past its neighbours and **hidden** from the
screen entirely, in **Settings → Appearance → Home screen**; both live in the appearance document as
`homeOrder` (app keys) and `hiddenApps`.

**Not from a long-press on the tile**, which is the obvious place to put it and was briefly where it
went. A long-press is one gesture, it already means *jump to this app's colour*, and a menu taking
it over would trade a shortcut used whenever somebody dislikes a colour for one used the handful of
times a home screen gets rearranged. Arranging is a settings job; it is done rarely, deliberately,
and with the whole list in view — which is also the only shape in which a *hidden* app can be given
back. The long-press keeps its meaning and gains an `onLongClickLabel`, so TalkBack can offer the
gesture instead of leaving it undiscoverable.

Three things are deliberate, and all three are in `:suitekit` with unit tests rather than in the
Compose file:

- **A stored order is partial, not authoritative.** An app the order has never heard of is *appended*,
  not dropped. This is the case that matters: a household arranges their screen today, an update
  adds an app next spring, and an order treated as the whole truth would hide it with nothing to say
  so. The same tolerance drops a key naming an app this build does not have, and collapses a key
  stored twice.
- **Hiding is not deleting.** A hidden app keeps its place in the order, its data, its reminders and
  its slice of the backup; only the tile goes. It comes back where it was rather than at the end.
  Moving skips hidden neighbours, so a "move right" never swaps a tile past something invisible and
  appears to do nothing.
- **The last tile cannot be hidden.** An empty grid reads as a broken app rather than as a choice,
  and the person staring at it has no reason to look in Settings.

Settings lists *every* app, hidden ones included and drawn faintly, because hiding has to have
somewhere obvious that undoes it — and there is no tile left to act on once an app is off the
screen.

### The phone's launcher (`SuiteShortcuts`)

Eleven apps ship behind one icon. That is the point of the container and also its one concession:
on the phone's own home screen there is a single *Operations Sandbox*, and everything inside it is
two taps away at best. The suite had no shortcuts at all — long-pressing its icon offered nothing —
so it now publishes both kinds:

- **Dynamic** shortcuts are what that long-press offers: the four most recently opened apps, which
  is the only ranking that needs no setup and is right more often than any fixed list. A phone where
  nothing has been opened yet falls back to the household's own home-screen order, so a fresh
  install has a full set rather than none. An app they have **hidden** is offered by neither half —
  hiding is the more recent instruction, even for an app they used constantly last week.
- **Pinned** shortcuts are the household putting an app on the phone's home screen themselves, from
  the same settings card the arrangement lives in. This is the one that undoes the concession
  outright: Logistics gets its own icon, in its own colour, beside everything else they use. Every
  app is offered there, **including ones hidden from the suite's own grid** — the opposite of what
  the recent list does, and deliberately so: that list is a guess, and this is somebody pointing at
  an app. "Not on that screen, yes on this one" is a coherent thing to want.

Both route through `SandboxActivity` carrying an `EXTRA_OPEN_APP`, rather than naming a hosted
activity. The hosted activities are **not exported** — there is one launcher entry point, deliberately
— so a shortcut naming one would be a shortcut the launcher is not allowed to start. Routing through
the container also means backing out of a shortcut lands on the home screen rather than on nothing.

**The icons had to be rasterised by hand** (`SuiteMarkRaster`). The marks are Compose `ImageVector`s
and Compose is the only thing that draws one; a launcher shortcut is a `Bitmap` handed to another
process long after any composition has ended. Without it the eleven hand-drawn silhouettes would
stop at the edge of the app and every pinned shortcut would look like the same anonymous square. So
the renderer walks the vector's paths and strokes them with the width, cap, join and alpha the
vector declares — the same drawing, a different renderer. Two details are worth knowing:

- The mark sits on a **field of the app's accent**, where the suite's own home screen deliberately
  draws no tile behind it. The home screen can, because it owns what is behind it; a pinned shortcut
  lands on a wallpaper this app has never seen, where an untinted line drawing is one photograph
  away from invisible. It is drawn in one colour even for an app with icon colours of its own —
  LifeOps' purple dial on a field of LifeOps' purple would be nothing at all.
- **Group transforms are ignored rather than implemented**, because no mark uses one.
  `SuiteMarkRasterTest` fails the day a mark starts to, which is the day the renderer needs the
  other twenty lines — the failure mode otherwise is invisible from inside the app, where Compose's
  own renderer draws it perfectly.

### Weather on the home screen

The clock earns its place on this screen by being true without being asked. Weather is the only
other fact like that — *is it raining, and will it be?* gets asked more often than any tile here
gets tapped — so it sits directly beneath the clock as one glance-deep card: the current
temperature, what the sky is doing, the day's high and low, and a line of place / chance of
precipitation / wind. An active NWS watch or warning adds a strip across the top, tinted red at
Severe and above. Tapping it opens LifeOps' full **Weather** screen, and from there its two summary
cards go one level deeper still: current conditions open a radar map pinned to wherever the phone
is, and "Today's conditions" opens the hour-by-hour and day-by-day forecast with the outdoor-task
windows.

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
- **Scheduled backup to Azure** → the same archive, uploaded to the household's own storage
  container every day (or three days, or week), on Wi-Fi, keeping the newest few. Off until it is
  switched on. *Back up now* runs the identical path a scheduled run takes, so a wrong container or
  an expired signature shows up in front of somebody rather than at two in the morning. See
  *Scheduled backups to Azure* above.

The ticks govern the whole tab — the zip, the restore **and** the scheduled upload — rather than the
schedule keeping a second, invisible selection that could quietly keep uploading an app somebody had
unticked.

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
| `SuiteWhenField` / `SuiteWhenPickerDialog` | a moment | epoch millis |
| `SuiteTextField` / `SuiteNoteField` | words | `String` |
| `SuiteNumberField` | a number, whole or `decimals`, optionally `signed` | `String`, filtered per keystroke |
| `SuiteMoneyField` | an amount | text in, cents out |
| `SuiteDates` / `SuiteClock` / `SuiteElapsed` / `SuiteMoney` | how a day, a clock, a gap and an amount are *written* | — |

Rules they all keep. **Nothing is typed that can be picked** — not a hex code, not a date: a hex
code typed into a live palette flashes the whole suite through the colours `#2`, `#2C`, `#2C7`
happen to parse as, and a date typed on a phone is how 2025 becomes 2205. **The conversion happens
at the edge**: callers hand over the type they store and the picker deals with UTC midnight, so the
trap is handled once. **What you typed is what is held** — the number fields take and return a
`String`, because a field that parsed on every keystroke rewrites `"12."` under the cursor halfway
through `"12.5"`, and one holding a `Double` cannot tell "nothing entered" from "zero". And **a
filter is not a validator**: `SuiteNumberField` runs between keystrokes, so it accepts every
*prefix* of a number — a lone `-`, a trailing `.` — and leaves what the finished text means to the
caller.

### The app owns the rule, the picker owns the asking

The pickers first shipped with `notBefore` / `notAfter` bounds and a greyed-out calendar. That was
wrong twice. It assumed every app answers the same question the same way — and they plainly do not:

| The tap | Health | LifeOps |
|---|---|---|
| next Tuesday | *refused* — a reading dated ahead would move a dose window | fine, that is what a planner is for |
| last Tuesday | fine, **and said so**: "Filed late — this happened 6d ago." | *refused* if that week is closed — the task would not be filed at all |

And bounds only have two answers, when the interesting one is the third. So the calendar now offers
every day and asks the app, which replies with a `SuiteVerdict`:

- **`Fine`** — nothing to say.
- **`Note(message)`** — allowed, and worth saying out loud. Shown in the ordinary voice, inside the
  dialog *and* under the field afterwards, because "recorded as added late" is a fact about the
  value now sitting there, not a message to flash once and take away.
- **`Refused(reason)`** — confirm is disabled and **the reason is required**. A greyed-out square
  the person is left to guess about is a bug report waiting to be filed.

The rules live in the apps, as plain JVM objects with tests: `health/logic/HealthWhen`,
`lifeops/util/DueDates`. LifeOps' closed-week refusal is the case that shows why this matters —
`TaskRepository.addTask` returns without writing when the week is closed, so the task was typed,
confirmed, and silently gone. Nothing on the way in had ever mentioned the week's state.

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

The cloud destination is held to the same bar and in the same module: blob and listing URLs
(including a prefix, a continuation marker and a sovereign endpoint), Azure's account/container
naming rules, a SAS pasted in each of the three forms people copy it in, every shape Azure writes an
expiry in — and that an *unreadable* expiry is never treated as expired — permissions, the due-check's
awkward cases (never run, a clock moved backwards, an early wake-up), a real `List Blobs` answer and
a real error document, which statuses are worth retrying, and retention: newest kept, oldest first,
"keep everything" by default, and **nothing the app did not write is ever a candidate for deletion**.

The shell's own half is Robolectric (`gradle :app:testDebugUnitTest`): what an unconfigured install
does (nothing, on Wi-Fi, keeping a week), a destination read back from preferences, and the
signature's mirroring — both stores written, read back through the vault on a new phone, refilled
locally, forgotten on clear, queued while the vault is shut, and refiled into a rebuilt one.

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

The home screen's arrangement is pure and tested as such (`gradle :suitekit:test`):
`SuiteHomeLayoutTest` covers an order that predates half the suite, one naming an app this build
does not have, one naming the same app twice, that hiding holds a tile's place and gives it back,
that a move skips a hidden neighbour, that the ends run out, and that the last tile standing cannot
be hidden. `SuiteMarkRasterTest` holds the launcher icons to what `SuiteGlyphsTest` holds the marks
to — every mark has paths to draw, none hides inside a group transform the renderer would ignore,
and no two rasterise to the same geometry. `SuiteShortcutsTest` pins the ranking: a full set on a
phone with no history, no duplicates, nothing from a build that knew an app this one does not, and
nothing the household has hidden.

The platform's own backup is held to the same bar in the same place: `AutoBackupRulesTest` makes
every app create its database and evaluates the shipped `backup_rules.xml` and
`data_extraction_rules.xml` against the disk, so a database added next year fails here rather than
on somebody's new phone; it also pins that no credential store is carried and that the three rule
sections still say the same thing. `SuiteNotificationsTest` pins the one prompt: a household that
already granted the permission is never asked, nobody is asked twice, and a phone too old to have
the permission is never asked at all.

The rest of the Android glue (contributors, the home screen and settings, the module surgery) is
verified by building and running the container app.

> Note: code shrinking (`minifyEnabled`) is off in `:app`'s release build for now — the merged
> LifeOps + Citation code needs a vetted keep-rule set (Room/Gson/Glance/WorkManager reflection)
> before minify can be trusted. It's a deliberate follow-up.
