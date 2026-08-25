# Health — the household health tracker

Health is the suite's **who's-ill-and-what-have-we-given-them** app: a profile per person, the
temperatures and other readings taken for each of them, the symptoms they've got, the medicines
they're on with the label's own dose rules, and the illnesses all of it hangs off. It is a hosted
library module inside the Operations Sandbox container (`:app`), a peer to LifeOps, Citation and
Logistics — opened from the sandbox home, backed up into the same one-zip archive, and readable by
Advisor only if you grant it.

> **Health is not medical advice, and does not pretend to be.** It records what you tell it and reads
> those records back consistently, using widely-published home-care thresholds written down once so
> the app can't hold two opinions about the same number. Every screen says so. When something worries
> you, call a doctor or your local emergency number.

Its guiding idea is the same "keep the receipts" spirit as the rest of the suite. At 3am nobody
remembers when the last dose was, whether that reading is higher than the one before, or which day
the fever started — and the next morning nobody can reconstruct it. Health's job is to already know.

## What it does

| Tab | Purpose |
|---|---|
| **Today** | The cockpit for whoever is selected: their latest temperature with its verdict, the illness in progress, which medicines are **due now** vs. how long to wait, what symptoms are still going — and four one-tap records (temperature, dose, symptom, care note). |
| **Vitals** | The measurement history. A temperature curve plotted against real time with the fever line marked, plus every other reading (heart rate, breathing, oxygen, blood pressure, weight) in one list. |
| **Meds** | Each person's medicines with the spacing and daily limits **from their own labels**, each showing its live dose window — due now, wait *this* long, or the day's allowance is spent — and the full history of doses given. |
| **Illness** | Episodes past and present, each readable back as a summary: how long, how high it peaked, which way it's going, what was given, what's still going. Plus the care log. |
| **People** | The household. Add, edit and remove profiles; set whose reading you're looking at; choose °C or °F. |

## Profiles — why they're the spine, not a setting

Every row in Health belongs to a profile. There is no ambient "current person" at the data layer —
every query takes the profile id — and the profile bar sits on top of every tab rather than hiding in
a menu. A temperature filed against the wrong child is worse than one never recorded, so who you're
looking at is always on screen and always one tap to change.

**Health does not own who these people are.** People does — see **[PEOPLE.md](PEOPLE.md)** — and
Health is a **bind-only peer** on that sync seam: names, relationships and birth dates stay in step
with the household directory and LifeOps, while everything medical stays here. Health never grows a
profile for a household member nobody is tracking the health of, so adding someone stays a deliberate
act; and removing someone means *stop tracking their health*, not *remove them from the household*.

Two consequences worth knowing:

- A person added in People arrives with their **birth date**, which is exactly what the age-aware
  fever thresholds below need — and which nobody wants to type twice.
- A profile's **notes are never published**. They hold allergies, conditions and the doctor's number;
  People has a field called `note` too, but it means "likes hiking, hates crowds". The mapper refuses
  the association explicitly, because a shared wire makes that leak a one-line mistake.

A birth date is optional but load-bearing: it is what makes the fever assessment age-aware. The
thresholds for a six-week-old are not the thresholds for an adult, and a profile without a birth date
is told, on its card, that it will get the adult ones. It is also the field most likely to arrive
over the seam rather than being typed here.

Removing a person removes their readings, symptoms, medicines, doses, illnesses and care notes in one
transaction. "Remove this person" has to mean it.

## The two judgements Health makes

Both live in `health/logic/` as framework-free Kotlin, unit-tested on the JVM, so they are provable
without a device — and so every surface (screens, episode summaries, Advisor answers) gives the same
answer to the same question.

### Is this a fever?

`Fever.assess(measuredC, site, ageMonths)` returns a band, a care level and its reasons.

- **The site is part of the reading.** A rectal, ear, forehead, oral or armpit measurement is
  converted to a common **oral-equivalent** scale using the conventional adjustments before it is
  banded, so a 37.7 under the arm is correctly a fever and the same 37.7 in the mouth is not.
- **The age red flags are checked on the higher of the measured and adjusted values.** The published
  infant thresholds ("38.0 °C in a baby under three months") are written against *rectal* readings,
  which run above oral — adjusting one down and then testing it would quietly under-call the exact
  case the rule exists for. Erring toward "call someone" is the direction to err in.
- **Care levels are ordered** (`ROUTINE` → `MONITOR` → `CALL_DOCTOR` → `SEEK_CARE_NOW`) so the worst
  call across a screen's worth of facts is a `maxOf`, not a re-derivation.

### Can the next dose be given yet?

`DoseSchedule.evaluate(rule, history, now)` answers with `READY`, `WAIT` or `LIMIT_REACHED`, plus when
the wait ends. Two independent gates, and the later one wins:

- the **interval** gate — the last dose plus the label's minimum spacing;
- the **rolling 24-hour** gate — when the day's allowance (a dose count, a total amount, or both) is
  spent, nothing is allowed until the oldest dose in the window ages out of it.

The window rolls; it is not "since midnight". A day boundary is exactly where a naive counter lets a
fifth dose through. A rule with no limits never blocks — Health enforces what the label says, never a
restriction nobody typed in.

## Illnesses — the thing the rows hang off

Starting an episode is the difference between a scatter of readings and a story. While one is open,
**everything recorded is filed against it automatically**, and starting it also adopts the
unattached readings, doses and symptoms from the previous 12 hours — an illness is nearly always
noticed after the first temperature was taken, and a record you have to assemble by hand afterwards is
one nobody assembles. One open episode per person, so "how long has this been going on" has exactly
one answer.

