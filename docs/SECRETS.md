# Secrets — one vault, and the credentials that survive a restore

Secrets is the suite's password manager. It keeps the household's own logins, cards, licence keys
and notes — their second factors, and the password each one had before this one; and it keeps, under
the same lock, **every credential the rest of the suite holds** —
Finance's Plaid keys and bank access tokens, Citation's catalogue sign-ins and library card, and the
Operations Sandbox's own GitHub update token and the signature its scheduled cloud backup uploads
with — so that restoring a backup onto a new phone does not
throw them away.

It is a hosted library module inside the Operations Sandbox container (`:app`), a peer to LifeOps,
Citation, Logistics, Advisor, Health, People, Project, Maintenance, Repository and Finance. Its
framework-independent core — the file format, the key derivation, the document, the generator, the
audit, the merge — is `:vaultkit`, pure JVM and unit-tested without a device.

```
Operations Sandbox  →  Secrets  →  Unlock     (make one the first time; start again if it is forgotten)
                                →  Vault      (everything, searchable)  →  one item
                                                                            (its codes, and the
                                                                             passwords it replaced)
                                →  Generate   (characters, or words)
                                →  Check      (weak, reused, old, empty)
                                →  Settings   (auto-lock, passphrase, autofill, finishing a restore)
                                                →  Import  (which manager, then its export file)

Another password app  →  Credential Exchange  →  Secrets  (passkeys and all)  →  the review list
Chrome, 1Password, anything  →  Export  →  the share sheet  →  Secrets  →  the same review list

Any app or page  →  the system's autofill  →  Secrets  (unlock, or pick)  →  the form, filled
Any app or page  →  Credential Manager      →  Secrets  (unlock, then sign) →  a passkey
```

## The problem this app was built for

Every app in this suite that holds a credential keeps it the same way, and for a good reason. From
`FinanceBackupContributor`:

> **Every credential.** No Plaid client secret, no access token, no Mercury API token, no sync
> cursor. […] a zip file in a cloud drive that would otherwise carry a standing read grant on a bank
> account — one that cannot be rotated by changing a password, and whose escape the household would
> have no way of noticing.

That is right. Its cost was stated just as plainly: **restore onto a new phone and you reconnect.**
Every bank, every catalogue, every library card, from memory, on a phone that might be the
replacement for a lost one.

The reason the cost was unavoidable is worth naming precisely, because the fix follows from it: those
stores are encrypted with a key held in the phone's hardware. `EncryptedSharedPreferences` behind an
Android Keystore master key is excellent protection for a device that still exists and is *no*
protection you can carry to the next one. The key cannot leave the phone — that is the feature — so
the data it protects dies with the phone.

A vault fixes it by changing what the key *is*:

| | Where the key lives | Survives a new phone | Safe in a backup |
|---|---|---|---|
| App credential stores | This phone's Keystore | No | Not backed up at all |
| **The vault** | **A passphrase in somebody's head** | **Yes** | **Yes — the file is a ciphertext the archive cannot open** |

So the vault file rides in the sandbox archive, deliberately, holding credentials. Copy it out of the
zip and you have what a thief holding the phone would have: a sealed file, and no key.

## What is in the file

```
  magic      "OPSVAULT"          8 bytes
  version                        1 byte
  kdf        PBKDF2-HMAC-SHA256  1 byte
  iterations                     4 bytes      ─┐ the KDF header: the wrapped key's AAD
  salt       len + bytes                      ─┘
  wrap       nonce + the vault key, sealed with the passphrase-derived key   ─┐ the full header:
                                                                             ─┘ the body's AAD
  body       nonce + the document, sealed with the vault key
```

Three choices, and the reasons for each:

- **PBKDF2-HMAC-SHA256, 310,000 rounds.** Argon2id is stronger and would mean shipping a native
  library — and therefore a vault that only a build with that library can open. The whole promise
  here is that the household's secrets outlive the phone, the install and the app version, so the
  format is restricted to what `javax.crypto` has had for a decade. The count is recorded in every
  file, so raising it later does not orphan an older vault.
- **AES-256-GCM, with the header as additional authenticated data.** The header must be readable —
  it says how to derive the key — but it must not be *malleable*. Edit the iteration count down to
  1,000 and the vault stops opening rather than opening cheaply. Splice another vault's wrapped key
  beside this one's body and it opens nothing. Both are tests, not intentions.
- **A random vault key, wrapped by the passphrase.** The document is encrypted with a random 256-bit
  key; the passphrase only ever encrypts *that*. Changing the passphrase re-seals sixty bytes rather
  than re-encrypting every secret; the fingerprint shortcut is a second wrapping of the same key
  rather than a second copy of the vault; and raising the KDF cost is not a migration.

The plaintext is JSON and exists in exactly one place: the running app's memory, between an unlock
and a lock. There is no database — a password manager whose deleted passwords are recoverable from a
write-ahead log is not a password manager — so every change re-seals and rewrites the whole file. For
a household's vault that is kilobytes.

## How another app's credential gets here

Each app keeps its own store exactly as before — the working copy, device-bound, read on every sync,
still excluded from the archive. What is new is two lines around it:

```kotlin
// writing
prefs.edit().putString(tokenKey(connectionId), token).apply()
ManagedSecrets.remember(tokenRef(connectionId), token, label, AppId.FINANCE)

// reading
ManagedSecrets.readThrough(
    ref = tokenRef(connectionId),
    local = { prefs.getString(tokenKey(connectionId), null) },
    rehydrate = { value -> prefs.edit().putString(tokenKey(connectionId), value).apply() }
)
```

`readThrough` is the whole trick. In normal running the local store answers and the vault is never
consulted. On a restored phone the local store is empty, the vault answers, and the value is written
back down — so a restore costs one vault read per credential and then behaves exactly as it did
before.

### The pull is not enough on its own

`readThrough` is *lazy*: it puts a credential back when something asks for it, and only if the vault
happens to be open at that moment. That is almost always right, and "almost" is doing a lot of work.
The first morning on a restored phone is exactly when it isn't:

- the scheduled cloud backup wakes at 2am, reads through to a **shut** vault, gets null, and records
  "paste a signature" on a phone whose household has done nothing wrong;
- Finance's sync runs before anybody has opened anything and reports a bank connection that looks as
  though it was never set up;
- the updater's launch check happens before the passphrase is typed, so the suite cannot say a new
  version exists.

None of those is wrong about what it saw. They are all asking a vault that is shut.

So the vault **pushes as well**. `SecretSource` has a second half — `rehydrate()` — and unlocking
runs it for every registered app:

```kotlin
private suspend fun openedUp() = withContext(Dispatchers.IO) {
    SecretsAccess.flushPending()   // what was written while it was shut goes in first
    SecretSources.rehydrateAll()   // then every app takes back what it is missing
}
```

The order is what makes it safe. A credential refreshed this morning while the vault was shut is in
the pending queue, not in the file; flushing first means step two cannot read a stale value out of
the vault and hand it back to the app that had already replaced it. And `ManagedSecrets.restock`
fills **only an empty slot** — the local store is the working copy and is always at least as new as
the vault's, so a full slot is never touched.

Each app enumerates from **its own data, not from its credential store**, which is the part that is
easy to get backwards: after a restore the credential store is precisely what is empty. Finance asks
the database which connections exist, Citation asks which catalogues exist, and the shell has two
fixed refs. The result is that typing one passphrase brings back every credential in the suite,
without opening a single app — and that every unlock after that finds nothing to do and costs a
preference read per slot.

