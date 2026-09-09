# Secrets — one vault, and the credentials that survive a restore

Secrets is the suite's password manager. It keeps the household's own logins, cards, licence keys
and notes; and it keeps, under the same lock, **every credential the other apps hold** — Finance's
Plaid keys and bank access tokens, Citation's catalogue sign-ins and library card — so that restoring
a backup onto a new phone does not throw them away.

It is a hosted library module inside the Operations Sandbox container (`:app`), a peer to LifeOps,
Citation, Logistics, Advisor, Health, People, Project, Maintenance, Repository and Finance. Its
framework-independent core — the file format, the key derivation, the document, the generator, the
audit, the merge — is `:vaultkit`, pure JVM and unit-tested without a device.

```
Operations Sandbox  →  Secrets  →  Unlock     (or make a vault, the first time)
                                →  Vault      (everything, searchable)  →  one item
                                →  Generate   (characters, or words)
                                →  Check      (weak, reused, old, empty)
                                →  Settings   (auto-lock, passphrase, and finishing a restore)
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

A credential is filed under a **`SecretRef`**: `finance/usaa/access-token`, `citation/oreilly/library-pin`.
Three segments — the owning app's `AppId` key, which of its things this belongs to (a connection id,
or `self`), and which secret. Refs are filing names, not addresses: nothing dispatches on them, and
`:vaultkit` deliberately does not depend on `:connectkit`, because a vault that can be *called* is a
vault with a surface.

### While the vault is shut

A read returns null, which every caller already handles — it is the same answer they get on a phone
where nothing was ever connected.

A **write** is the interesting case. Finance re-authorising a connection at eight in the morning,
before anybody has opened Secrets, would otherwise put the token in a device-bound store and nowhere
else — exactly the failure this app exists to fix. So writes that cannot land are queued in memory
and flushed on the next unlock. The queue is memory-only and capped: a pending write is a plaintext
secret, and the one place this suite will not put a plaintext secret is a file.

### Where the arrow points

No app depends on `:secrets`. They depend on `:vaultkit` — the contract, the ref, the crypto — and
find the implementation through a registration the sandbox makes at start-up
(`SecretsAccess.register`). A bank client that had to pull in a password manager's UI in order to
read its own token would be the wrong shape.

The seam is three calls and no listing: read what you filed, file it, forget it. That is not a
sandbox — nine modules in one process cannot be isolated from each other by an interface, and
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

## What the app will not do

- **It cannot reach the network.** No `INTERNET` permission in its manifest, no HTTP client on its
  classpath. No sync, no account, no telemetry — and no breach check, not even the k-anonymous kind
  that sends a hash prefix to somebody else's server. The audit says weak, reused, old and empty, and
  those are the things that can be known without telling anyone anything.
- **It cannot recover a forgotten passphrase.** There is no reset link, no support address, no copy
  anybody else holds. The screen that creates a vault says so before it makes one.
- **It does not search secrets.** The search box reads titles, usernames, addresses, notes, tags and
  refs, and never a secret. Typing a password into a search box to find where you used it is a
  reasonable thing to want and a terrible thing to support — it puts the password into a text field,
  an input method's learned-word store, and whatever the keyboard app does with what it sees. The
  audit answers that question without anybody typing anything.
- **It serves no connection routes.** Three apps answer on the suite's address contract; this one
  does not.
- **It takes no screenshots of itself.** `FLAG_SECURE` for the life of the activity, so the recents
  thumbnail the system writes to disk is never a picture of the vault.

## The generator

Two shapes, because they are for two different things: a **character password** for the sites you
will never type by hand, and a **passphrase** of real words for the handful you do — a phone unlock,
a wifi key read to a guest, and this vault's own master passphrase.

Both report their entropy, which is the only honest way to compare sixteen jumbled characters with
five English words. The word list is 1,040 words, so a word is worth just over ten bits: five words
is about fifty, six about sixty. The list is public — it is compiled into the app — and that costs
nothing, because the entropy is in the dice rather than in the vocabulary.

## Tests

`:vaultkit` — 74 JVM tests. The ones that matter are the failures: wrong passphrase, flipped bit in
the body, a header edited to claim a cheaper KDF, a wrapped key spliced from another vault, a
truncated file, a version from the future, and an old archive merged over a newer vault.

`:secrets` — 21 Robolectric/JVM tests: the file store, the lock states, the broker, what the archive
does and does not contain, both restore paths, and the one this app exists for — a credential
mirrored on a phone that no longer exists, read back on the one that replaced it.

`:finance` — 8 more, on its half of the seam: which refs it uses, that a write reaches both stores,
that a read prefers the local copy, that a restore rehydrates, and that with no vault installed the
app behaves exactly as it did before.