`EpisodeSummaries.summarize` reads the whole thing back: duration, peak, latest, trend, how long the
*current* fever run has lasted (a fever that settles and returns is reported as the new run it is),
active vs. resolved symptoms, doses given — and the standing-back observations no single reading can
make, like a fever heading into its fourth day, which escalates the episode's care level on its own.

## Module layout

```
:health (Android library, com.health.app)
├── logic/            pure JVM, unit-tested — no Android imports
│   ├── Temperature      °C/°F conversion, tolerant parsing, plausibility bounds, formatting
│   ├── Fever            sites, bands, age-aware red flags, the standing disclaimer
│   ├── DoseSchedule     interval + rolling-24h dose windows and countdown formatting
│   ├── EpisodeSummary   an illness read back: peak, trend, fever run, advice
│   └── Age              birth date → months/years, and the label people actually use
├── data/             Room (HealthDatabase, entities, HealthDao) + repository + prefs
│   ├── model/        domain types with the string columns resolved into enums
│   ├── repository/   HealthRepository — rows in, models out, every judgement delegated to logic/
│   └── prefs/        HealthPrefs — selected person + display unit (deliberately not in the db)
├── ui/               Compose: today · vitals · meds · episodes · people (+ common, theme)
├── backup/           HealthBackupContributor (whole-file health.db copy + health_* prefs)
├── HealthApp.kt      tiny runtime container (install/get), like LogisticsApp
└── MainActivity.kt   tabbed shell over the one runtime
```

Health owns temperatures, doses and illnesses outright and shares them the two ways the suite already
shares things: a backup contributor and a read-only Advisor source. It does **not** own the people
they are recorded against, so it depends on `:people` for the sync contract — the packet, binder and
merge rule — and joins that seam as a bind-only peer. It does not read People's database; the roster
is replicated over a mailbox, not borrowed live.

## Storage

One `health.db`, seven tables:

- **`profiles`** — the people. Name, relationship, birth date (ISO `yyyy-MM-dd`), colour, their own
  baseline temperature, notes (allergies, conditions, the doctor's number), plus the `personKey` and
  `syncVersion` that make a profile a peer's view of a household member. *Schema v2 adds those two
  via `MIGRATION_1_2`; the colour, baseline and notes are Health's own and never leave it.*
- **`readings`** — every measurement, in the canonical unit for its type (temperature always in °C),
  with the site for temperatures and a nullable episode link.
- **`symptoms`** — name, severity 1–5, started, ended (null while it's still going).
- **`medications`** — a medicine as kept for one person, with every label limit nullable.
- **`doses`** — what was actually given. The medicine's name is **denormalised onto the row** so the
  history survives the medicine being renamed or deleted.
- **`episodes`** — bouts of illness; open while `endedAt` is null.
- **`care_notes`** — fluids, rest, the call to the doctor and what they said.

Two conventions run through all of them: **instants are epoch millis, dates are ISO strings** (a
moment gets subtracted and windowed; a birth date must not shift across time zones), and **every row
carries its profile id**. Cross-table links are plain nullable ids rather than foreign keys — a
reading taken before anyone declared an illness is still a real reading, and deleting a medicine must
not delete the record that a dose of it was given.

## Backup

`HealthBackupContributor` (registered as `AppId.HEALTH`) copies the whole `health.db` into the sandbox
archive and swaps it back on restore — complete by construction, the same approach LifeOps, Citation
and Logistics use. It also carries Health's own `health_*` preferences (selected person, display
unit) and, like LifeOps' contributor, touches **only** files matching its own prefix: the hosted apps
share one `shared_prefs/` directory. The manifest's data version is read from
`HEALTH_DB_VERSION` rather than hand-copied, so it cannot drift from the schema.

## Privacy

Health declares **no permissions at all** — no `INTERNET`, no sensors, no contacts. Household medical
records are the last data in this suite that should be able to leave the device, so the module has no
means to send them.

Advisor can read Health, but only behind the same explicit per-app gate as every other source, denied
by default. `HealthKnowledgeSource` is read-only and **names the person in every document it
produces** — the retrieval corpus is flat text with no per-row scoping, so a document that says
"38.4 at 21:00" without saying whose is one that can be retrieved into an answer about the wrong
person. Recent readings are listed individually and older ones characterised, so a year of
temperatures can't drown the rest of the corpus, and each temperature carries Health's own assessment
rather than inviting the model to form a second opinion.

## Tests

Pure-JVM suites under `health/src/test` (run with `gradle :health:testDebugUnitTest`) — 36 tests:

- `TemperatureTest` — conversion both ways, a *difference* converted as a difference (0.5 °C is
  0.9 °F, not 32.9), tolerant parsing (`" 38,4 °C "`), rejection of impossible values (`986`), and
  one-decimal formatting.
- `FeverTest` — the bands, the site adjustment changing the verdict on the same number, the newborn
  flag judged on the reading as taken rather than only on the adjustment, the under-six-months
  escalation, 40 °C being urgent at any age, hypothermia never being routine, and an unknown age
  falling back to the adult rules rather than the strictest ones.
- `DoseScheduleTest` — the interval gate, the spent allowance blocking even when spacing is fine, the
  window rolling rather than resetting at midnight, an amount cap releasing exactly when the oldest
  dose ages out, the later of the two gates winning, a limitless rule never blocking, a future-dated
  row not counting as given, and countdowns that read like speech.
- `EpisodeSummaryTest` — peak vs. latest being different questions, trends needing more than one
  reading and ignoring thermometer noise, the fever run measured from the start of the *current* run
  only, a fever into its fourth day escalating the care level, a baby's episode inheriting the
  age-aware level, symptoms split into still-going and passed, a stale episode asking for a fresh
  reading, and an ended episode reported in the past tense.
- `AgeTest` — whole months/years, a future or malformed birth date returning null rather than zero
  (0 months old is a dangerous reading of a typo), and the label using the unit people use at that age.