A credential is filed under a **`SecretRef`**: `finance/usaa/access-token`, `citation/oreilly/library-pin`.
Three segments — the owner's key, which of its things this belongs to (a connection id, or `self`),
and which secret. Refs are filing names, not addresses: nothing dispatches on them, and `:vaultkit`
deliberately does not depend on `:connectkit`, because a vault that can be *called* is a vault with a
surface.

### The owner is not always an app

That first segment used to be an `AppId`, and could not stay one.

The container itself holds a credential: the GitHub token the updater checks releases with. It sat
in `EncryptedSharedPreferences` behind a Keystore key, deliberately outside the archive — word for
word the arrangement Finance had, and word for word the reason this app exists. So a restore brought
back every setting on the Updates tab and not the token, and the suite quietly lost the ability to
tell anybody there was a new version, on a phone that was quite likely the replacement for a lost
one.

The obvious fix is a twelfth `AppId`, and the test that makes it wrong is a good one: `SuiteAppsTest`
asserts that every `AppId` has a tile on the home screen. The shell is not an app on its own home
screen. So the owner widened instead — **`SecretOwner`** is either a hosted app or the container —
and `AppId` went back to meaning what it says:

| | key | shown as | has a tile |
|---|---|---|---|
| A hosted app | its `AppId.key` | Finance, Citation, … | yes |
| The container | `sandbox` | Operations Sandbox | no |

The shell's token is filed at `sandbox/self/github-token`, mirrors on every write, reads through on a
restore, and answers a rebuilt vault like any app. It is shown in the vault list as
"Operations Sandbox — GitHub update token", beside the household's own logins, because a credential
the suite keeps on its own behalf is exactly the sort of thing that should be visible rather than
tactful.

The container holds a second one now, on the same terms: the **shared access signature** its
scheduled backup uploads with, filed at `sandbox/self/azure-backup-sas` and shown as
"Operations Sandbox — Azure backup signature". It is the sharper version of the same failure — a
restore that dropped it would leave a new phone that looks exactly like a working one and has not
backed itself up since the day it replaced the old one. Both are refiled into a rebuilt vault
together; see *Scheduled backups to Azure* in
**[OPERATIONS_SANDBOX.md](OPERATIONS_SANDBOX.md)**.

Equality is on the key alone and the set of keys is closed, for the same reason `AppId.key` is: an
archive written last year names its owners by string. `SecretOwnerTest` asserts that `sandbox`
collides with no app's key, so a future hosted app called "Sandbox" fails a test rather than
silently adopting the container's credentials.

### While the vault is shut

A read returns null, which every caller already handles — it is the same answer they get on a phone
where nothing was ever connected.

A **write** is the interesting case. Finance re-authorising a connection at eight in the morning,
before anybody has opened Secrets, would otherwise put the token in a device-bound store and nowhere
else — exactly the failure this app exists to fix. So writes that cannot land are queued in memory
and flushed on the next unlock. The queue is memory-only and capped: a pending write is a plaintext
secret, and the one place this suite will not put a plaintext secret is a file.

### And somebody is told

The queue above was correct and, for a while, invisible — which amounted to a quieter version of the
failure it prevents. The queue does not survive the process, by design; so a household that happened
not to open Secrets that day lost the mirror silently, on exactly the credentials the vault exists to
carry onto the next phone. Nothing outside this app could see that there was a reason to unlock,
because nothing outside this app could see the vault at all.

So the vault is now **observable**: `SecretsAccess.watch` takes a listener and tells it two things —
what state the vault is in, and how many writes are waiting — whenever either changes. The shape is
LifeOps' completion bus, and so are the rules: facts rather than requests, a listener that throws
cannot break the write it was told about, and a watcher is handed neither the broker, the queued
refs, nor their values. A listener list is the last place to widen a seam whose whole design is *no
listing*.

The sandbox's home screen is the one thing watching. A line appears under the clock —

```
  🔒  3 credentials are waiting for your vault — tap to unlock
```

— and the Secrets tile carries the same count, so it is still there after the line has been read
past. Tapping either opens the unlock screen, which already said how many were waiting; the change
is that somebody now finds out without going and looking.

Two judgements are worth stating, because both are about what is *not* shown:

- **A locked vault on its own is not news.** The vault comes up shut on every process start, always,
  so "Secrets is locked" would be true nearly every time anybody glanced at the home screen — a
  permanent badge is a badge people stop seeing, including on the day it means something. The line
  appears only when a credential is actually stranded.
- **A household with no vault is asked to make one, not to unlock one.** Queued writes land the
  moment a vault is created, so the offer is real; and telling somebody to unlock a vault they have
  never made is an instruction they cannot follow.

Exactly one app can carry a count on that home screen, and the parameter is a plain number rather
than a hook the other ten can start hanging their own on. A home screen where every tile has a red
circle is a home screen where none of them mean anything.

### Where the arrow points

No app depends on `:secrets`. They depend on `:vaultkit` — the contract, the ref, the crypto — and
find the implementation through a registration the sandbox makes at start-up
(`SecretsAccess.register`). A bank client that had to pull in a password manager's UI in order to
read its own token would be the wrong shape.

The seam is three calls and no listing: read what you filed, file it, forget it. That is not a
sandbox — eleven modules in one process cannot be isolated from each other by an interface, and
pretending otherwise would be theatre — it is a *shape*, so that reaching further has to be written
down rather than happening by default.

## Restore is the one place this app disagrees with the rest of the suite

Every other contributor restores by swapping its file in wholesale, because the worst case is
re-fetching something. Here the worst case is every password added since the backup, gone, with
nowhere to fetch them from.

So:

- **No vault on this phone** (a new install, a new phone, a restore of everything at once) — the
  archived vault becomes the vault. This is the case the app was built for, and it is a file copy.
- **A vault already here** — the archived one is *staged* beside it, and Settings offers it as
  something to merge. The merge needs that vault's passphrase, which may be an older one if the
  archive predates a passphrase change, and it reports what it did: added, updated, deleted,
  already the same.

The merge rule is item by item, newest wins, with one asymmetry: **a tombstone only wins if it is
genuinely newer.** Restoring a year-old archive does not resurrect a login deliberately deleted last
week, and merging an archive in which something was deleted does not remove an item edited here
afterwards. There is no field-level merge — one person edits one item, and a password stitched
together from two versions of itself works nowhere.

## A forgotten passphrase: delete, rebuild, refill

Nothing can open the old vault without its passphrase. That is the property everything else rests
on, and a "recovery" that got round it would mean the passphrase never protected anything. So the
answer to a forgotten passphrase is not recovery — it is **deletion followed by a rebuild**, and the
useful observation is that the rebuild does not have to start empty.

The managed credentials were never the vault's only copy. Finance's tokens, Citation's sign-ins and
the library card are all sitting in each app's own encrypted store on this phone, twelve inches from
the person who has just lost their passphrase. So each app implements `SecretSource` — the reverse
of the read-through, where an app answers a vault that lost its copy — and the reset asks all of
them:

```
Unlock screen → "I have forgotten it"
    → what you lose:        everything you typed in. The vault was the only place it was.
    → what comes back:      the credentials Finance and Citation still hold on this phone.
    → what survives anyway: a backup taken before the reset. The old passphrase still opens it,
                            and Settings will merge it if that passphrase ever turns up.
    → new passphrase, twice
    → delete · create · SecretSources.refileAll()
    → "5 credentials filed again — Finance 3, Citation 2"
```

It lives on the **unlock screen**, which is the only place it can: somebody who has forgotten the
passphrase cannot reach Settings. The report is shown on the way in rather than as a toast on the
way past, because "5 came back" and "nothing came back" are very different afternoons and neither
should have to be discovered by scrolling a list.

