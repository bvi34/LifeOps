# Health — the household health tracker

Health is the suite's **who's-ill-and-what-have-we-given-them** app: a profile per person, the
temperatures and other readings taken for each of them, the symptoms they've got, the medicines
they're on with the label's own dose rules, the medicine cabinet those come out of, and the illnesses
all of it hangs off. It is a hosted
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
| **Meds** | The **medicine cabinet**, in two halves. *Cabinet* is the household's actual stock — every bottle and box, whether it's still in date, whether there's enough left, where it lives, and everyone who takes it with their own dose and live dose window. *[Name]'s medicines* is the per-person regimen: the spacing and daily limits **from their own labels**, each showing its window — due now, wait *this* long, or the day's allowance is spent — plus reminders and the full history of doses given. |
| **Illness** | Episodes past and present, each readable back as a summary: how long, how high it peaked, which way it's going, what was given, what's still going. Plus the care log. |
| **People** | The household. Add, edit and remove profiles; set whose reading you're looking at; choose °C or °F. |

## Profiles — why they're the spine, not a setting

Every row in Health belongs to a profile. There is no ambient "current person" at the data layer —
every query takes the profile id — and the profile bar sits on top of every tab rather than hiding in
a menu. A temperature filed against the wrong child is worse than one never recorded, so who you're
looking at is always on screen and always one tap to change.

**Health does not own who these people are.** People does — see **[PEOPLE.md](PEOPLE.md)** — and
Health joins that sync seam as the peer that takes only the people the directory has ticked as
**household members**. Names, relationships and birth dates stay in step with People and LifeOps,
while everything medical stays here.

The tick is the whole interface. Mark somebody a household member in People and Health grows a
profile for them; leave them unticked and Health never hears about them, because a medical profile
for every adult in the house would be noise. Un-ticking stops them being offered and deletes nothing
Health has recorded. Removing a profile here means *stop tracking their health*, not *remove them
from the household* — it un-ticks them and leaves the directory's record intact.

Two consequences worth knowing:

- Somebody ticked in People arrives with their **birth date**, which is exactly what the age-aware
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

## The medicine cabinet

The Meds tab answers two different questions, and it is split in two because conflating them is what
makes a household medicine list quietly wrong.

**"What do we have?"** is a question about *things*. A bottle of ibuprofen is a household possession:
one bottle, one amount left, one expiry date, however many people take from it. So `cabinet_items` is
**not** scoped to a profile. A copy per person would be four dates to keep in step and three of them
wrong within a month.

**"What does she take, and can she have some yet?"** is a question about a *person*, and that is
`medications`, unchanged in substance: her dose, her spacing, her daily limit, her dose window.

A medication points at the bottle it comes out of, and the cabinet card shows every person pointing
at it — so standing in front of a bottle you get the answer you actually came for, which is almost
never "what is this" and almost always "how much does *she* get, and can she have some yet".

### What the cabinet works out

`Cabinet.assess` is framework-free and unit-tested like the rest of `logic/`, and it computes only
from what was written down:

- **Expiry.** `EXPIRED`, `EXPIRING_SOON` (within 60 days), `IN_DATE`, or — with no date recorded —
  `UNKNOWN`, never "probably fine". A month-only date, which is what boxes actually print, is read the
  way a pharmacist reads it: good **through the end of that month**. An unparseable date leaves the
  item undated rather than wrongly expired.
- **Stock.** `OUT`, `LOW`, `IN_STOCK`, or `UNKNOWN`. Low means the threshold you set, or — if you set
  none — that there isn't enough left for one more dose. Health won't decide for you that three
  tablets is "low"; it will tell you when three tablets can't cover the next dose.
- **Doses remaining**, but **only when the stock and the dose share a unit.** 120 mL dosed in 15 mL is
  eight doses. 120 mL dosed in 160 mg is a conversion that depends on the concentration, and getting
  that wrong is the exact failure this app exists to prevent — so Health returns nothing and says
  nothing.

