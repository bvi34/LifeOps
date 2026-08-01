# Migrating existing data into Operations Sandbox

The Operations Sandbox container is a **new app package** (`com.operations.sandbox`). Android app
isolation means it cannot read the data of your old standalone installs (`com.lifeops.app`,
`com.citation.app`) directly — that data is private to those packages. This guide copies it across
with `adb`.

> **Requirement:** the *old* apps must be **debuggable** builds (anything installed from Android
> Studio's Run/Debug is). `adb run-as` only works on debuggable packages — no root needed. If an old
> app is a release-signed install, this won't work without root; use its in-app export instead
> (partial for LifeOps — the old export omits resources/counters).
>
> **Schemas line up:** the container and the old apps are built from the same source, so the
> database schema versions match and the files copy across cleanly. If an old app is an *older*
> build, Room runs its migrations automatically the first time the container opens the file.

Package / path reference:

| | Old LifeOps `com.lifeops.app` | Old Citation `com.citation.app` | Container `com.operations.sandbox` |
|---|---|---|---|
| Database | `databases/lifeops.db` | `databases/citation.db` | both, same names |
| Owned files | — | `files/sovereign/…` | `files/sovereign/…` |
| Prefs | `shared_prefs/lifeops_*.xml` | `shared_prefs/oreilly_access.xml` (encrypted) | `shared_prefs/lifeops_*.xml` |

---

## 0. Quiesce the databases first

Open **old LifeOps**, press Home (don't force-stop), wait a few seconds, then swipe it from Recents.
Do the same for **old Citation**. Backgrounding each app truncates its write-ahead log into the main
`.db` file, so copying just the `.db` (no `-wal`/`-shm`) captures everything. Then connect the device
with USB debugging on.

## 1. LifeOps — pull from the old app

```bash
adb exec-out run-as com.lifeops.app cat databases/lifeops.db > lifeops.db
adb exec-out run-as com.lifeops.app cat shared_prefs/lifeops_settings.xml > lifeops_settings.xml
adb exec-out run-as com.lifeops.app cat shared_prefs/lifeops_prefs.xml   > lifeops_prefs.xml
```

## 2. LifeOps — push into the container

```bash
adb shell run-as com.operations.sandbox mkdir -p databases shared_prefs

adb push lifeops.db /data/local/tmp/
adb shell "run-as com.operations.sandbox cp /data/local/tmp/lifeops.db databases/lifeops.db"
# Drop any stale sidecars so a leftover WAL can't shadow the copied file:
adb shell "run-as com.operations.sandbox rm -f databases/lifeops.db-wal databases/lifeops.db-shm"

adb push lifeops_settings.xml /data/local/tmp/
adb shell "run-as com.operations.sandbox cp /data/local/tmp/lifeops_settings.xml shared_prefs/lifeops_settings.xml"
adb push lifeops_prefs.xml /data/local/tmp/
adb shell "run-as com.operations.sandbox cp /data/local/tmp/lifeops_prefs.xml shared_prefs/lifeops_prefs.xml"
```

## 3. Citation — pull (database + owned books)

```bash
adb exec-out run-as com.citation.app cat databases/citation.db > citation.db
adb exec-out run-as com.citation.app tar -c -C files sovereign > sovereign.tar
```

## 4. Citation — push into the container

```bash
adb shell run-as com.operations.sandbox mkdir -p databases files

adb push citation.db /data/local/tmp/
adb shell "run-as com.operations.sandbox cp /data/local/tmp/citation.db databases/citation.db"
adb shell "run-as com.operations.sandbox rm -f databases/citation.db-wal databases/citation.db-shm"

adb push sovereign.tar /data/local/tmp/
adb shell "run-as com.operations.sandbox tar -x -C files -f /data/local/tmp/sovereign.tar"
```

## 5. Clean up and launch

```bash
adb shell rm -f /data/local/tmp/lifeops.db /data/local/tmp/lifeops_settings.xml \
  /data/local/tmp/lifeops_prefs.xml /data/local/tmp/citation.db /data/local/tmp/sovereign.tar
```

Launch **Operations Sandbox** and open LifeOps / Citation — your tasks, resources, counters, weeks,
books, and notes should all be there.

## What does not carry over

- **Citation's O'Reilly card + PIN.** It's stored in Keystore-bound `EncryptedSharedPreferences`
  whose key is tied to the old package and can't be decrypted under the new one. Re-enter the card/PIN
  once in the container; everything else (books, notes, highlights, reading state) migrates.

---

Once your data is in the container, the container's own **Full Backup** (Operations Sandbox home →
Full Backup) produces a single `.zip` that *does* capture everything — it uses whole-file database
copies, so no table is left behind. That's the backup to rely on going forward; this adb dance is a
one-time move off the old standalone installs.