Two honest limits. On a phone where the apps are *also* empty — a fresh install, a restore in which
the vault was the thing that failed — the refill returns nothing, and the screen says so rather than
reporting a success of zero. And a source that throws is counted as zero while the others carry on:
the day somebody loses their vault is the worst possible day to abandon eight apps' credentials
because the ninth has a bug.

The device shortcut goes with the vault it belonged to, since it held the old vault key.

## Moving in from another password manager

A vault nobody has moved into is a vault nobody uses, and the move is the part that stops people.
Two hundred logins live in Chrome, or in a subscription somebody is paying for mostly because
leaving it would cost a fortnight of evenings. Every commercial importer solves this by asking the
other manager's *server* for them. This one cannot: there is no `INTERNET` permission in this
module's manifest and no HTTP client on its classpath, and that is the property the rest of this
document rests on.

There are two honest answers, and this app has both.

The first is the platform's: **Credential Exchange**, where another credential manager on this phone
hands its contents over after the household authenticates to *it*. No file, no network, and it
carries passkeys — which no export file does.

The second is for everywhere that has not implemented the first: **read the file the other manager
already hands its owner.** Chrome, Edge, Brave, Firefox, Safari and Apple Passwords all export a
CSV; 1Password exports a CSV and its own `.1pux`. Every one of those files is written locally, by
software the household already trusts with these passwords, at their own request.

```
Settings → Bring passwords in → Straight from another app → the system selector → its unlock
Chrome → Export passwords → screen lock → the share sheet → Secrets
Settings → Bring passwords in → which manager → its exact steps → the system picker
    → read           what the file is, and what it holds                (:vaultkit, no vault involved)
    → plan           what each row would do to this vault
    → review         new rows ticked, rows that would overwrite unticked
    → one save       the whole document re-sealed once, for one item or six hundred
    → "now delete the export"
```

Three ways in, and they are in order of preference. **Credential Exchange** is a transfer rather
than a file and carries what no file can — passkeys and second factors included. Failing that, a
browser's export ends in the **share sheet**, so Secrets is on it: export, pick Secrets, review,
done, with the file never saved anywhere. The **picker** is last, for an export made on a computer
and carried across.

### What it reads

| Written by | What arrives |
|---|---|
| Chrome, Edge, Brave | title, address, username, password, note |
| Firefox | address, username, password — and **when the password was last changed** |
| Safari, Apple Passwords | the above, plus the second factor |
| 1Password (CSV) | the above, plus tags and favourites |
| 1Password (`.1pux`) | all of it: cards, notes, identities, wifi keys, custom fields, extra addresses, password history |
| Any manager, over Credential Exchange | all of that **and passkeys**, with no file anywhere |

The reader is column-driven rather than dialect-driven — it looks each thing up by a list of names
in priority order — so a header nobody here has seen still imports as long as it calls its password
column something recognisable. The dialect is worked out separately and used for exactly one thing:
telling the household which of their exports they just picked, so that somebody who meant to import
1Password and is looking at four hundred Chrome logins finds out before they tap the button.

Two of those columns are worth naming because they are the ones a lossy import loses quietly:

- **The second factor** arrives as a *seed*, not as a field. Kept as text it would be a string
  nobody can turn into a code, in an app that can. An `otpauth://` URI and a bare Base32 key are
  both understood, and a seed that will not decode is kept as a field rather than dropped — which
  loses the codes and keeps the secret.
- **When the password last changed**, where the file says so. `VaultAudit` scores an item's age from
  `updatedAt`, so stamping every imported row with the moment of the import would report a vault of
  decade-old passwords as uniformly fresh — on the very screen somebody opens *because* they have
  just imported four hundred passwords they have not looked at in years.

From a `.1pux`, whether a field was **concealed** survives with it, which is what decides whether
this app masks it, keeps it out of the search box and audits it. A CVV that arrived as ordinary text
would be a CVV shown in a list.

### Credential Exchange: the transfer the platform now defines

The best route in is not a file at all. **Credential Exchange** — FIDO's CXP/CXF, wired into Android
through `androidx.credentials.providerevents` — is the sanctioned way for one credential manager to
hand its contents to another on the same device:

```
Import → Straight from another app
    → the system selector          every credential manager on the phone, listed by Android
    → the household picks one      Google Password Manager · 1Password · Bitwarden · …
    → that app authenticates them  its own lock, its own passphrase — this app never sees it
    → CXF payload                  handed back through a content URI, not through a file
    → the same plan and review list as every other import
```

It is better than an exported file in every way that matters, and the differences are not
cosmetic:

| | Exported file | Credential Exchange |
|---|---|---|
| Passkeys | Impossible — no CSV carries a private key | **Carried, and they still sign** |
| Second factors | Only Apple's and 1Password's exports | Carried, as seeds |
| Cards, custom fields, folders | Only a `.1pux` | Carried |
| Plaintext on disk | The whole vault, until somebody deletes it | **None, ever** |
| Authorisation | The other app's export dialog | The other app's own unlock, inside its own UI |

The request asks for every credential type this vault can hold — a provider hands over what it has
of them, and asking for a type nobody exports costs nothing while *not* asking for one is how a
household silently loses their second factors in the one transfer designed to carry them.

A transfer is a **copy, not a move**: the other manager still holds everything until somebody
deletes it there, and until then two apps can offer the same passkey to the same site. That is how
the protocol is specified — a transfer that deleted the far side would be a transfer that loses
everything if it half-fails — and the card says so before it starts.

Every refusal has a sentence rather than an error code, and the most common one is not a failure at
all: a household whose other manager has not implemented this yet is told so, and pointed at the
file routes below, which is why those stay on the screen rather than behind a "more options".

### Passkeys arrive able to sign, which takes one piece of arithmetic

CXF carries a passkey's **private** key as PKCS#8 and not its public one — correctly, since the
public half is a function of the private half and a format carrying both could carry two that
disagree. This app needs it anyway: the public key is what every later check verifies against.

The JDK will not do that step. `KeyFactory` parses a PKCS#8 EC key into a scalar and a curve and
exposes no way to multiply the generator by it, so `Passkeys.publicKeyFrom` does it — about thirty
lines of `BigInteger` arithmetic over the prime field, the textbook double-and-add rather than a
constant-time version, because it runs on a key the household already holds, on their own phone,
once per imported passkey.

Writing that by hand is only defensible because of the test beside it, and there are two. One
generates twenty real key pairs with the platform's own generator, throws away each public half and
recomputes it from the private half, and asserts the bytes come back identical — a mistake in that
arithmetic does not produce a subtly wrong key, it produces a different point. The other takes a
passkey through a whole CXF import and then **signs with it and verifies the signature against the
computed public key**, which is the check a relying party's server runs. An imported passkey that
cannot do that is an account nobody can get into, discovered at the worst possible moment.

### Why the file routes are still here

Because Credential Exchange only answers where it is implemented. For everything else an import
cannot be a connection, and the reason is not effort:

- **Nothing else enumerates another manager's vault.** Credential Manager's everyday surface answers
  one credential at a time, to the app asking, for a sign-in it is performing. Credential Exchange
  is the only sanctioned way to read the rest, and it exists precisely because the ordinary API
  deliberately does not.
- **A manager that has not implemented it cannot be reached.** Google Password Manager's store lives
  inside Play Services' private data and Chrome's inside Chrome's — no exported provider, no intent,
  no OAuth scope. 1Password has no consumer API either, by the same design that makes it worth
  using: the account is unlocked by a passphrase and a Secret Key that never leave the device, and
  the server holds ciphertext it cannot open.