Recording a dose draws it out of the linked bottle under that same same-unit rule, and deleting a
dose puts it back. The inverse has to exist: without it, one fat-fingered dose leaves a household
with a bottle Health believes is emptier than it is.

Items sort worst-news-first — expired, then out, then expiring soon, then low, then everything fine.
A cabinet is read top-down when something is wrong and searched by name when nothing is.

## Looking a medicine up — RxNorm and openFDA

Typing a medicine in by hand still works and still does everything it did. In front of it there is now
a search, because the *identity* of a medicine is the part a phone can fetch and a person shouldn't
have to spell.

Two public, keyless U.S. government references:

- **RxNorm** (`rxnav.nlm.nih.gov`, National Library of Medicine) — the drug vocabulary. It knows that
  "Children's Tylenol" and "acetaminophen 160 mg/5 mL oral suspension" are the same product, which is
  exactly the lookup a person standing at a cupboard cannot do. Health takes the ingredients, the
  brand, the dose form, the available strengths and the controlled-substance schedule.
- **openFDA** (`api.fda.gov`) — the Structured Product Label, the text printed on the box. Health keeps
  a fixed, ordered set of sections: what it's for, the directions, then everything that starts with
  "don't".

The answer is cached in `drug_facts`, keyed by RxNorm's concept id and **not** scoped to a profile —
what a product is made of doesn't vary by who is taking it, so one lookup serves the whole house and
one refresh updates everybody.

### The line this feature does not cross

**Health never computes a dose from a label.** Not the amount, not the spacing, not the daily maximum.

A label's dosing text covers several ages, several weights and often several products at once; picking
the line that applies to the person in front of you is a judgement, and Health does not make
judgements it wasn't given. So the label's own directions are shown *beside* the dose fields as the
reference they are — the manufacturer's words, attributed and dated — and the fields stay empty until
somebody types in them.

An app that reads a label to you is a reference. An app that does arithmetic on a label is giving
medical advice, and this one is not qualified to. Sections are stored and shown verbatim: joined,
whitespace-collapsed, and otherwise untouched. Nothing is summarised, ranked or filtered for
relevance.

Both dates are shown on the label sheet — when Health looked it up, and when the manufacturer last
revised the label — because a stale cache and a stale label are different problems.

## Reminders

Two modes, because households take medicines in two ways:

- **At set times** — the regular ones. The 8am and 8pm tablet, taken at breakfast whether or not the
  last one was three hours late. These want a clock.
- **When the next dose is due** — the as-needed ones. Calpol at 2am. Nobody wants a fixed-hour
  reminder for those; they want to be told the moment the four hours are up, which is a fact
  `DoseSchedule` already computes.

Off is the default, and everything that predates the feature stays off. Pausing a medicine pauses its
reminder, because pausing is how somebody says "not at the moment" and a notification that ignores
that is the fastest way to get notifications turned off wholesale.

`DoseReminder` computes the next instant against an injected clock and is unit-tested, so the awkward
cases are provable rather than folklore: a time that has already passed today rolls to tomorrow; a
when-due reminder for a medicine nobody has taken in a day **lapses** rather than nagging into the
silence; and a reminder that wakes up late re-checks the live dose window before it says anything —
if somebody else in the house already gave a dose from their own phone, it stays quiet.

