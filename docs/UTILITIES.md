# Utilities — the takeovers

*The app that replaces pieces of the phone rather than providing a service.*

Module: `:utilities` · Package: `com.utilities.app` · Hosted by the Operations Sandbox (`:app`)

---

## 1. Why this app exists

Every other app in this suite replaces a *service*. LifeOps replaces a planner, Logistics a pantry
list, Secrets a password manager. Utilities replaces **pieces of the operating system**, and the
reason is the same each time:

> The stock component is fine, and it reports to somebody else.

A keyboard sees every password, every message and every search typed on the phone. The two that ship
on most handsets both send some of what they see away, to improve themselves, under a setting
somebody agreed to once. A messaging app sees every conversation in it. Neither of those is a thing
the household is *choosing* to publish; it is a thing they inherited with the handset.

Android already allows both to be replaced. It has allowed it for over a decade. What it does not do
is make it findable: the switch is three levels deep in system settings, it is guarded by a warning
dialog that (correctly) sounds alarming, and having enabled a keyboard you must then separately
*select* it — a step so easy to miss that "I installed it and nothing happened" is the single most
common first experience of a third-party keyboard.

So this app is two things at once: **the screen that finds those switches**, and **the replacement
they hand over to**.

### The shelf

The home screen is a list of takeovers, one row each, and every row says the same four things in the
same order:

| | |
|---|---|
| **What it replaces** | "The keys, drawn here instead" |
| **What state it is in** | on, half-done, off, or unavailable — as a dot |
| **What that means** | one sentence, in plain words |
| **The next step** | one button, or nothing when there is nothing left to do |

Two takeovers ship. The dialler, the launcher, the clipboard and the share sheet are all takeovers
Android permits and none of them is wired up; each would be one entry in `Utility`, one resolver in
`PhoneFacts`, and a screen. That extensibility is the point of the shape — the app is one idea
repeated, and a screen where each takeover invented its own layout would hide that.

### Three states, not two

`TakeoverState` has a `PARTIAL`, and it earns its place. Both shipped takeovers have a halfway house
that is genuinely useful and genuinely not the whole thing:

- a keyboard **switched on but not selected** — installed, and you are still typing on the old one;
- a message reader **drawing the threads while the carrier's app still delivers them**.

Collapsing either into "off" would make the shelf lie about a phone that is already half done. The
first of those is the state the shelf is loudest about, because from the outside it is
indistinguishable from nothing having happened.

---

## 2. The keyboard

### The promise, stated exactly

`:utilities` declares **no `INTERNET` permission** and has **no HTTP client on its classpath**. That
is the whole of the privacy claim, and it is worth being precise about what it does and does not
mean.

It **does** mean:

- there is no route off the device from this code. Not to a server, not to a crash reporter, not to a
  "personalisation" endpoint, not behind a setting;
- what it remembers is a **word list with counts** — no sentences, no sequence, no n-grams, nothing
  about who you were writing to — kept in a plain text file in this app's own directory and capped at
  a few thousand words;
- that list is **shown to you**, word by word with its count, on the keyboard's own settings screen,
  with a Forget button on every row and a Forget-all at the top;