- **And this module holds no network.** No `INTERNET` permission, no HTTP client on the classpath.
  Finance can talk to Plaid because banks are compelled to offer delegated access and because
  Finance made a different bargain for a different job; the vault's bargain is the one that makes it
  safe to put in a backup.

What those places do offer is an export, made locally, behind the device's own lock screen — the
same authorisation a hosted connection would ask for, handed over as a file instead of a token. So
the import names the menu item in each app
([`ImportSource`](../secrets/src/main/java/com/secrets/app/ui/importer/ImportSources.kt)) and is
somewhere the file can be *sent*.

### The share sheet, and the third exported component

Being on the share sheet costs something this module has been careful about, and the manifest argues
it rather than absorbing it. Until now two components were exported and both were behind permissions
the platform holds and nothing installable does — `BIND_AUTOFILL_SERVICE` and
`BIND_CREDENTIAL_PROVIDER_SERVICE` — so "exported" meant *the operating system may bind this*.
`ImportShareActivity` is genuinely wider: any installed app can start it with a file.

It is worth it because of what it removes. Without it, the household's next three steps after
tapping Export are save, lose, and hunt for it in a picker — and the thing they are carrying through
those three steps is a plaintext copy of every password they have, which then lives in Downloads
until something backs it up. Being a share target deletes that file's entire life on disk.

What keeps it narrow is its shape rather than a permission:

| | |
|---|---|
| **It takes and never gives** | No result, no returned data, nothing it can be asked for. An app that sends a file learns nothing — not whether a vault exists, not whether it was unlocked, not what is in it |
| **The vault still has to be opened** | On that screen, by the person holding the phone |
| **Nothing is written without a review** | What arrives is a proposal. The worst a hostile share achieves is a screen offering to add items nobody asked for, declined by pressing Cancel |
| **The payload is hostile input** | Bounded before it is read, turned into data and never into behaviour, refused with a sentence when it is not an export |
| **It closes when the vault does** | What it holds until then is a plan made of plaintext, and the main activity's answer — collapse to the unlock screen — has nowhere to collapse to here |

A share carrying *text* rather than a file is refused outright, and that refusal is tested. It is
the shape the mistake takes: an app offering to send a password list as text is an app putting
passwords through the clipboard and the notification log, and answering it with an import screen
would be accepting that.

### What it refuses, and what it says instead

A file with no password, username or note column is not a password export, and is rejected outright
rather than imported as four hundred empty items. Beyond that, every refusal is a sentence about
what to do next rather than a `false`:

| Picked | Answer |
|---|---|
| A photo, or anything with a NUL byte in it | "That file is not text" |
| A spreadsheet of something else | "A browser's export has a column called password, username or note; this one has none of them" |
| A zip that is not a `.1pux` | "The file to pick is the .1pux itself" |
| **This app's own sealed vault, out of a backup** | "Restore the archive and Settings will offer to merge it — that needs the passphrase it was sealed with, which this screen does not ask for" |

The last one is the reason the check is in that order. A `.vault` file has a home in this app and it
is not the import screen; "nothing to import" would send somebody away from the screen that could
actually have opened it.

Items the other manager has **trashed or archived** are left where they were put and reported by
name. So are attachments: a `.1pux` carries files, and a vault whose sealed body can hold a scanned
passport is a vault whose every save rewrites several megabytes of ciphertext. Nothing is left out
silently — "214 read, 198 imported" with no account of the other sixteen is how an importer earns a
reputation for losing things.

### Why it shows a list before it writes anything

Because the household knows the one thing this app cannot work out: **which copy is newer.**

A row is matched against the vault on its address and username together — `AutofillMatch.hostOf`
does the address half, so `https://www.bank.com/login` and `bank.com` are one place, and it is the
same normalisation autofill matches on rather than a second answer to the same question. An item
with no address is matched on its title, which is all a secure note has. A mirrored credential is
matched against *nothing*: it belongs to an app, it is addressed by a `SecretRef`, and a CSV row
that happens to carry the same title must never be allowed to overwrite a bank token.

### Which of two passwords is the current one

That is the hard question of any import, and the one that arrives in bulk the day somebody has both
Google's copy and 1Password's and they have drifted apart. Overwriting everything is wrong half the
time; asking four hundred times is not a feature. So the plan **resolves what it can from evidence**,
in this order, and says on each row which piece of evidence decided it:

1. **It is not a different password at all** — the import brings a passkey or a second factor for an
   account already here. Nothing to weigh.
2. **This vault already knows the incoming password, as an old one.** It is in the item's history,
   which means it was replaced *here*. No date outranks that.
3. **The import knows this vault's password as an old one** — its own history carries what is
   current here, so its copy is the later one.
4. **The dates.** Where both sides say when the password was last changed, the later one is current.
   A Credential Exchange transfer carries `modifiedAt`; so do a 1Password `.1pux` and a Firefox CSV.
5. **Otherwise nobody knows**, and the plan says so instead of guessing.

That gives the verdicts, and the defaults follow from them:

| Verdict | What it means | What taking it does | Ticked? |
|---|---|---|---|
| **New** | Nothing here is filed under this account | Adds it | Yes |
| **A passkey or second factor to add** | Same password, plus something this vault lacks | Adds it, overwrites nothing | Yes |
| **Newer than the copy here** | Dated later, or the import lists this vault's password as old | Becomes the password; the old one is kept in history | Yes |
| **Older than the copy here** | The other app is behind | **Nothing is replaced** — the incoming one is filed as a *previous* password | Yes |
| **Two passwords, nothing to tell them apart** | Neither side is dated | Replaces, if you tick it | **No** |
| **Already in the vault** | Same account, same password — or one already recorded as previous | Nothing | Not offered |

Two of those rows are worth pausing on.

**Older is not a failure, it is a find.** A browser that has not been opened since March still holds
what the password was then, and that is exactly what a password history is for: the account somebody
gets locked out of is the one whose password changed on one device and not the other. So the older
copy is kept as a previous password, the current one is not touched, and — deliberately —
`updatedAt` is not moved either, because the password did not change today and the audit reads that
field to decide what is stale.

**An undated export does not get to claim it is newest.** The readers leave `updatedAt` at zero when
the file said nothing, rather than stamping the moment of the import. A reader that stamped today
would make every CSV newer than everything in the vault, and every import a silent overwrite of
every password changed since. That is one of the tests.

Nothing in an import happens on a default that costs a working password: the only unticked row is
the only one where being wrong does.

Taking one anyway is safe in the way that matters: the item **keeps its identity**. Its id, its
tags, its extra fields, its favourite star, its second factor and everything else somebody did to it
here all survive; what the import supplies is the password, plus anything the vault's copy was
missing. And the replaced password is kept, because the change goes through `VaultDocument.upsert`
like every other one — which is exactly why that rule lives there rather than at each call site.

No password is shown on that screen, incoming or outgoing. It is a list of *accounts*: a screen
showing two hundred passwords in the clear would be the one place in this app where a shoulder is
worth more than the passphrase.

### The export file is the dangerous part, and it is said twice

For as long as it exists, that file is every password the household has, in plain text, in Downloads
— readable by anything with storage access, and carried into whatever backs that folder up. This app
cannot delete it: it is not this app's file, and a vault that could reach into shared storage would
be a vault worth being nervous about. So the screen says so before the picker opens and again on the
way out, and points at **Check** on the way past, since an import is the one moment a vault gains
hundreds of passwords nobody has looked at in years.

## One row per place