Delivery is WorkManager (`reminder/`), not exact alarms: a dose reminder is a "some time around eight"
nudge that must survive a reboot, not a to-the-second alarm. The notification never tells anybody to
take a medicine — it says the dose *you wrote down* is due, and taps through to the screen where the
live dose window has the final word.

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
│   ├── DoseReminder     when a reminder next fires, in both modes, against an injected clock
│   ├── Cabinet          expiry and stock verdicts, doses remaining, same-unit rule
│   ├── DrugFacts        the monograph types — candidates, label sections, attribution
│   ├── RxNormParser     RxNorm JSON → candidates, ingredients, strengths, schedule
│   ├── OpenFdaParser    openFDA JSON → the label's own sections, in reading order
│   ├── EpisodeSummary   an illness read back: peak, trend, fever run, advice
│   └── Age              birth date → months/years, and the label people actually use
├── data/             Room (HealthDatabase, entities, HealthDao) + repository + prefs
│   ├── model/        domain types with the string columns resolved into enums
│   ├── net/          DrugLookupClient — the only class in Health that opens a connection
│   ├── repository/   HealthRepository — rows in, models out, every judgement delegated to logic/
│   └── prefs/        HealthPrefs — selected person + display unit (deliberately not in the db)
├── reminder/         MedicationReminderWorker + scheduler (WorkManager; timing lives in logic/)
├── ui/               Compose: today · vitals · meds · episodes · people (+ common, theme)
├── backup/           HealthBackupContributor (whole-file health.db copy + health_* prefs)
├── HealthApp.kt      tiny runtime container (install/get), like LogisticsApp
└── MainActivity.kt   tabbed shell over the one runtime
```

Health owns temperatures, doses and illnesses outright and shares them the two ways the suite already
shares things: a backup contributor and a read-only Advisor source. It does **not** own the people
they are recorded against, so it depends on `:people` for the sync contract — the packet, binder and
merge rule — and joins that seam as the peer that creates only for ticked household members. It does
not read People's database; the roster is replicated over a mailbox, not borrowed live.

## Storage

One `health.db`, ten tables:

- **`profile_tombstones`** — profiles removed here, kept only long enough to publish the un-tick so
  the next round doesn't hand the person straight back. See PEOPLE.md.
- **`profiles`** — the people. Name, relationship, birth date (ISO `yyyy-MM-dd`), colour, their own
  baseline temperature, notes (allergies, conditions, the doctor's number), the `household` tick,
  plus the `personKey` and
  `syncVersion` that make a profile a peer's view of a household member. *Schema v2 adds those two
  via `MIGRATION_1_2`; the colour, baseline and notes are Health's own and never leave it.*
- **`readings`** — every measurement, in the canonical unit for its type (temperature always in °C),
  with the site for temperatures and a nullable episode link.
- **`symptoms`** — name, severity 1–5, started, ended (null while it's still going).
- **`medications`** — **one person's use of a product**: their dose, the label's limits (every one
  nullable), their reminder setting, and the two links that keep it from duplicating anything —
  `rxcui` to the cached product facts, `cabinetItemId` to the bottle it's given from.
- **`doses`** — what was actually given. The medicine's name is **denormalised onto the row** so the
  history survives the medicine being renamed or deleted.
- **`cabinet_items`** — the physical stock: a bottle, a box, a blister pack. Amount left, expiry date,
  where it lives, when to call it low. *Schema v4, and deliberately **not** scoped to a profile — a
  bottle is a household possession, and a copy per person would be four expiry dates to get wrong.*
- **`drug_facts`** — one looked-up product's monograph, cached whole and keyed by RxNorm concept id.
  Also household-scoped: what acetaminophen suspension is made of doesn't vary by who takes it, so one
  lookup serves everybody and one refresh updates them all. The label's sections are JSON in a single
  column; a malformed blob reads back as no sections rather than taking the screen down. *Schema v4.*
- **`episodes`** — bouts of illness; open while `endedAt` is null.
- **`care_notes`** — fluids, rest, the call to the doctor and what they said.

Two conventions run through them: **instants are epoch millis, dates are ISO strings** (a moment gets
subtracted and windowed; a birth date must not shift across time zones), and **every row about a
person carries its profile id** — there is no ambient "current person" at the data layer.

The two v4 tables are the deliberate exception, and the exception is the design: `cabinet_items` and
`drug_facts` are about *things*, not people. A bottle is a household possession and a drug label is a
fact about a product; scoping either to a profile would mean one row per person, one expiry date per
person, and three of them wrong within a month.

Cross-table links are plain nullable ids rather than foreign keys — a reading taken before anyone
declared an illness is still a real reading, deleting a medicine must not delete the record that a
dose of it was given, and throwing a bottle away must not delete either the regimens given from it or
the doses recorded against it.

## Backup

`HealthBackupContributor` (registered as `AppId.HEALTH`) copies the whole `health.db` into the sandbox
archive and swaps it back on restore — complete by construction, the same approach LifeOps, Citation
and Logistics use. It also carries Health's own `health_*` preferences (selected person, display
unit) and, like LifeOps' contributor, touches **only** files matching its own prefix: the hosted apps
share one `shared_prefs/` directory. The manifest's data version is read from
`HEALTH_DB_VERSION` rather than hand-copied, so it cannot drift from the schema.

Restoring also **re-arms every medication reminder** against the database that has just arrived. The
work queue survives the restore and still refers to the medicines of the database that was replaced,
so it is cancelled wholesale and rebuilt from the restored rows — the only version of this that can't
leave somebody nudged about a medicine they don't have, or not nudged about one they do.

## Privacy

Health declares exactly two permissions, no sensors and no contacts. **Household medical records still
cannot leave the device** — nothing in the module reads a profile, a reading, a symptom, a dose or an
illness and sends it anywhere, and there is no code path that could.

- **`INTERNET`** — the medicine cabinet's drug lookup, and nothing else. `data/net/DrugLookupClient`
  is the only class in the module that opens a connection; it is only ever called because somebody
  pressed Search or Refresh; and the only things it can send are **the search term typed into the box
  and an RxNorm concept id**. It asks two public, keyless U.S. government references a question about
  a *product*, of the kind anyone could type into a search engine, and caches the answer locally so it
  isn't asked twice. Nothing runs on a timer, at startup, or in the background.
- **`POST_NOTIFICATIONS`** — medication reminders the user sets up per medicine. Nothing is scheduled
  until a reminder is turned on.

The module used to declare no permissions at all, and the promise behind that has not changed — what
crosses the wire is a question about a bottle, never a fact about a person. The manifest says so at
the point of declaration, which is where anyone reading the module will look first.

Advisor can read Health, but only behind the same explicit per-app gate as every other source, denied
by default. `HealthKnowledgeSource` is read-only and **names the person in every document it
produces** — the retrieval corpus is flat text with no per-row scoping, so a document that says
"38.4 at 21:00" without saying whose is one that can be retrieved into an answer about the wrong
person. Recent readings are listed individually and older ones characterised, so a year of
temperatures can't drown the rest of the corpus, and each temperature carries Health's own assessment
rather than inviting the model to form a second opinion.

## Tests

Pure-JVM suites under `health/src/test` (run with `gradle :health:testDebugUnitTest`) — 84 tests:

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
- `CabinetTest` — the printed expiry day itself still counting as in date, a month-only date running to
  the end of that month, an unparseable date leaving the item undated rather than wrongly expired, a
  dose in a different unit never being converted, "low" meaning the threshold you set or else "not
  enough for one more dose", and the worst-news-first ordering.
- `DoseReminderTest` — the next set time today, the roll to tomorrow once today's are past, a when-due
  reminder that lapses rather than nagging about a medicine nobody is taking, a late wake-up that still
  notifies and a hopelessly late one that doesn't, a window re-armed by somebody else's dose staying
  quiet, and an unparseable time being dropped rather than defaulted.
- `DrugLookupParserTest` — products sorted ahead of bare ingredients, suppressed and non-English
  concepts dropped, the approximate search keeping the best score per concept, a numeric DEA schedule
  written out and an unscheduled one saying nothing, label sections kept in reading order with the
  label's own words untouched, RxNorm keeping identity while openFDA fills in what it has no opinion
  about, and malformed JSON parsing to nothing rather than throwing.