- **nothing is learned from the fields that matter**: a password field of any input type, a field
  marked `TYPE_TEXT_FLAG_NO_SUGGESTIONS`, and any editor setting `IME_FLAG_NO_PERSONALIZED_LEARNING`
  (which is what a browser's private window does). Those are the editor's own statements about
  itself, not a guess this app makes;
- turning learning off **empties the list** as well as stopping it growing. A switch that stopped
  only the future would do half of what its label says;
- it can always be **escaped from**. A long press on the space bar hands back to whatever keyboard
  was there before.

It **does not** mean the keyboard sees less than any other keyboard. An input method sees everything
typed while it is selected; that is what an input method is. The argument here is not that it
doesn't — it is that this one is running in a process with no network permission, storing a word list
in a file you can open.

### What is made of what

Three pieces, and only the last is Android:

| File | What it decides | Tested |
|---|---|---|
| `keyboard/logic/KeyLayouts.kt` | what is on each page | JVM |
| `keyboard/logic/KeyboardMachine.kt` | what a press does to the keyboard | JVM |
| `keyboard/logic/Lexicon.kt` | what is remembered, and what is refused | JVM |
| `keyboard/KeyboardCanvas.kt` | geometry, paint, touch slop | — |
| `keyboard/UtilitiesKeyboardService.kt` | translating effects into `InputConnection` calls | — |

Nearly every keyboard bug that is not in touch handling is in the *arrangement* — a row whose widths
do not add up, a layer with no way out of it, a shift key that leaves the letters lowercase — and
every one of those is decidable from data without drawing anything. Which is why the layouts are
data, and why `KeyLayoutsTest` can assert that every page has a backspace, that only backspace
repeats, that the numeric pad offers no letters and no way to reach them, and that every printed
alternate types something the key does not already type.

### Why a `View` and not Compose

Everything else the household sees in this suite is Compose. The keys deliberately are not:

1. **An input method has no activity.** Its window is put up by the system over somebody else's app,
   so a `ComposeView` here needs a lifecycle owner, a saved-state registry and a view-model store
   bolted onto a decor view by hand — each a thing to get wrong in a process hosting eleven other
   apps. The recipe exists; it is machinery in service of nothing this screen needs.
2. **A keyboard is thirty rounded rectangles and thirty pieces of text**, relaid out only when the
   layer changes and repainted on every touch. That is exactly what a `Canvas` is.

The keyboard's *settings* screen is Compose, like the rest of the suite. It is the surface that is
different, not the app.

### Decisions worth arguing about

**Long press prints its alternate on the cap.** The usual answer is a popup row of alternates, and it
is deliberately not what this does: a popup is a second surface to lay out, a second thing to dismiss
and a gesture people discover by accident. Printing `é` small in the corner of the `e` key makes it
discoverable by *reading*, and the long press then does one predictable thing. A language needing
three alternates per key would need the popup, and would be the reason to build it.

**The symbols page is not left after one character.** Several keyboards throw you back to the letters
the moment you type a symbol. Somebody who went to the symbols page for `£` very often wants
`£30.00`, and that behaviour costs two taps every single time.

**The number row is off by default.** It costs a fifth of the keyboard's height to save one tap on
keys most people press rarely. The households that want it want it badly enough to find a switch.

**Key edges are on by default.** Edgeless keyboards look better in a screenshot and are measurably
worse to aim at, and the people most likely to be typing on a phone in poor light are the ones the
edges help most.

**The suggestion strip keeps its height when empty.** A bar that appears and disappears moves every
key up and down by forty pixels while somebody is typing, which is the most disorienting thing a
keyboard can do.

**Three suggestions, not five.** The third is already rarely right — the lexicon is a word list, not a
language model — and pretending otherwise by showing more would be a worse keyboard wearing a better
one's clothes.

### The lexicon's refusals

The rule that does the real work is what a word *is*: letters, optionally with an interior
apostrophe, between 3 and 32 characters. That single rule is what stops a word list from quietly
becoming a list of order numbers, licence keys and half-typed passwords:

```
hunter2     → refused (digit)
AB12CD      → refused (digit)
123456      → refused (no letters)
x, no       → refused (too short)
a-b         → refused (hyphen)
p@ssw0rd    → refused
don't       → kept
Thursday    → kept as "thursday"
```

`LexiconTest` asserts each of those by name, because it is one line away from not holding.

---

## 3. Messages

### Two rungs

This is the part to understand before switching anything on, and it is modelled in the code
(`Takeovers.messages`) rather than explained in a tooltip.

**Reading** needs `READ_SMS` (and `SEND_SMS` to reply) and changes *nothing else about the phone*.
The threads are already in Android's own `Telephony` provider. Utilities draws them. The carrier's
app keeps delivering, keeps notifying, keeps handling picture messages. This rung is worth having on
its own — it is the one that answers "I want my own window" — and because nothing moved, it costs
nothing.

**Default** is the whole job: Android hands arriving texts to this app, it stores them, it notifies.
It is granted through `RoleManager.ROLE_SMS`, all at once, and it is the rung where what this app
does *not* do starts to matter.

### Picture messages

MMS is not a bigger SMS, and the difference is the whole of why this is a subsystem rather than a
field.

A text arrives over the radio. A picture message arrives as a **pointer**: a WAP push carrying a
binary PDU that says something is waiting at a URL on the carrier's own MMSC, reachable only over
the carrier's data channel. Fetching it means parsing that PDU for the URL, asking the platform to
go and get it, parsing the reply into parts, and writing those parts into a second provider. Nothing
in Android does any of that for an app — `SmsManager` does the **network** half, because it alone
knows the MMSC address and the APN and can raise a cellular connection for one transfer while the
phone sits on Wi-Fi, and it hands back bytes.

So the bytes are ours, and that turns out to be good news: it makes the whole of MMS decidable
without a phone. `messages/pdu/` is a **pure-JVM WAP codec** under unit test, and `messages/mms/` is
the thin Android layer that moves its results between the platform and the store.

#### What happens when one arrives

1. `MmsDeliverReceiver` gets the WAP push and decodes the `m-notification-ind`. Delivery reports and
   read receipts come down the same pipe; the decoder refuses to call one of those a notification,
   which is how they are ignored rather than misread.
2. A **placeholder row** goes into the message store — from whom, what about, how big, and where it
   is waiting. This happens *before* anything is fetched, and three things follow from it: a failed
   download leaves something visible with a button on it, a household with auto-download off gets
   the same thing on purpose, and the notification can name the sender before a byte has moved.
3. If auto-download applies, `MmsTransport` asks the platform to fetch it.
4. `MmsDownloadedReceiver` decodes the `m-retrieve-conf`, files the message and its parts, and
   **then** deletes the placeholder — in that order, so there is never a moment with neither.

If auto-download does not apply, the app does not simply ignore the announcement: it sends an
`m-notifyresp-ind` saying **deferred**. A network that hears nothing re-pushes, which the household
experiences as the same picture message arriving four times.

#### When auto-download does not apply

- **it is switched off** — every picture arrives as a card with a Fetch button;
- **the phone is roaming** and roaming downloads are off, which is the default. An automatic
  download abroad is a charge nobody sees coming and finds out about a month later;
- **the message is advertising**. The class is in the notification, and auto-fetching one is
  somebody paying to receive a leaflet. It is still announced and still one tap away.

#### Sending one

Which protocol a message becomes is decided by `Routing`, which is pure and tested, from four facts:
attachments, recipient count, whether there is a subject, and whether Utilities holds the
default-SMS role.

**Pictures need the role.** Only the default app may write a sent MMS into the store, there is no
echo for a photograph the way there is for a text, and a picture that vanished the instant it was
sent would be worse than being told to switch. A **group** message without the role still goes — as
a separate text to each person, which is what every phone did before group messaging existed and is
better than not sending.

Every picture is re-encoded before it goes. Carriers cap a message at about 300KB and a phone camera
produces four megabytes a shot, so there is no path where the file somebody picked is the file that
is sent. `MmsBudget` does the arithmetic — overhead off the top, an even split between attachments,
a floor under which it refuses rather than sending a mosaic — and `MmsImages` runs the ladder:
**scale first, then quality**, because halving the dimensions removes three quarters of the pixels
and is nearly invisible on a phone, while JPEG quality under about 60 puts artefacts around text,
and a photograph of a receipt or a screenshot is as common a thing to send as a face. An animated
GIF that already fits passes through untouched; one that does not is refused rather than silently
turned into a still frame.

A message goes into the **outbox before the radio is asked**, so the thread shows it immediately and
a failure has a row to be marked against. `MmsSentReceiver` moves it to sent or failed when the
network answers.

#### The provider, and the thing most implementations get wrong

`SmsManager` will not take or return bytes in memory. Both calls hand a `content://` URI across to
the phone process, so there has to be a provider — and the usual implementation declares it
`exported="true"` and stops thinking about it, which makes every picture message on the phone
readable by any installed app for as long as its buffer sits in the cache.

`MmsFileProvider` is **not exported**. It carries `grantUriPermissions`, and the transport grants
exactly one URI to exactly the two system packages that do the work, for one transfer, and revokes
it in the result receiver on both paths. Everything else about it is refusal: it cannot be queried,
`insert`, `update` and `delete` do nothing, and the filename is checked so that a URI naming
`../../databases/lifeops.db` resolves to nothing. The buffers are a transfer buffer rather than
storage — the message goes into the platform's provider the moment it is decoded — so they are
deleted when the transfer ends and swept if one never does.

None of this is a network permission. This module still declares no `INTERNET` and still has no HTTP
client on its classpath: the transfer is the platform's, over the carrier's own bearer, and what
this app does is encode and decode.

#### Reading them back

`content://sms` and `content://mms` are separate tables, keyed by the same `thread_id`, that keep
their dates in **different units** — milliseconds and seconds. That is not a mistake anybody can
fix, and the two halves of a thread appear in the wrong order the moment somebody forgets it. The
conversion happens once, at the edge, in `MmsStore`, and everything above it is in milliseconds.

Parts are read in one query for the whole thread rather than one per message: a thread of two
hundred picture messages would otherwise be two hundred round trips into a provider that is not
fast. The pictures themselves are never loaded into a message — what travels is the platform's own
`content://mms/part/…` URI, and the screen decodes what it is about to draw, through a bitmap cache
bounded by **memory** rather than by count.

### Encryption

Messages between two installs of Utilities are encrypted end to end, by default, with no setup.

#### What RCS has to do with this, and why it is not here

The obvious way to get modern encrypted messaging on Android is RCS, and **a third-party app cannot
do it.** Not "with difficulty" — there is no API. `android.telephony.ims.RcsMessageStore` landed in
AOSP for Android 10 and was removed in Android 11 before it was ever usable; what remains in
`android.telephony.ims` is `@SystemApi` behind `READ_PRIVILEGED_PHONE_STATE`, which is carrier-
privileged apps only. The default-SMS role — the one this app takes — grants SMS and MMS and nothing
else. Google Messages does RCS because it ships Google's Jibe client stack, not because it holds the
role. RCS's end-to-end encryption as it exists today is Google's own Signal-protocol implementation
between Google Messages clients, not part of the standard and not exposed to anybody.

So the choice was to do nothing, or to encrypt over the transport this app actually has. It encrypts.

#### The protocol

**X3DH** to agree a secret, and a **Double Ratchet** to use it — the Signal design, implemented
directly rather than through a library, in `messages/seal/` as pure JVM with no Android in it.

| | |
|---|---|
| Key agreement | X25519, implemented here (see below), pinned against RFC 7748's vectors |
| Key derivation | HKDF-SHA256, pinned against RFC 5869's vectors |
| Encryption | AES-256-GCM, header bound in as associated data |
| Session start | X3DH with identity, ephemeral and prekey — three DHs |
| Ongoing | Double Ratchet: a symmetric step per message, a DH step per reply |
| Verification | 60-digit safety number over both identity keys, Signal's construction |

The curve is implemented rather than called, and that is a decision worth the paragraph it has in
`Curve25519`: the JDK and recent Conscrypt both have `XDH`, and using it would mean the code CI
exercises is not the code that runs on the phone — two implementations, one never executed by the
tests, differing by Android version. The tradeoff taken is that the field arithmetic is `BigInteger`
and therefore **not constant-time**, which is written down in the source rather than implied. The
attacker that admits is one already running code on the phone, where the messages are readable from
the screen anyway.

#### What the three Diffie-Hellmans are each for

Easy to "simplify" to one, so the reasons are in the source and here:

- **identity × their prekey** authenticates *us* to them — only our identity private key computes it;
- **ephemeral × their identity** authenticates *them* to us;
- **ephemeral × their prekey** supplies freshness, without which two sessions between the same pair
  of long-term keys would derive the same secret forever.

X3DH as specified also signs the prekey. This does not, and that is a subtraction with an argument:
the signature exists so a bundle fetched from a **server** can be tied to the identity key it claims.
There is no server. A bundle arrives over the air from the same place the identity key arrived, in
the same message — an attacker who can substitute one can substitute the other, and a signature by
the substituted identity key verifies perfectly. What defends against that attacker is the safety
number, which is a thing a person does. The cost avoided is an entire signature scheme (XEdDSA) that
would have to be written and got right.

#### Why the ratchet, and what each half buys

- **The symmetric ratchet** steps a chain key forward once per message. Because the step is one-way,
  a key recovered today says nothing about yesterday. That is what matters when a phone is lost.
- **The DH ratchet** replaces the chain whenever the other side sends a new ratchet key. An attacker
  holding a stolen copy of the state is locked out again after one round trip. That is what matters
  when a phone was lost and got back.

Both are tested as properties rather than as code paths: `SessionTest` restores a captured state and
asserts it cannot open an earlier message, and asserts that a copy of Bob's state stops working once
Alice and Bob exchange one more round.

#### SMS is not a stream

A ratchet that refused anything out of order would lose messages that every other app shows, because
SMS segments are reordered routinely, deliveries are duplicated, and a message can be delayed for
hours. So:

- **skipped keys are kept**, bounded at 1000 per chain and 2000 overall, oldest evicted first;
- **a key is used once** and removed as it is used, so a replayed delivery does not open twice;
- **a message claiming to be two billion ahead is refused**, because without that bound one text
  costs the receiving phone two billion HMACs.

Each of those is a test with the failure in its name.

#### How two phones find each other, with no server

A **data SMS on a port** (17763). Not a marker appended to a real message, and the difference is the
whole design: a data message is never stored by the platform and never displayed by any messaging
app, so somebody without Utilities sees **nothing at all** rather than a line of base64 from a
friend. That property is what makes automatic key exchange acceptable rather than rude.

It costs one SMS segment per contact, once, so it is a setting that says so. Offers are rate-limited
to one a day per contact, and a reply never triggers a reply — two installs would otherwise talk to
each other forever.

#### Trust on first use, verifiable afterwards

Automatic exchange cannot rule out an attacker present at the very first handshake, so the app does
not claim it can. Three states, and the middle one is the honest part:

| | |
|---|---|
| **Not encrypted** | no keys — nearly everybody |
| **Encrypted** | sealed, but the first exchange has not been checked |
| **Verified** | somebody compared the safety number |

The strip above a conversation says which, and tapping it opens the safety number: sixty digits in
blocks of five, monospaced so they can be read down a phone line, with a QR code for when the two
people are in the same room. Both ends compute the *same* number, sorted so neither has to know who
the initiator was — the one failure that would make the feature worse than nothing.

Nothing prompts anybody to verify. A security prompt that everybody dismisses trains everybody to
dismiss security prompts.

**An identity key that changes is refused, not adopted.** It means a reinstall or somebody in the
middle, nothing can tell those apart, and quietly re-pinning would make the distinction unobservable.
The thread says the keys changed and starting again is a deliberate act.

#### What this protects, stated exactly

**Protected: the message in transit.** What leaves the phone is ciphertext. The carrier, anyone with
access to the carrier's systems, and anyone intercepting the air interface see a sealed blob.

**Not protected: the message on this phone.** A received message is decrypted and stored, in
plaintext, in Android's own message database — where every message has always been, readable by any
app granted SMS access.

That follows from the decision the whole app is built on: Utilities owns no data. Keeping sealed
messages out of the platform's store would mean a private second copy of the household's history and
a takeover that could no longer be undone — and it could not be done by storing the ciphertext
either, because forward secrecy destroys the key as it is used, so a stored ciphertext would be
unreadable forever after.

**Not protected: who, when, and how much.** The carrier still records that these two numbers
exchanged a message of about this length at this time. No encryption over SMS changes that.

**Not interoperable.** This is Utilities to Utilities. It is not Signal and cannot talk to it.

#### Cost

Eighty-nine bytes of overhead on an ordinary message, 153 on the first, base64'd — about one extra
SMS segment per message, two on the first. Cheaper than the alternative, which would be a picture
message per text.

#### What is backed up

Everything in `filesDir/utilities/seal` is **encrypted** with a portable 32-byte key that is kept two
places for two reasons: locally, behind an Android Keystore key, so that reading a message needs
nothing unlocked; and **in the vault**, because that is the only copy a new phone can get. So the
sealed store in an archive does not open until Secrets does — the same bargain the vault's own
payload strikes. See `SessionCipher`.

The **identity key** travels in the vault too, not in the archive. An identity that changed on every
restore would make every contact see *the keys changed*, which is the one alarm that has to mean
something.

The **peer records** — who somebody is, and whether anybody verified them — are in the archive and
are restored. This is the half with durable value: verification cost a human being a phone call, and
losing it on every restore would mean being asked to verify the same person again for reasons they
cannot see. Verification is stored against the **identity key**, not against a session, precisely so
it survives the plumbing being rebuilt underneath it.

#### The one thing that is not carried, and why

The **ratchet state**, and this is about correctness rather than privacy.

A Double Ratchet is a counter that only goes forward. Restore last week's copy and the sending chain
is rewound: the next message out is encrypted with a key the other end consumed days ago and cannot
get back to, so they cannot open it; their replies have moved the root key on, so the chain this
phone derives for *receiving* is not the one they are sending under either. Nothing about that
self-heals — it is a conversation silently dead in both directions, which is very much worse than one
message of setup. It would also rewind the used-key store, quietly returning replay protection for
every message in the window.

Carrying it and refusing to put it back would be worse still: it would break the suite's own rule
that everything in an archive comes back byte for byte, which `BackupCoverageTest` enforces. So
`seal/ratchet-…` is left out by filename prefix, with the reason on the census's excluded list.

**Nothing visible is lost.** The first message after a restore starts a fresh handshake, which costs
one message and no interaction, and the peer record it starts from came back intact. The conversation
is still encrypted, still verified, and the household notices nothing.

#### Conversations heal themselves

A ratchet can legitimately go missing at either end — the other person reinstalled, or somebody
restored a backup. Both arrive as a message that will not open with the session on file, and both are
answered the same way: if it is an *opening* message, accept a new session. The existing session is
tried first and only written back on success, so a failed attempt leaves the stored ratchet exactly
as it was, which is what makes the fallback safe to try.

Without that, one reinstall would leave two people unable to write to each other with no way to tell
why. It is two tests: one restores a backup and carries on, one proves a failed attempt does not
half-turn the ratchet.

### The four components, and why one of them is odd

Android will not offer an app in the default-SMS role picker unless its manifest declares all four
of:

| Component | Guarded by | Reachable by |
|---|---|---|
| `SmsDeliverReceiver` | `BROADCAST_SMS` | the platform only |
| `MmsDeliverReceiver` | `BROADCAST_WAP_PUSH` | the platform only |
| `RespondViaMessageService` | `SEND_RESPOND_VIA_MESSAGE` | the platform only |
| `SendToActivity` (`sms:` links) | nothing | **any app** |

The first three hold permissions no installable app can hold, so "exported" there means the operating
system can bind them and nothing else can. The fourth is genuinely a wider surface, and is therefore
deliberately the narrowest thing in the module — the same shape as Secrets' share-sheet import:

- it has **no layout, no state and no result**. An app that starts it learns nothing: not whether the
  number is known, not whether a thread exists, not what is in one;
- it **does not carry `?body=` across** into the composer. A link that can pre-fill a message is one
  mistaken tap from sending somebody else's words from your number;
- `MainActivity` stays **not exported**, like every other hosted app's screen. The sandbox opens it
  in-process.

### The outbox

Only the default SMS app may write to the message store. That is Android being right — an app that
could insert texts into your history could forge a conversation — and it leaves a real gap in the
reading rung: `SmsManager` will send the text, the recipient receives it, and it appears **nowhere**,
because nothing is allowed to record it. A reply that vanishes the instant it is sent is worse than
no reply button.

So Utilities keeps its own small record of what *it* sent, shows those alongside the stored messages,
and throws each one away the moment the real store has it. The matching rule is:

> same recipient, same body once whitespace is normalised, sent within **two minutes** of each other.

Two minutes is generous on purpose: the timestamp the store records is the carrier's, not the moment
the send button was pressed. Too strict and every reply shows twice for a day; too loose and a second
message with the same words silently disappears. Both halves are asserted in `OutboxTest`.

Entries older than a day are dropped whether or not they ever landed, and nothing is ever written to
the outbox once Utilities holds the role. The file is deliberately named `outbox_utilities` — outside
the prefix the backup contributor sweeps — because an echo restored onto a new phone is a claim that
is false by the time it arrives.

### Comparing phone numbers

The quietly hard part. The same person appears as `+1 (555) 010-9999`, `5550109999` and
`555-010-9999` depending on whether they texted you, you texted them, or the number came out of a
contact card. Two rules settle nearly all of it, in `Addresses`, and neither needs a library:

1. strip everything that is not a digit;
2. compare the **last ten** — which makes a country code and a leading zero stop mattering without
   pretending to know which country anybody is in.

Short codes (a bank's five-digit sender) are shorter than that and compare whole, which is correct:
two different short codes are two different senders. Only the ten-digit case is *formatted*, because
it is the only grouping that is not a guess — a short code, an international number and an
alphanumeric sender like `VERIZON` are shown exactly as they arrived, which is what somebody checking
a number against a letter from their bank actually wants.

---

## 4. The look

> *"maybe borrowing a ton of GUI customization from citation"* — the original ask, and the right
> instinct.

Citation's reader settings are the best screen in this suite, and not because there are a lot of
switches on it. It is because every one of them is a decision somebody defended out loud, and because
the four presets are **covers for a page you can still take apart** underneath them. You start with
Sepia, you find it a shade too yellow, and there is a slider right there.

A keyboard and a message thread are the other two surfaces people stare at for an hour. They got the
same treatment.

### What is shared, and what is not

`UtilityLook` is the surface-level value both takeovers carry:

- **theme** — System (the suite's own colours), Paper, Sepia, Night, Custom;
- **surface / text / accent colours**, under Custom, held even while a preset is selected so that
  switching to Sepia to compare and back does not throw away colours somebody tuned;
- **true black**, under Night, so an OLED panel actually switches those pixels off;
- **warmth**, 0 to 1;
- **typeface** — sans, serif, mono, or a file the household supplied;
- **text scale**, **roundness** and **air**.

On top of that, the keyboard adds height, number row, key edges, long-press delay, haptics,
capitalisation and the three privacy switches; the thread adds bubble roundness, tails, avatars,
timestamp style, return-sends and newest-first. One button on each screen takes the other surface's
`UtilityLook` — only the shared part travels, because a bubble's corner radius is not a key's.

### Warming is done to the colours, not over them

The one piece of maths worth reading. A night-shift filter that lays a translucent orange sheet over
the surface **dims everything under it**, which flattens contrast at exactly the hour somebody turned
the warmth up because their eyes were tired. Cutting blue out of each colour — blue most, green a
little, red not at all — warms the surface while leaving it as legible as it was. `UtilityPalettesTest`
asserts that: a fully warmed white-on-black keeps at least three quarters of its original contrast.

### Nothing you choose can make the app unreadable

Three rules, all tested against a sweep of surfaces including the awkward ones:

- **the accent is checked against the surface it lands on** and, if it cannot be read, pulled toward
  the text colour until it can. A pale yellow send button on a white surface does not disappear; it
  becomes a darker yellow;
- **the received bubble is derived from the surface** by a faint step toward the text colour, and the
  step is **doubled when the first one vanishes** — which is what rescues a surface somebody tinted
  mid-grey;
- **`contrastOn` measures both candidates** rather than using a luminance threshold. A threshold at
  0.45 hands a surface of luminance 0.44 the light ink at about 2 : 1 — the mid-grey bubble whose text
  nobody can read. Picking the better of the two puts the worst case above 4 : 1.

A part-transparent colour is made opaque on the way in: a see-through surface lets whatever is behind
it show through, which is never what choosing a colour meant.

### Fonts

No face is shipped. The ones people actually ask for here — OpenDyslexic first among them — are not
ours to redistribute, and pointing at a file you already have is both the honest answer and the more
capable one.

The bytes are **copied in** rather than the picker's URI being kept, for two reasons: a document grant
does not survive a reinstall, so the font would quietly stop working on the day everything else came
back from a backup; and the keyboard is drawn in a window with no activity, where opening another
app's document provider is a permission conversation nobody is there to have. The copy is named by
the digest of its contents, so picking the same font twice costs nothing, and it lives under
`filesDir` where the backup contributor sweeps it.

### Why a copy of Citation's design rather than a dependency

`:core` is *Citation's* spine — the sync contract, the paginator, the note model. A keyboard reaching
into it for a colour would be an arrow nobody could defend on the day Citation's reader settings grow
a field about chapters. What is borrowed is the **design**; the thirty lines of colour maths are worth
owning outright. If a third app ever wants them, the place they go is `:suitekit`, which is already
the suite's pure-JVM appearance contract.

---

## 5. Storage, and the backup

Utilities **owns no data**, and that is the design rather than a gap.

The texts are in Android's `Telephony` provider, where they have always been, put there by whatever
app has been delivering them. This app draws a window onto them. That is what makes every takeover
**reversible** — handing Messages back to the carrier's app loses nothing, because nothing moved —
and it is why the backup slice is a few kilobytes:

| | Carried? | Why |
|---|---|---|
| `utilities_look` (appearance) | **yes** | twenty minutes of somebody's afternoon |
| `utilities_messages` (settings) | **yes** | four choices that depend on what you pay for |
| `filesDir/utilities/lexicon.txt` | **yes** | the learned words, as readable text |
| `filesDir/utilities/fonts/*` | **yes** | a grant does not survive a reinstall; the bytes do |
| the encryption identity | **via the vault** | must survive a new phone, must not sit in a zip |
| `filesDir/utilities/seal/peer-*` | **yes, encrypted** | verification cost somebody a phone call |
| the key that opens `seal/` | **via the vault** | in the archive it would make the rest pointless |
| `filesDir/utilities/seal/ratchet-*` | no | a rewound ratchet kills the conversation outright |
| `outbox_utilities` | no | claims about a store that, on a new phone, are all false |
| `utilities_seal_adverts` | no | when we last silently texted each contact |
| the messages themselves | no | Android's, not ours; a second copy could only drift |

The look and the word list are both singletons that may already be open when a restore runs — the
keyboard could literally be on screen over another app — so the contributor re-reads both afterwards.
A restored colour takes effect without anybody being told to reboot the phone.

The appearance document is stored as JSON and **merged over the defaults** rather than deserialized
on its own. Gson builds objects without calling their constructors, so a field added later comes back
as `false` or `0` or `null` — which for `heightScale` is a keyboard with no height. Merging over a
tree of the defaults means an old document supplies what it knows about and everything added since
arrives at the value the data class declares.

---

## 6. Tests

All JVM, no Robolectric — nothing worth testing here touches Android.

| Suite | What it pins |
|---|---|
| `KeyLayoutsTest` | every page has an escape and a backspace; only backspace repeats; the numeric pad has no letters; the alphabet appears once; sentence punctuation is on the letters page; every printed alternate types something new |
| `KeyboardMachineTest` | the shift cycle and its double-tap lock; a held shift spent by one letter and a locked one not; long press typing the alternate unshifted; the symbols page not being left after one character; sentence capitalisation, including the case where the cursor sits right after the stop |
| `LexiconTest` | what is learned, what is suggested, the tie-break that stops the strip flickering — and, chiefly, every shape of thing that is **refused**: digits, symbols, too short, too long |
| `UtilityPalettesTest` | warming cuts blue and does not cost contrast; every preset is legible; an unreadable accent is rescued on any surface; sliders clamp; a transparent colour is made opaque |
| `ChatPalettesTest` | a received bubble stands off every surface including mid-grey; text is readable in both bubbles everywhere; an avatar is the same colour for the same person however their number is written |
| `OutboxTest` / `AddressesTest` | the settle rule in both directions — one copy when the store catches up, two when somebody deliberately said the same thing twice; a failed echo kept; number comparison across every format |
| `WspTest` | every WAP primitive against the byte sequences the specification gives: uintvars, the length-quote rule, the text-string quote, encoded strings with a charset, content types with parameters, and the bounds checks that make a truncated download fail rather than crash |
| `PduCodecTest` | hand-built notification PDUs decoded field by field; a full encode-then-decode round trip of a message with a caption, three pictures and a layout part; a delivery report refused as a notification; a part claiming more bytes than the message holds refused rather than clamped |
| `MmsBudgetTest` | the size arithmetic: overhead taken off the top, an even split, a refusal below the useful floor, a carrier config of nonsense clamped, and a ladder that only ever goes downhill |
| `RoutingTest` | which protocol a message becomes, including the two cases a `when` gets wrong: a picture without the role refused with a reason, and a group without the role sent as texts rather than refused |
| `PrimitivesTest` | X25519 against RFC 7748 §5.2 and §6.1, HKDF against RFC 5869 A.1 and A.3, a small-order point refused, and every AEAD failure looking identical from outside |
| `SessionTest` | Alice and Bob through the real protocol: out-of-order delivery, a message delayed across a reply, a replay refused, forward secrecy (a later state cannot open an earlier message), post-compromise recovery (a stolen state stops working after one round trip), an edited header refused, a message replayed into another conversation refused |
| `SessionCodecTest` | a conversation surviving the process going away — including the skipped keys, which is the field a codec loses silently — and that a stored session holds no verification, because that belongs to the person |
| `TakeoversTest` | the three states and, separately, the **next step** each one offers — including the case a state comparison would get wrong, where a takeover is `ON` and still has something to ask for |
| `BackupCoverageTest` (in `:app`) | the census: the look, the word list and a font file are in the archive, and the outbox is on the excluded list with its reason |

---

## 7. Adding the third takeover

1. An entry in `Utility` — id, title, blurb, and the sentence saying what leaks.
2. A `TakeoverStatus` function in `Takeovers`, pure, taking the platform facts as booleans.
3. The facts themselves in `PhoneFacts`, which does nothing but ask the platform.
4. A branch in the shelf's `onOpen` / `onNextStep`, and a screen.
5. Tests for (2). The wording is the part most likely to be wrong, and it is testable.

Nothing else in the suite has to change: the home tile, the backup slice and the settings route are
already this app's.