An import changes what the list has to do. A vault somebody typed by hand has one item per site; a
vault that has just taken four hundred logins out of a browser has three for the bank — the one they
use, the one from the old email address, and the one the browser saved against the mobile site — and
a flat list of those is a list nobody can read.

So the list groups by site. Everything filed under one address collapses to one row saying how many
sign-ins are behind it, shut by default, opening in place. A site with one credential is **not** a
group and never looks like one, because a folder containing one thing is a tap for no reason.

It is not folders, and the difference is the whole design:

- **Nothing is filed.** The grouping is computed from the item's own address every time the list is
  drawn, through the same `AutofillMatch.hostOf` that decides what autofill will offer — so a site
  is one place here exactly when it is one place there. There is nothing to maintain, nothing to go
  stale, nothing to restore, and nothing to migrate.
- **Nothing moves.** A group takes the position its *best* member would have had, so a starred login
  does not disappear into a folder halfway down the alphabet by acquiring a neighbour.
- **A group is named after what its items agree on.** Three logins all called *Bank* make a row
  called Bank with the address underneath; where the titles disagree, the address is the label,
  because it is the only thing they actually have in common.
- **Searching flattens it.** A search is a different act from browsing — the answer to "where did I
  put the council login" is that login, not a folder it might be in — so a query returns items, and
  a match is never hidden inside a shut group.
- **An item with no address is untouched.** A secure note, the wifi password, a mirrored credential
  filed under a `SecretRef`: no site, no group, same row it always had. That is most of a young
  vault and all of the *From apps* filter, which is why the grouping has to be invisible when it has
  nothing to do.

## The device shortcut, and why it is not a contradiction

The argument above is that a key bound to one phone's hardware dies with that phone. The fingerprint
unlock uses exactly that. The distinction that makes both true is between a **root of trust** and a
**shortcut**:

- The passphrase is the root. It is the only thing that opens the archived vault, it is not on the
  phone, and losing the phone does not lose it.
- The device unlock is a shortcut to a vault this phone already has. It is opt-in, refused outright
  on a phone with no lock screen, gated behind the device credential, and **excluded from the
  backup** — by a file name (`secure_secrets_device`) that fails the contributor's prefix test, the
  same trick Finance uses. A backup carrying both the sealed vault and a device-unwrappable copy of
  its key would be a backup carrying the vault in plaintext.

Revoking it, or losing the phone, costs nothing: type the passphrase.

## The second factor

A time-based one-time password is the rare second factor that is *arithmetic*: HMAC over a counter
taken from the clock, truncated to six digits (RFC 6238, and RFC 4226 underneath it). There is no
server to ask, no account to have, and nothing to sync — everything it needs is in `javax.crypto`,
which is the same restriction the envelope accepts and for the same reason.

So the app that promises it cannot phone anybody turns out to be the natural home for this. What it
replaces is a separate authenticator app whose seeds live in *its* store, die with the phone, and
are the one credential nobody can reissue without a support line — which is the failure this whole
module exists to fix, arriving a second time in a different costume.

The objection worth answering is that keeping both factors in one place collapses two into one. It
is a real argument, and it is an argument about *where the vault is* rather than about what is in it.
Two-factor authentication defends against somebody who has the password and is not here: a leaked
database, a reused password, a phishing page. None of those gets them this file, and somebody who
does have this file and its passphrase has the password anyway.

```
  Second factor                             Monzo · me@example.com
  138 249                             17s   [copy]
  ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░░░
  From this phone's clock.
```

Three decisions, and the reasons:

- **The seed is stored; the code never is.** The code is a pure function of the seed and the clock,
  so writing it down would mean putting a secret on disk in order to save one HMAC. The seed is
  treated as what it is — never searched, never in the audit's reuse comparison, gone from a
  tombstone like everything else, and never shown on screen at all. The only reason to look at a
  seed is to move it to another authenticator, and the site that issued it still has the QR code.
- **The code is *not* masked, unlike every other secret here.** It exists to be read off the screen
  and typed into something else within half a minute, it is worthless the moment it expires, and a
  reveal button in front of it would be a tap between somebody and the only thing they came for.
  Copying it clears the clipboard when the code stops working rather than on the vault's general
  timer.
- **Two ways in, and neither is the other's fallback.** Scanning is what a site expects — it puts a
  QR code on screen and assumes an authenticator is pointed at it — and it is the only one that
  does not involve transcribing thirty-two characters of Base32 without a typo. Typing still works
  when the camera is declined, when the code is on the same screen as the scanner, or when the seed
  arrived by email; every site that shows a QR code offers the same key as text behind a "can't scan
  it?" link. The field takes either an `otpauth://` URI or a bare Base32 key, in whatever case and
  spacing it was printed in.

  The camera is the one permission this module now holds, and the paragraph it replaced said it
  never would. What changed the answer is what is *in* that QR code: a seed is the one credential a
  household cannot reissue without a support line, and an app that makes it hard to file is an app
  they file it somewhere else instead. The cost is bounded and stated — the scanner opens on a tap,
  records nothing, keeps no image, decodes in this process with zxing's plain-Java decoder, and runs
  behind `FLAG_SECURE` because what is in front of the lens is a picture of a seed. The container
  already held `CAMERA` for People's partner pairing, so the app's permission set is unchanged;
  what changed is that this module is now one of the two asking.

Two honest limits. **The clock is the phone's** — a phone thirty seconds out of step produces codes
a site rejects, and nothing here can tell that apart from a wrong seed, because asking a time server
would mean the network permission this module does not have. The screen says so rather than leaving
it to be worked out. And **counter-based `hotp` seeds are refused** rather than half-supported: the
counter would advance every time a code was *looked at*, so opening the vault would desynchronise
the second factor, which is a worse failure than not holding it.

## The password you had before this one

The most common way to lose an account is not forgetting a password — it is **changing** one. The
form said it saved and stored something else; the change never committed; the tablet in the kitchen
is still signed in on the old one and will ask for it the next time somebody opens it. Every one of
those is ten seconds' work for a person who can see what the password was this morning, and an
account-recovery phone call for a person who cannot.

So an item keeps what its password used to be, newest first, capped at ten. Recording happens in
`VaultDocument.upsert` rather than at the editor, because that is the one funnel every change goes
through and a rule written at a call site is a rule the next call site forgets. Four cases record
nothing, each because there was no *replacement*: a new item, one coming back from a tombstone, a
password that did not change, and one that was blank — filling in the empty secret on a stub started
last year should not file an empty string as a password somebody once used.

This is the one place the argument against a database gets re-examined, because it sounds like the
same thing. It is not. The objection to a write-ahead log was never "old passwords exist" — it was
that they exist where nobody decided to put them and nobody can get them out. These are inside the
sealed body, bounded, shown on the screen that owns the item, masked like any other secret, revealed
one at a time, and thrown away from that screen by a button that says so — which, like every other
edit here, takes effect on Save rather than under the finger.

**The mirrored credentials do not get one.** An app's access token is dead the moment it rotates, so
keeping it would be storing a secret that opens nothing; worse, those rotate on somebody else's
schedule rather than when a person decides something, so a history of them would be an unbounded
churn of useless plaintext inside the file. That is enforced in `keepingReplaced` rather than left to
each writer to remember.

## Filling it in elsewhere

A vault whose only way out is the clipboard is a vault that spends its day in the clipboard. The
copy-reveal-switch-paste dance is four steps, it puts the password in a buffer other apps can read,
and the app's own defaults already bend around it — `lockOnLeave` is off by default precisely
because locking on every app switch would make that dance five steps. Autofill is the answer to the
thing those defaults were apologising for.

It costs this module the other half of its old manifest promise. An `AutofillService` is bound by
the system, and the system can only bind something exported, so "every component here is
`exported="false"`" stopped being true. What makes that narrower than it sounds is the permission
on the declaration: `BIND_AUTOFILL_SERVICE` is held by the platform and by nothing installable, so
exported here means *the operating system may bind this* and still means *no app on this phone may*.
It is inert until somebody picks Secrets as their autofill service in a system settings screen this
app does not control, and the screen it authenticates through is not exported at all — the system
opens that one through a `PendingIntent` this app created, which carries this app's identity rather
than the caller's.

### The part with the danger in it

Everything about autofill is plumbing except one question: *whose password may be offered to what?*
Answer it wrong and the vault has typed the bank password into whatever was pretending to be the
bank, silently, on a phone whose owner has no way of noticing. So the answer lives in
`AutofillMatch` — framework-free, in `:vaultkit`, under tests that are mostly about refusals, for
exactly the reason the crypto lives there.

Four rules:

- **A managed credential is never offered to anything.** Finance's access token is not a login, no
  sign-in page wants it, and the only thing filling one in could achieve is handing a bank token to
  a form. Mirrored items are filtered out before anything else is considered.
- **A match must be earned.** The asker names itself — a web domain for a browser, a package name
  for an app — and an item is a candidate only if its own address says it belongs there. Nothing is
  offered on a guess about the title, which means an item saved with no address is never offered
  automatically, and that is the intended trade.
- **A subdomain matches its parent and a lookalike does not.** `login.bank.com` may be filled from
  an item filed under `bank.com`, because that is one site. `bank.com.evil.example` may not, because
  the suffix test is on label boundaries rather than on characters. That one line is the difference,
  and it has a test to itself.
- **No match means no rows.** Not "show the whole vault and let them pick" from inside the dropdown,
  which would abandon rule two. What an unrecognised form gets is a single entry that opens the
  vault's own list to be searched, where the person picks the item themselves — a different and much
  better-founded act than a service deciding on their behalf.

Two more, which are about this app rather than about matching. It will not fill **its own package**:
the unlock screen's passphrase box is a password field like any other, and a vault that offers to
fill its own passphrase is a vault with its key inside it. And a **locked vault stays locked** — it
cannot know whether it holds anything for the form in front of it, because that is what being locked
means, so it offers a way in rather than an answer, and the entry that says so opens the unlock
screen and comes back with the real datasets.

### Reading somebody else's form

Nothing in the view tree the system hands over is trustworthy or consistent. Some apps declare
`autofillHints`, some declare an HTML input type, some a native input-type flag, and plenty declare
a field called `et_pw_2` and nothing else. `AutofillForm.classify` reads all four, in order of how
much the asker committed to, and the guesswork tier is last for the obvious reason.

One decision in there is worth stating on its own: a **one-time-code field is recognised when it is
declared and never guessed at**. "Code" appears on postcode, area code, country code and discount
code fields, and a second factor pasted into a discount box is a code *spent* — they work once. The
short abbreviations that are guessed at are matched on word boundaries rather than as substrings,
which is what keeps `pw` from finding the middle of `upward`.

### Saving

A sign-in typed into a form by hand is offered to the vault, and only ever **added**. A form filled
with a password the vault already holds is not a change worth recording; a form filled with a
different one is more likely a second account than a rotation somebody wanted overwritten, and
guessing wrongly would silently replace a working password. There is no queue for a save that
arrives while the vault is shut, either — the pending-write queue exists for an app's own mirrored
credential, which that app still holds and can re-file, whereas a sign-in typed into somebody else's
form exists nowhere else, and holding it in memory until an unlock that may never come would be
pretending to have saved it. It says so and declines.

## Passkeys

A passkey is a key pair. The site keeps the public half; the private half **is** the credential, and
where it lives decides what happens the day the phone does not come back.

A platform passkey lives in the phone's hardware-backed keystore. That is excellent protection and it
is the same hardware binding this entire module exists to work around — the key cannot leave, so it
dies with the device unless somebody else's cloud is holding a copy. Kept here, it lives in the
sealed document, under a passphrase that is in somebody's head rather than in a TEE, in a file that
rides the sandbox archive. The same bargain as everything else in this vault, applied to an
unusually unforgiving credential: a passkey has no forgotten-password link behind it, so losing one
means a site's account recovery flow, per site, with a support queue at the end of it.

The authenticator says so rather than leaving it to be inferred. The **backup eligible** and
**backed up** flags are both set in every authenticator data this app produces, because both are
literally true here, and a relying party reads them to decide whether to keep offering a password as
a fallback. Setting them falsely in either direction would make that decision wrong.

### The feature that moved the suite's floor

A third-party app can hold passkeys **only** through Credential Manager's provider API, and that API
is Android 14. There is no earlier route — not a hidden one, not a worse one.

Everything else in this suite ran happily on API 26, so for a while this shipped as the one feature
with a floor above the app's own: gated behind a version check, explained on the settings screen,
absent on older phones. That was the worse of the two options. A vault that keeps passkeys on some
phones and apologises on others is harder to own than one that asks for a phone from 2023 — the
apology is a thing the household has to carry around in their head, and it is exactly the sort of
half-present feature nobody trusts with an account they cannot recover.

So the whole suite moved to `minSdk` 34 and the gate came out. Not higher: nothing here uses an API
above 34, so 35 or 36 would buy no code and only narrow who can install. The suite compiles and
targets **36** (Android 16), which is a different question — what it is built against, rather than
what it will run on.

### What is in the pure module, and why almost all of it is

Everything except the service and the screen: the CBOR encoder, the COSE key, the authenticator
data, the attestation object, the client data, the signature, and the parsing of the request the
site sent. None of it needs a phone, and the reason it is worth insisting on that is the test it
buys — the tests beside it **build a registration, produce an assertion against it, and verify the
signature with the public key the registration handed over**, which is precisely the check a relying
party's server performs. A passkey that is subtly wrong produces a sign-in that fails on somebody's
phone for a reason no log will explain, and that is not a thing to find out on a device.

Four decisions live in there, and each is a place a plausible implementation goes wrong:

- **ES256 only.** ECDSA over P-256, COSE `-7`: what every relying party accepts, and what
  `java.security` has had for a decade. The same rule the envelope's key derivation follows, for the
  same reason — a credential that only opens on a build with a particular native library is not one
  a household can carry to their next phone. A site whose `pubKeyCredParams` leaves ES256 out is
  **refused**, because issuing a key of an algorithm it did not ask for produces a credential it
  rejects at first use, discovered much later and diagnosable by nobody.
- **The AAGUID is all zeroes.** It identifies the make and model of an authenticator, and vendors
  register one. This app has not, so the honest value is the one reserved for not saying. Inventing
  sixteen bytes would be claiming an identity nobody issued; borrowing another vendor's would be
  worse.
- **Attestation is `none`,** and that is the right answer rather than a shortcut. Attestation is a
  signed claim about which hardware holds the key, made by a certificate a manufacturer issued. A
  vault whose whole purpose is that the key is *not* bound to hardware has nothing true to say
  there, and every self-signed alternative amounts to asserting your own trustworthiness.
- **The signature counter is fixed at zero.** The counter exists so a site can notice a *cloned*
  authenticator: one that goes backwards means two copies of a key that should exist once. A
  credential held in a vault that deliberately travels in a backup may legitimately be used from two
  phones, so an incrementing counter would report a clone every time somebody restored. A counter
  that never moves says "this authenticator does not keep one", which is true, rather than something
  false that happens to increase.

And one detail that is worth a line because it fails rarely enough to be a mystery: the EC
coordinates in the COSE key are padded to exactly 32 bytes. A `BigInteger` drops leading zeroes, so
a coordinate whose top byte happens to be zero is 31 bytes and a rejected credential — about one
registration in two hundred and fifty-six.

### Who is asking

A web sign-in has an origin like `https://bank.com`. A native app has no URL, so WebAuthn names it by
the hash of the certificate its APK was signed with — `android:apk-key-hash:…` — which a relying
party matches against its own `assetlinks.json`. That check is what stops an app which merely claims
to be the bank from being handed the bank's passkey, so this app does not get to skip building it
honestly: the certificate comes from the platform's own `SigningInfo` for the caller, never from
anything the caller said about itself.

A **privileged** caller — a browser the platform vouches for — is the other case. It has already
built the client data for the page it is showing and hands over only the hash; this app signs that
and never sees the JSON, which is correct, because the origin in it is the page's and only the
browser can honestly state it.

### Matching needs no judgement here

`AutofillMatch` is a careful file because a password has no idea which site it belongs to. A passkey
does: it names its relying party, the request names its relying party, and they either agree or they
do not. So the rule is **exact match on the rpId** — not by subdomain, not by anything clever, since
a signature is scoped to the rpId it was created under and a credential offered to a different one
produces a signature that site rejects. The looseness that is a convenience for a password would be
a broken sign-in here. An empty `allowCredentials` means "whatever you hold for this site", which is
the discoverable-credential flow passkeys are normally used in.

Two more refusals, in the screen rather than the service. A site's `excludeCredentials` is honoured,
so a second passkey is not quietly made for an account that already has one. And the request is
**re-read** when the credential is used rather than trusted from the entry that was tapped: an entry
and a request that disagree would mean signing a challenge for a site this credential does not
belong to.

### Nothing to reveal, nothing to copy

The item screen shows a passkey and does not offer to edit it. Every other secret here is a text box
because every other secret is something a person typed and may need to retype; a private key is not
a value anybody transcribes, and retyping it is not a thing that can succeed. There is nowhere to
paste it either — a passkey is used by signing a challenge, which is what the system's own dialog
asks this app to do. Putting it on screen would be offering a value that cannot be used and can only
leak. What is shown is what somebody needs to recognise it by, and the one load-bearing fact:
deleting it ends the ability to sign in with it, and there is no copy anywhere to fall back on.

## Three features, one format version, counted honestly

Each of the additions above puts something in the document that a build without it would silently
drop — Gson keeps what it knows and discards the rest — so the document's own version has climbed
twice: second factors and password history took it to 2, passkeys to 3. What it does *not* do is
move on every vault.

`VaultDocument.stamped()` computes the version from the contents rather than asserting it, **per
feature rather than per release**:

| What the vault holds | Version | Which builds can still write it |
|---|---|---|
| Logins, cards, notes | 1 | every build there has ever been |
| A second factor, or a replaced password | 2 | every build since those shipped |
| A passkey | 3 | this one |

A household that never makes a passkey keeps a file the previous build can open **and edit**.
Stamping every vault at the newest number on the day a feature shipped would have locked those
households out of their own vault on any phone running the older build, in exchange for nothing.

It only ever goes up. Deleting the last seed does not walk the version back down — a version that
flapped would be a version that meant nothing — and a document from a future build keeps whatever
version it arrived with, which is what keeps `VaultStore.mutateBlocking`'s refusal to write it
working.

## What the app will not do

- **It cannot reach the network.** No `INTERNET` permission in its manifest, no HTTP client on its
  classpath. No sync, no account, no telemetry — and no breach check, not even the k-anonymous kind
  that sends a hash prefix to somebody else's server. The audit says weak, reused, old and empty, and
  those are the things that can be known without telling anyone anything. The second factor is the
  proof this is a restriction rather than a shortfall: it is arithmetic over a seed and a clock, so
  it works here exactly as well as it would anywhere.
- **It imports from a file, never from an account.** Moving in from a browser or from 1Password
  reads the export those apps already hand their owner — on this phone, with no account, no sign-in
  and nothing asked of anybody's server, because there is nothing here that could ask. See *Moving
  in from a browser, or from 1Password*.
- **It uses the camera for one thing, on a tap.** Reading the QR code a site shows when it hands
  over a second-factor seed, and nothing else — nothing recorded, no image kept, decoded in this
  process by zxing's plain-Java decoder. Every screen that scans also takes the key as text, so
  declining the permission costs convenience and no capability. See *The second factor*.
- **It cannot recover a forgotten passphrase.** There is no reset link, no support address, no copy
  anybody else holds. The screen that creates a vault says so before it makes one. What it *can* do
  is start again and refill from the other apps — see below, and note that this is a rebuild rather
  than a recovery.
- **It does not search secrets.** The search box reads titles, usernames, addresses, notes, tags and
  refs — never the password, never a password the item used to have, and never a second-factor
  seed. Typing a password into a search box to find where you used it is a
  reasonable thing to want and a terrible thing to support — it puts the password into a text field,
  an input method's learned-word store, and whatever the keyboard app does with what it sees. The
  audit answers that question without anybody typing anything.
- **It serves no connection routes.** Three apps answer on the suite's address contract; this one
  does not.
- **It exports three components: two only the operating system can reach, and one that takes a file
  and gives nothing back.** The autofill service behind `BIND_AUTOFILL_SERVICE` and the credential
  provider service behind `BIND_CREDENTIAL_PROVIDER_SERVICE` — permissions the platform holds and
  nothing installable does. Both are inert until the household picks this app for that job in system
  settings, and neither can hand over a secret while the vault is shut: what they return then is a
  way in, never an answer. The third is the share-sheet import, which any app can start and none can
  learn anything from — it returns no result, requires the vault to be opened on the spot, writes
  nothing without a review, and closes when the vault shuts. Every other component is still
  `exported="false"`, the two screens those services authenticate through included. The list of
  exceptions is meant to stay short enough to read in one sitting. See *Filling it in elsewhere*,
  *Passkeys* and *The share sheet, and the third exported component*.
- **It takes no screenshots of itself.** `FLAG_SECURE` on every screen this module owns — the main
  activity, the screen autofill authenticates through, and the scanner, which is a subclass of the
  scanning library's activity existing for that one line. The recents thumbnail the system writes to
  disk is never a picture of the vault, and never a picture of a seed's QR code either.

## The generator

Two shapes, because they are for two different things: a **character password** for the sites you
will never type by hand, and a **passphrase** of real words for the handful you do — a phone unlock,
a wifi key read to a guest, and this vault's own master passphrase.

Both report their entropy, which is the only honest way to compare sixteen jumbled characters with
five English words. The word list is 1,040 words, so a word is worth just over ten bits: five words
is about fifty, six about sixty. The list is public — it is compiled into the app — and that costs
nothing, because the entropy is in the dice rather than in the vocabulary.

## Tests

`:vaultkit` — 274 JVM tests. The ones that matter are the failures: wrong passphrase, flipped bit in
the body, a header edited to claim a cheaper KDF, a wrapped key spliced from another vault, a
truncated file, a version from the future, and an old archive merged over a newer vault. Plus the
owner (`sandbox` collides with no app; every owner round-trips through its key; a key from a newer
build is nobody rather than somebody invented) and the watcher (told on registration, told when a
write strands, told when a flush clears it, silent after unwatching, and a watcher that throws
cannot take a write down with it).

The second factor is checked against **RFC 6238's own test vectors**, all three hashes, and that is
the only kind of test worth having for it: a generator that is subtly wrong produces six plausible
digits that no site accepts, and no amount of reading the code catches that. If those pass, the
arithmetic agrees with every authenticator app in the world. Around them: that six digits is the
eight-digit code's *last* six rather than its first, that a leading zero survives being formatted,
that a code holds for its period and changes at the boundary, that Base32 is lenient about spacing
and case and strict about content, that an `otpauth://` URI's issuer, digits, period and hash are
taken from it rather than guessed, that a `+` in an account name survives (which `URLDecoder` would
not manage), and that an `hotp` URI is refused rather than half-supported.

The history rules are tested as rules rather than as a feature: a changed password is kept, an
unchanged one records nothing, a blank one is not a replacement, a mirrored credential never
accumulates any, the cap holds at ten with the newest first, and a tombstone carries neither a
previous password nor a seed. Two more on the search box, which is where a mistake would be
invisible: neither a password used last year nor a seed can be matched by typing it. And the format
version — that a vault using neither feature stays at 1 and remains writable by the older build,
that either feature moves it to 2, that it never walks back down, and that a document from a future
build is not quietly demoted into one this build would strip. And two on the audit: an item that
exists for its second factor is not reported as an abandoned stub — the codes *are* what it holds —
and a seed is never compared against a password for reuse.

The Credential Exchange payload is tested as a document, since that is what it is: a login with a
second factor and custom fields, a card, a wifi network, a note, an API key, the other manager's
folders becoming tags, a file left behind by name, a malformed item that does not take the rest of
the transfer with it, and a credential type this reader has never heard of arriving as its values
rather than vanishing. Two more are the ones with teeth. Twenty real key pairs have their public
half thrown away and recomputed from the private half, asserting the bytes come back identical; and
a passkey taken through a whole import then **signs, and its signature verifies against the public
key that was computed rather than received** — the check a relying party's server runs.

The resolution rules are tested as rules: the later date wins and the password it displaced is kept;
the earlier one is filed as a previous password and changes nothing else, `updatedAt` included; a
password already in the item's history is recognised as the older one with no dates at all, and so
is an import whose own history holds what this vault currently has; the same stale export imported
twice does not stack duplicates; a transfer carrying a passkey for an account held as a password
adds it without touching the password; and an undated export does not get to claim it is the newest
copy, which is the bug that would have made every import a silent overwrite.

The grouping is tested mostly on what it refuses to do: one login at a site is not a folder, an item
with no address is left exactly where it was, a subdomain is its own place, a deleted item is in no
row, and a group sorts where its best member would have — so a favourite does not lose its place by
gaining a neighbour. Four more in `:secrets` cover what only exists on screen: a site is shut until
it is opened, a search flattens the list so a match is never inside a shut folder, and a site that
has been filtered away stops being recorded as open.

The import is also tested on files shaped like the real exports — a Chrome header, a Firefox one with its
nine columns and its timestamps, an Apple one with a second factor, a 1Password CSV with an archived
row in it, and a `.1pux` document with a card, a note, a router, custom fields and a password
history. The CSV reader is tested on the cells that break the afternoon version of it: a note with a
comma, a note with a *newline*, a doubled quote, three line endings, a byte-order mark, and a
semicolon file whose delimiter has to be sniffed without one note full of semicolons outvoting it.
The plan is tested against a vault that is not empty — the same export imported twice adds nothing,
a different password is a change rather than a duplicate, taking one keeps the item's tags and
fields and records the password it replaced, two logins at one site stay two items, and a mirrored
credential is never what a row matches. And the refusals: a spreadsheet of something else, a photo,
a zip of holiday pictures, and this app's own sealed vault, which is sent to the screen that can
actually open it.

`AutofillMatch` is tested almost entirely on what it **refuses**, because that is where the damage
is: a lookalike domain gets nothing, a mirrored credential gets nothing however well its address
matches, an item with no address is never offered however well its title reads, an asker that names
itself as nothing gets nothing, and a browser is not filled from the item filed under the browser.
Then the ranking — an exact domain over a parent, a parent over a package read backwards, a
favourite over a name — and the two primitives underneath: that the subdomain test is on label
boundaries, and that a package match is by label rather than by string prefix, so `com.monzonian`
does not collect `monzo.com`'s password.

`:secrets` — 57 Robolectric/JVM tests: the file store, the lock states, the broker, what the archive
does and does not contain, both restore paths, the reset (including that it cannot bring back what
only the old vault held), and the one this app exists for — a credential mirrored on a phone that no
longer exists, read back on the one that replaced it. Three of them run the new features through the
real sealed file rather than through the document alone: a seed and a replaced password survive the
seal and the reopen and still produce the right codes, a credential rotated three times by an app
leaves no trail of dead tokens, and a vault that uses neither feature is still stamped version 1 on
disk. Five more take the import the rest of the way: a picked file read through a `ContentResolver`,
a plan that has written nothing yet, a confirmation that survives a lock and a reopen because it
went into the sealed file rather than into memory, an empty selection that leaves the vault exactly
as it was, and a vault that shut while the file was being read — which says so rather than losing
the passwords quietly. Four more guard the share sheet, which is the one component any app can
start: a shared file is the file, an intent that is not a share is nothing, and a share carrying a
password list as *text* is nothing either.

The autofill field reader is tested on the JVM without Robolectric, because `AutofillForm.classify`
takes its signals as plain values: a declared hint is believed and beats the input type under it,
both Android's vocabulary and the web's are understood, a number field is not a password because its
variation bits collide with one, a one-time-code field is recognised when declared and never guessed
at from a field called `discount_code`, and `pw` is found in `et_pw` and not in `upward_scroll`.

The passkey tests are the ones that would be worthless on a device and are worth a great deal on the
JVM. The centre of them builds a registration, produces an assertion against it, and **verifies the
signature with the public key the registration handed over** — the same check a relying party's
server runs — and then does it again over different client data to prove a replayed signature fails,
which is the attack the whole protocol is about. Around that: the attestation object says `none` and
carries the authenticator data; the authenticator data is laid out as the spec lays it out, with the
rpId hash over the relying party and nothing else, with backup-eligible and backed-up set, with a
zero counter and no AAGUID claimed; an assertion carries no attested credential data; the public key
the site is given is the one the vault kept; a coordinate with a leading zero is still thirty-two
bytes; and a site that will not take ES256 is refused rather than issued something it will reject.
The CBOR encoder is checked against RFC 8949's own example bytes and round-tripped through a reader
that lives in the test source set for exactly that purpose — so the encoder is checked against
something other than itself.

`:secrets`' list ends where the app's argument does: a passkey made on one phone, sealed, and read
back on the phone that replaced it — where it still signs, and the signature still verifies against
the public key the site was given by a device that no longer exists.

`:finance` — 14 more, on its half of the seam: which refs it uses, that a write reaches both stores,
that a read prefers the local copy, that a restore rehydrates, that a refill hands over everything
it holds, and that with no vault installed the app behaves exactly as it did before.

`:secrets`' list now includes the push: that unlocking hands every app back what its own store lost,
that it never writes over a credential the phone already has, and that making a vault takes the same
round.

`:app` — 17, and the same list from the shell's side: the updater's token reaches both stores, the
vault row says *Operations Sandbox* rather than an app's name, a token filed on one phone is read
back on the phone that replaced it, a write while the vault is shut is queued rather than lost, a
rebuilt vault gets the token filed again, and a phone with no vault behaves exactly as it did
before. Plus what the home screen says, and — the half that matters more — when it says nothing.
