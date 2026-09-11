# Health — the household health tracker

Health is the suite's **who's-ill-and-what-have-we-given-them** app: a profile per person, the
temperatures and other readings taken for each of them, the symptoms they've got, the medicines
they're on with the label's own dose rules, the medicine cabinet those come out of, the illnesses all
of it hangs off, the insurance that pays for it, the doctors who provide it, and — since the Record
tab — the standing facts that are true between illnesses: what they must not be given, what they
already have, what they've been vaccinated against, and the paperwork behind all of it. It is a hosted
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
| **Vitals** | The measurement history. A curve for whichever measurement you pick — temperature with the fever line marked, or a weight, blood pressure, pulse, breathing rate or oxygen trend — plotted against real time, plus every reading in one list. Tapping any of them corrects it. |
| **Meds** | The **medicine cabinet**, in two halves. *Cabinet* is the household's actual stock — every bottle and box, whether it's still in date, whether there's enough left, where it lives, and everyone who takes it with their own dose and live dose window. *[Name]'s medicines* is the per-person regimen: the spacing and daily limits **from their own labels**, each showing its window — due now, wait *this* long, or the day's allowance is spent — plus reminders and the full history of doses given. |
| **Information** | **Who this person is, what is normal for them, and what has gone wrong.** Their name, relationship and age as the directory has them; **their own usual temperature** and the medical note that goes with it — the two things Health owns outright and never publishes; whether temperatures read in °C or °F and weights in kg or lb; and then the illnesses. Episodes past and present, each readable back two ways: a **summary** (how long, how high it peaked, which way it's going, what was given, what's still going) and a **history** — everything that was done, hour by hour, day by day. Anything that wasn't recorded at the time can be added afterwards, including an illness that has already been and gone. Plus the care log. |
| **Record** | **What is true about a person between illnesses.** *Allergies* — structured, ordered worst-first, and checked against any medicine being added. *Conditions* — the long-running things an illness episode could never hold. *Vaccines* — the card in the drawer, typed up, reported as what is **recorded** and never as "up to date". *Documents* — the paperwork, stored exactly as it arrived and never read. |
| **Care** | **Who pays for this, and who do we take her to.** *Cards* is the household's insurance as copied off the card — each person's own member number on the household's policy, whether the coverage is current, photographs of the card, and **a PDF of it on demand**. *Doctors* is the care team, which belongs to the household and **not** to the policy: each shown with where they stand against this person's coverage, read out of the whole history of checks rather than a single flag. |

## Profiles — why they're the spine, not a setting

Every row in Health belongs to a profile. There is no ambient "current person" at the data layer —
every query takes the profile id — and the profile bar sits on top of every tab rather than hiding in
a menu. A temperature filed against the wrong child is worse than one never recorded, so who you're
looking at is always on screen and always one tap to change.

**Health does not own who these people are.** People does — see **[PEOPLE.md](PEOPLE.md)** — and
Health joins that sync seam as the peer that takes only the people the directory has ticked as
**household members**. Names, relationships and birth dates stay in step with People and LifeOps,
while everything medical stays here.

**So Health has no household screen at all.** There is no People tab, no add-a-person form and no
remove-a-person button: a second place to add, rename or remove somebody would be a second answer to
"who lives here", which is exactly what the seam exists to prevent. Switching between people is the
profile bar's job on every tab, and the bar's last chip opens the People app for everything else.

What Health *does* own about a person — their usual temperature and their medical note — lives on the
**Information** tab, because neither is ever published and no other app has a column for either.
Removing somebody is the directory's act: People deletes them, the seam carries a `deleted` packet,
and `PersonMerge` **archives** the profile here rather than cascading. An archived profile vanishes
from every screen, and the medical history survives on disk — losing a household member's entire
record because another app dropped a row is not a recoverable mistake, and the seam says so at the
point of declaration.

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

The cascade that removes a person's readings, symptoms, medicines, doses, illnesses, care notes and
standing record in one transaction still exists in the repository and **nothing calls it**. That is
the right number of callers for now: a purge is a different feature from a removal, and it needs a
confirmation in the app that holds the data rather than a side effect in the app that doesn't.

## The judgements Health makes

The two below are the oldest and the most load-bearing; the allergy check added with the Record tab
is the third, and is described with it. All of them live in `health/logic/` as framework-free Kotlin, unit-tested on the JVM, so they are
provable without a device — and so every surface (screens, episode summaries, Advisor answers) gives
the same answer to the same question.

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

### Is that a number a body produces?

`logic/Vitals` carries one `VitalRange` per measurement — the bounds a believable value falls in, in
the canonical unit, and the sentence to say when a typed value doesn't. Temperature and weight had
their own bounds inside their own parsers from the start; heart rate, blood pressure, oxygen and
breathing rate had none at all, which meant "920" typed into the oxygen field saved cleanly, charted,
and was handed to Advisor as a fact about somebody's body. All seven now come from the same place,
and the two parsers that convert units read their bounds from it rather than keeping their own.

**They catch typing, not patients.** Every range is far wider than anything a clinician would call
normal, because the job is to reject numbers no living person produces — never to argue with a
reading somebody actually took. A resting pulse of 38 in a cyclist and one of 190 in a feverish
toddler are both real and both go in. `Fever` is where Health says what a number *means*; this is
only where it says the number is a number.

Blood pressure is bounded as **two** ranges rather than one, because the commonest mistake with it is
the two numbers the wrong way round, and a single range admitting both could not notice.

The check happens where the number is typed, not where it is stored — the same contract
`logTemperature` always stated. Bounds belong at the point of entry, while the person who typed it is
still there to be told what was wrong with it.

### Can the next dose be given yet?

`DoseSchedule.evaluate(rule, history, now)` answers with `READY`, `WAIT` or `LIMIT_REACHED`, plus when
the wait ends. Two independent gates, and the later one wins:

- the **interval** gate — the last dose plus the label's minimum spacing;
- the **rolling 24-hour** gate — when the day's allowance (a dose count, a total amount, or both) is
  spent, nothing is allowed until the oldest dose in the window ages out of it.

The window rolls; it is not "since midnight". A day boundary is exactly where a naive counter lets a
fifth dose through. A rule with no limits never blocks — Health enforces what the label says, never a
restriction nobody typed in.

## Information — the person before the illness

The tab that used to be **Illness** is now **Information**, and the rename is a change of emphasis
rather than of contents.

"Is anyone ill right now" is the rarer question. The one asked far more often — and that every reading
in the app is implicitly measured against — is **what does normal look like for this person**. A 37.6
means one thing for somebody who runs at 36.4 and another for somebody who runs at 37.1, and the
number that settles it used to be buried in a profile editor on a tab about the household.

So the tab reads top-down as the answer to "tell me about her":

1. **Who they are** — relationship and age, as the directory has them, and read-only here. Offering an
   editable name in Health would invite somebody to change it and find it changed back on the next
   sync round. A missing birth date says so in as many words, because it is the one field here that is
   load-bearing rather than decorative: it is what makes the fever thresholds age-aware.
2. **What's normal for them** — their own usual temperature, and the medical note. Neither is ever
   published over the seam, neither has a column anywhere else in the suite, and this is the only
   screen in the household that can change them.
3. **Display** — °C or °F, and kg or lb. It lives here rather than in a settings screen Health
   otherwise doesn't have, because choosing the units and recording that somebody runs at 36.4 are the
   same act: saying how numbers should read for this household. The two are separate choices rather
   than one metric/imperial switch, because households are genuinely mixed — plenty of people read a
   fever in °C and their own weight in pounds, and a single toggle would force them to be wrong about
   one of them. Readings are always stored in Celsius and kilograms, so changing either never rewrites
   anything.
4. **The illnesses**, exactly as before, with the care log underneath.

The baseline is typed in whatever unit the household is using and converted on the way in — somebody
who reads temperatures in Fahrenheit does not know their child's normal in Celsius. A weight recorded
on the Vitals tab makes the same bargain against pounds and kilograms.

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

## Coverage — the card, not the policy

The Care tab's first half is insurance, and the single most important thing about it is what it
refuses to be.

**Health stores a card. It does not store a policy.** There is no field for a deductible, a copay, a
coinsurance share or an out-of-pocket maximum, and there never will be. Those are the terms of an
eighty-page legal document that vary by service, by network tier and by how much of the year has
gone; an app with a box marked "copay: $30" is inviting a household to plan around a number nobody
checked. What is here is what is *printed on the card* — the insurer, the plan, the member number,
the group, the payer id, the Rx BIN/PCN/Group, the dates, and the phone numbers on the back. That is
the part a person actually needs at a desk, and it is a part an app can hold honestly.

The split follows the cabinet's:

- **`insurance_plans` is household-scoped.** A family policy is *one* policy: one carrier, one group
  number, one set of phone numbers, one directory. Copying it per person would be four rows to keep
  in step and three of them wrong by renewal.
- **`insurance_members` is per person.** Their own member number and person code on the household's
  card, and their own dates where those differ — a baby added to a family plan in March is covered
  from March, not from the policy's January, so the member's dates win where they were recorded.

The only judgement made is whether the coverage is current, from the dates **as written down**. A
card with no dates is `UNKNOWN`, never "probably fine": most cards print an effective date and no end
date at all, and a plan with no printed end is exactly the one Health has no business declaring
active or lapsed. Ended sorts first, then not-yet-started, then ending soon — a wallet is read
top-down when something is wrong with it.

Member numbers are **masked in the list** (`••• 4567`) and shown in full on the card and in the
export. A member id is not a password, but it is the number a plan will read an account out against
over the phone, and printing it on a screen anybody can read over your shoulder is careless for no
gain. The card view is the thing you opened on purpose.

### The card as a PDF

The wallet card is the one health record a household is asked to **produce** rather than consult: at
a reception desk, at a pharmacy, on a form, emailed to a school before a trip. So photographs are
saved when they are attached — into `filesDir/insurance-cards`, with only the **file name** on the
row — and from then on the card can be turned into a PDF without asking for the picture again.

`card/InsuranceCardPdf` draws it at card size (ISO/IEC 7810 ID-1, 243 × 153 pt) in up to four pages:
the typed front, the typed back, then each photograph. **The typed faces come first even when there
are photographs**, and that is deliberate — a photograph of a card is authoritative and hard to read,
while typed fields are legible, selectable and searchable, and the person at the desk wants the
second thing. The photograph is the evidence behind it.

Both faces come from one `Insurance.card` layout, so the screen and the PDF cannot disagree about a
member number — the place that would show up is a reception desk. Blank fields are dropped rather
than rendered empty: a line reading "Group —" invites the reader to conclude the plan has no group
number, when all it means is that nobody typed one in.

Every page carries the same line:

> Copied into Health from your own card. Not issued by the insurer and not proof of coverage — check
> the real card, or ring member services, before it matters.

A PDF faithful enough to be useful is faithful enough to be mistaken for the card itself. Saying what
it is, on every page, is the same reflex as the fever disclaimer, and it is the reason the feature
can exist without pretending to be something it isn't.

Photos are stored as files rather than blobs because `health.db` is WAL-checkpointed and copied whole
by every backup — eight megabytes of JPEG in there would be copied every time anybody recorded a
temperature. They are in `filesDir`, not `cacheDir`: a card photo is a record the user chose to keep,
and the system may reclaim a cache at any time. The backup carries the directory alongside the
database, restoring the pictures **before** the rows that name them.

## The care team — deliberately not owned by the insurance

The Care tab's second half is doctors, and the design point is the separation.

**A doctor belongs to the household, not to the policy.** The plan changes every January; the
paediatrician doesn't. If the care team hung off the insurance, changing carriers would mean
re-entering every clinician in the house — and, worse, would throw away the check history that is the
only way to notice that the new plan doesn't cover somebody the old one did.

So `providers` is household-scoped and `provider_links` carries the per-person half: which person
sees them, and **in what capacity**. The role is a relationship rather than a job title, which is why
one paediatrician can be one child's *Primary care* and their cousin's *Specialist* without either
being wrong.

The field worth filling in is the **NPI** — the ten-digit national identifier printed on
prescriptions and after-visit summaries, published in the federal NPPES registry. With it, a
directory check is exact. Without it, a common surname can only ever come back as "couldn't tell them
apart". It is validated as it is typed (ten digits, Luhn over the `80840` issuer prefix), because a
mistyped NPI and a doctor who has left the network both come back as no results, and discovering the
transposed digit weeks later as a mysterious "not listed" helps nobody.

## Checking the network — what a directory can and can't tell you

Since the CMS Interoperability and Patient Access rule, payers publish their provider directory as a
**public, unauthenticated FHIR R4 API** — the Da Vinci **PDEX Plan-Net** profile. No key, no sign-in,
no member number, by design: it is a directory in the telephone-book sense, published so that anybody
can look a doctor up before booking. That is the only reason this feature can exist.

### No table of guessed endpoints

There is no national registry of these addresses, and **Health ships none of its own**. A wrong
endpoint quietly answering "not listed" for every doctor in the house is the worst outcome this
feature could produce — a confident fiction, which is exactly what the rest of this app refuses.

So the user pastes what the plan published (usually on the insurer's developer or interoperability
page) and Health does the honest thing: takes it at face value, tries the handful of paths a FHIR
server conventionally lives at underneath it, asks each for its `CapabilityStatement`, and reports
exactly what came back. Five candidates, not fifteen — enough to find the endpoint when somebody
pasted the page above it, few enough not to look like a scan.

The answers are told apart rather than collapsed, because they mean different things and one of them
is fixable by the user:

| | |
|---|---|
| **Directory reachable** | A FHIR server answered and publishes practitioners. |
| **Not a provider directory** | Something answered — usually a marketing page, or a FHIR endpoint for something else entirely. |
| **Nothing published here** | The server says there is nothing at that path. Ordinary while hunting for a base URL. |
| **Needs a sign-in** | Plan-Net is meant to be public, so this almost always means the URL is the **member portal**, not the directory. Worth saying, because it is the user's to fix. |
| **Couldn't reach it** | No network, DNS, timeout, or the payer's server is down. Not a fact about any doctor. |

When every candidate fails, the **most informative** failure is reported rather than the last one: a
401 on the address somebody actually pasted beats a 404 on a path Health invented.

### From a list of strangers to a verdict

`ProviderDirectory.judge` turns what came back into one outcome, and it is conservative on purpose —
being wrong in the generous direction is what sends somebody to an appointment they get billed
out-of-network for:

- **Nothing came back** → not listed. The directory answered; it does not have them.
- **The NPIs agree** → listed. One identifier, one clinician, no doubt — and **conflicting NPIs are
  certainty the other way**: an entry with the right name and somebody else's number is a different
  clinician with the same name, which is common, and calling it a match is precisely how an app tells
  a household their doctor is in network when he isn't.
- **Exactly one name matches** → listed. Names are compared as word *sets* with honorifics and
  credentials stripped, so "Okafor, Jane A" and "Dr. Jane Okafor" are the same person.
- **More than one matches**, or only a surname-and-initial does → **ambiguous**. Never "probably the
  first one". The whole reason the NPI field exists is to settle this, and quietly picking a match
  would hide the fact that it needs settling.
- **The listing is marked inactive** → not listed, and says so. Plan-Net's `active = false` is how a
  payer records a clinician who has stopped practising at that listing; reading it as a yes is what
  sends somebody to a closed office.

**"Listed" is not "covered".** A payer's directory covers every network it sells, so being in it and
being in *your* plan's network are different sentences. Plan-Net models membership on
`PractitionerRole.network`, so Health keeps the **network names** it was found under and shows them —
"Listed in Choice Plus PPO" rather than a bare yes.

### Why every check is kept

`network_checks` is **append-only**. Nothing overwrites a check and nothing prunes the old ones, and
that is the design rather than an oversight: a single "in network" flag cannot tell the difference
between a doctor who was never in the network and one who was in it until March, and that difference
is the most useful thing this feature produces.

`logic/NetworkStatus` derives the verdict from the whole list:

| Verdict | What it took to say it |
|---|---|
| **In network** | The current directory lists them — with the networks it listed them under. |
| **Was listed — not any more** | An earlier check found them; the latest doesn't. Usually means they have left the network, and it is a phone call to make *before* the appointment. |
| **Never listed** | Checked, and never found in any directory Health has asked. |
| **Couldn't tell them apart** | Several matched and nothing settled it. Their NPI would. |
| **Confirmed by phone** / **Told not in network** | Somebody rang and was told something. Kept as somebody's word with a date on it — it never becomes "in network". |
| **Couldn't check** | No directory, or it couldn't be reached. Not a verdict about anybody. |
| **Not checked** | Nobody has asked. Health has no opinion and says so. |

Three rules make those hold up:

1. **The most recent *decisive* check wins.** A directory that was down this morning does not
   overwrite the answer it gave last month — an outage is not evidence about a doctor, and treating
   it as one would flip a whole care team to "unknown" every time a payer's server hiccuped.
2. **A "not listed" is read against everything before it**, which is the only way "has left the
   network" and "never was in it" can be told apart.
3. **Ambiguity is never rounded up.** Four Dr Patels is not a yes — and a name that has *become*
   ambiguous unsettles an older yes, because the directory has since grown a second person with that
   name and the old answer may have been about either of them.

An answer older than 45 days is flagged as worth repeating: payers are required to keep these current
and are audited on how badly they manage it, so entries go stale in weeks. And a check made under a
policy the household has since left deliberately **stops counting** for a person no longer on it —
last year's network is not this year's, and letting an old carrier's yes stand under a new plan would
be the most convincing wrong answer the feature could give. The rows aren't deleted; they simply stop
speaking for a plan they were never about, and come back the moment somebody is put on that policy
again.

Individual checks can be deleted for the mis-taps — after a confirmation that says what goes with
one, because a check is evidence and "this doctor was in network last March" is exactly the fact
worth keeping. There is deliberately **no "clear history"**:
clearing it means making the app forget that a doctor used to be in network, which is the fact worth
keeping.

## The standing record — what is true between illnesses

Every other tab records something that **happened**. This one holds what simply **is**, and it exists
because the app's most safety-critical data used to live in a free-text note.

A profile's `notes` column was documented as holding "allergies, conditions, the doctor's number".
That is the one shape nothing can read back: a note cannot be listed, cannot be ordered by how badly
it went last time, and above all cannot be compared against the bottle somebody is holding at 3am.

### Allergies, and the one check Health is qualified to make

`allergies` carries the substance, the kind, the severity, what the reaction actually was, when it was
first noticed, and the RxNorm concept when it was recorded by lookup rather than typed. It is
per-person and never household-scoped — the least shareable fact in the app.

`logic/Allergies.check` compares what was written down against what the label says, and its limits are
the design:

- **No class inference, ever.** Health does not know that penicillin and amoxicillin are relatives,
  that a sulfa allergy has anything to do with a thiazide, or that one NSAID predicts the next. Those
  are real and they are *pharmacology* — a judgement this app is no more qualified to make than it is
  to read a dose off a label. A pharmacist is.
- **Whole words, not substrings.** "Codeine" does not fire on "hydrocodone", which is a different drug
  and exactly the false alarm that teaches a household to tap past the dialog. A recorded
  "amoxicillin" does match an ingredient printed as "amoxicillin trihydrate".
- **A product that says it is free of something is not read as containing it.** "Aspirin-Free" is
  printed on the box precisely for the person being warned; firing on it would be the most
  embarrassing failure available to the feature.
- **An identifier beats a string.** Matching RxNorm concept ids is certainty; matching an ingredient
  list is strong; matching a brand name is weak, and each warning says which it was and quotes the
  text it matched. A warning nobody can audit is one people learn to dismiss.
- **Only drug allergies take part.** Health holds a label's active ingredients, not its excipients, so
  it genuinely cannot tell whether a grape-flavoured suspension is a problem for a grape allergy. The
  allergy stays on the record for a human to read and is not pretended to have been checked. The form
  says so at the moment you pick a non-drug kind.

**An empty result is never an all-clear.** It means nothing recorded matched, and every surface that
renders it says that rather than showing a reassuring tick. That distinction is the whole feature:
"nothing recorded" and "she isn't allergic to this" are different sentences, and only one of them is
something this app is in a position to say.

The medicine form runs the check against whatever it currently names, as it is typed — so picking a
product from the drug lookup upgrades the check from matching a name to matching the label's own
ingredient list.

### Conditions — deliberately not episodes

Asthma, eczema, coeliac, a murmur somebody is watching. Health already had a container for illness and
it is precisely the wrong shape for these.

An episode is built around a bout with an end: it counts days from Day 1, escalates a fever into its
fourth day, and is held to one open row per person so "how long has this been going on" has a single
answer. Left open for nine years, an episode would report a four-thousandth day, block every future
illness that person has, and corrupt the one number the illness screen exists to give. So a condition
has an **onset** rather than a start and a [status] rather than an end, and it outlives every episode
filed alongside it.

Three statuses, because two would lose the useful middle: **active**, **in remission**, **resolved**. A
resolved condition stays on the record and stays visible — "she had this as a toddler" is the answer to
a question a doctor asks, and deleting it to tidy the list is how a record stops being one.

`monitorReadingType` is the chronic-care hook: the one measurement that matters for this condition —
oxygen for asthma, blood pressure for hypertension. Nullable, and null for most, because Health has no
table of which vital belongs to which diagnosis and will not invent one.

When somebody sets it, **the card shows that measurement** — the latest one, in the household's
units, and when it was taken: "Watched with: Weight · 17.4 kg, 3 days ago". Naming the number that
matters and then not showing it tells a household nothing it didn't already know, and sends it to
another tab to find out. When nothing of that kind has been recorded yet the card says so, rather
than going quiet. What it never says is whether the number is *good*: there is no table of what
normal looks like per diagnosis here either.

### Onset dates are recorded at the precision they're known

Nobody remembers the day their child's eczema started; plenty remember the year. So `logic/PartialDate`
accepts `2019`, `2019-03` and `2019-03-14`, and reads each back saying only as much as it knows —
"Since 2019", never "Since 1 January 2019". Padding a bare year invents a fact, and it is the kind that
later reads as a real anniversary.

A partial date rounds to the **start** of its period, which is the opposite of `Cabinet.parseExpiry`
and right for the same reason: an expiry is a deadline and runs to the end of its month, while an onset
is a beginning. Both round in the direction that cannot overstate what was written down.

### Nothing is parsed out of the old note

The v7 migration reads nothing out of `profiles.notes` and deletes nothing from it. Turning
"penicillin (hives), asthma, Dr Okafor 555-0101" into rows means guessing at exactly the data where a
wrong guess is worst — a mis-parsed allergy is a warning that never fires, or one that fires on the
doctor's surname. The note keeps saying what it always said, and the Record tab shows it under "Also on
this person's profile" so somebody can see what is in there and re-enter what should be checkable.

## The vaccination record — and the sentence it refuses to write

The card in the drawer, typed up. It is the one health document a household is repeatedly asked to
**produce** rather than consult: school, daycare, camp, a new practice, a visa, an employer.

`immunizations` holds the vaccine, when it was given, which dose in the series, the lot number for the
day there's a recall, the site, and who gave it.

**There is no "due", no "overdue" and no "up to date" anywhere in the feature, and no schedule behind
it.** That absence is the design, and it is the same line `DrugFacts` draws about dosing. An
immunisation schedule is not one list: it varies by country, by birth year, by risk group, by whether a
dose was given early, by which combination product was used, and by catch-up rules a clinician applies
with judgement. An app holding one hard-coded list would be confidently wrong for a family that moved
countries, for a premature baby, for anybody on an accelerated schedule — and "she's up to date" is
precisely the sentence somebody would act on without checking.

So Health says "3 doses recorded · latest March 2019". That is a fact about this household's paperwork.
"She has had all her jabs" is a claim about the world, and this app cannot see the world.

**Provenance is part of the record**, not metadata about it. `VaccineSource` distinguishes a dose
somebody watched being given from one copied off a card years later from one nobody has paperwork for —
the same principle that puts `createdAt` beside every event time elsewhere in Health. All three are
worth recording; they are not equally reliable, and the record says which is which rather than
presenting a transcription as an observation.

Series are grouped on the name compared as **letters and digits only**, so "M.M.R.", "MMR" and
"Hepatitis-B" don't split a child's record into halves on a form. That is more aggressive than the
allergy matcher, deliberately: there a false match is a warning that fires on the wrong drug, here a
false *split* is a school form with a gap in it. The two guard against opposite mistakes.

A transcribed card very often carries only a month, so vaccination dates take the same partial-date
rule as onsets.

## Documents — stored, never read

Health could already keep one kind of document: a photograph of an insurance card. That mechanism
turned out to be the right one — bytes beside the database rather than inside it, only a file name on
the row, the directory carried by the backup and restored *before* the rows that name it. `documents`
generalises it to everything else a household is handed: after-visit summaries, lab results, referral
letters, imaging reports, school forms, the bill that arrived three weeks later.

**Health stores documents and reads none of them.** No OCR, no text extraction, no interpretation.
Every fact Health holds about a document — what kind it is, what date it carries, who it is about — is
something a person typed. An app that parsed a lab report would be interpreting a medical document, and
this one is not qualified to.

That is also what keeps the seam honest for later. Reading an EOB into claim rows is a real and useful
feature, but it is *parsing*, and parsing belongs to the change that owns it rather than being smuggled
in underneath a file picker. `DocumentKind.BILL` exists so a household can file the PDF; nothing reads
it.

Three decisions worth knowing:

- **`profileId` is nullable**, unlike every other per-person table in the schema. A lab result is about
  one person; an insurance statement or a registration pack is about the house. Forcing every document
  onto somebody would file the family's paperwork under whoever happened to be selected when it was
  scanned. The two lists are shown separately for the same reason.
- **Pictures are re-encoded; everything else is copied byte for byte.** A photograph of a document is a
  photograph — twelve megapixels of a sheet of A4, most of it noise — so it is downsampled. A PDF *is*
  the document, very often the practice's own file with the letterhead on it, and re-encoding it would
  produce something different from what the household was given. "Different from what the practice
  sent" is the one property a record must not have.
- **The bytes are secured before any question is asked.** A picker's URI permission can lapse the
  moment it closes, so the file is copied into the store first and the form is filled in over the top
  of an attachment that is already safe. Cancelling deletes the copy rather than leaving it behind.

Opening a document copies it into `cacheDir/exports` and hands *that* to the share sheet.
`HealthFileProvider` exposes only the export directory — widening it to the store would make every lab
result in the house readable by anything that could guess a URI. A document leaves only when somebody
asks it to, exactly as the insurance card PDF does.

## Illnesses — the thing the rows hang off

Starting an episode is the difference between a scatter of readings and a story. While one is open,
**everything recorded is filed against it automatically** — by the time the record *happened*, not by
which episode is open when it is typed in, which is what lets the history be filled in afterwards
without landing last month's flu inside today's cold. Starting one also adopts the
unattached readings, doses and symptoms from the previous 12 hours — an illness is nearly always
noticed after the first temperature was taken, and a record you have to assemble by hand afterwards is
one nobody assembles. One open episode per person, so "how long has this been going on" has exactly
one answer.

`EpisodeSummaries.summarize` reads the whole thing back: duration, peak, latest, trend, how long the
*current* fever run has lasted (a fever that settles and returns is reported as the new run it is),
active vs. resolved symptoms, doses given — and the standing-back observations no single reading can
make, like a fever heading into its fourth day, which escalates the episode's care level on its own.

## The history — everything that was done, and when

An episode can be read back two ways, because people ask two different questions about an illness.

`EpisodeSummaries.summarize` answers **how did it go** — the peak, the trend, how long the fever has
run. `Timeline.build` answers the other one: **what actually happened, and when?** That is the version
a doctor asks for in a waiting room, the version a second parent taking over needs, and precisely the
version the person who was up all three nights cannot produce from memory.

It merges all four kinds of record — readings, symptoms, doses, care notes — into one list, grouped
by day of the illness. The day it started is **Day 1**, because that is how everybody counts it out
loud and it is the number the question "how long has this been going on?" is really asking for. The
episode's own start and end are entries too, so the bookends are visible. Nothing is summarised or
dropped — the whole value of it is that it is complete.

The list runs **one way all the way down**, and the reader picks which — newest first by default,
oldest first for reading the illness as a story. It used to run days newest-first with each day
reading forwards, which sounds right and isn't: it broke the one promise a timeline makes, that
moving one row moves you one step in time. A reading at 23:55 and the next one at 00:05 are ten
minutes apart and were landing at opposite ends of the screen with a whole day between them —
midnight being exactly the stretch somebody is trying to read. Whichever direction is chosen, it
applies to the days, the entries inside them, and the tie-break between two records sharing a minute.

Temperatures and weights in it are written in the household's display units, like every other number
in the app. They are stored in Celsius and kilograms and converted on the way out (see
`logic/Temperature` and `logic/Weight`), so the history is handed both units when it is built, and
rebuilt when either changes with an illness open.

### Filling it in afterwards

A history you can only write at the moment things happen is a history that mostly doesn't get
written. So every record dialog now asks **when**, and every one of them defaults to "now" at no cost
in taps. Nothing about recording a temperature as it is taken got slower.

*Every one* now includes the measurement dialog on the Vitals tab — a weight, a blood pressure, an
oxygen saturation or a breathing rate. It was the one that never asked, and the omission cost more
than a convenience: readings are filed against the illness that was open **at the instant they were
taken**, so one typed up on Sunday for Friday was landing in today's story, or in none. The number
was right and the illness it belonged to was wrong, which is the failure nobody notices, because
nothing about the row looks incorrect.

What that buys is the case this exists for: the 2am dose typed up over breakfast, the doctor's call
on day three, an entire illness that was never recorded at all. Set an episode's dates to when it
actually ran and fill the rest in from memory.

Two things make this safe rather than merely possible:

- **Records are filed by when they happened, not by what's open now.** `episodeIdAt` finds the
  episode whose span contains the instant. The two answers agree for anything recorded live and
  diverge the moment anything is backdated — file by "what's open now" and last month's flu ends up
  inside today's cold, which makes every summary built on top of it wrong.
- **Moving an episode's dates re-files its records, both ways.** Widening a span adopts unattached
  records that now fall inside; narrowing it releases records that now fall outside. Records filed
  under a *different* illness are never touched, and nothing is ever deleted. The span always means
  what it says.

### Saying which is which

A record made at the time and a record made from memory are both worth having and are **not** equally
reliable. Presenting a reconstruction as an observation would be a quiet lie about the evidence — the
one thing an app whose whole pitch is "keep the receipts" cannot do.

So doses, symptoms and care notes now carry `createdAt` alongside their event time (readings always
have), and an entry more than **half an hour** apart reads "written 7h later". Half an hour because
finishing with the thermometer, settling a child and *then* opening the app is still recording at the
time — flagging that would attach the note to nearly everything and make it mean nothing.

It is shown as a note, not a warning: filling the history in is the encouraged thing to do, and the
header says how much of it was added afterwards so a reader knows what they are relying on. Rows
written before Health tracked this say **nothing** — the honest answer there is that it doesn't know,
and a badge either way would be inventing a fact about how the row was entered.

Nothing in the future can be entered. The date picker won't offer it and the time step refuses it: a
temperature that hasn't been taken yet is a typo, and `DoseSchedule` takes future-dated doses
seriously enough to ignore them for exactly that reason.

## Module layout

```
:health (Android library, com.health.app)
├── logic/            pure JVM, unit-tested — no Android imports
│   ├── Temperature      °C/°F conversion, tolerant parsing, plausibility bounds, formatting
│   ├── Weight           kg/lb conversion, tolerant parsing, plausibility bounds, formatting
│   ├── Vitals           what a believable measurement is, per kind — one place, shared by both
│   │                    unit-converting parsers and by every field that types a number
│   ├── Fever            sites, bands, age-aware red flags, the standing disclaimer
│   ├── DoseSchedule     interval + rolling-24h dose windows and countdown formatting
│   ├── DoseReminder     when a reminder next fires, in both modes, against an injected clock
│   ├── Cabinet          expiry and stock verdicts, doses remaining, same-unit rule
│   ├── Allergies        the medicine check: whole-word matching, no class inference, never an all-clear
│   ├── Conditions       long-running conditions: status order, how long they've been going
│   ├── Immunizations    doses grouped into series — what is *recorded*, never what is due
│   ├── Documents        document kinds, file naming, sizes, newest-first ordering
│   ├── PartialDate      a date at the precision somebody actually knew — 2019, 2019-03, 2019-03-14
│   ├── Insurance        coverage-current verdict, and the one card layout both renderers draw
│   ├── ProviderDirectory  candidate endpoints, NPI validation, name matching, the check verdict
│   ├── NetworkStatus    a doctor's standing, derived from the whole history of checks
│   ├── FhirDirectoryParser  Plan-Net JSON → capability, practitioners, roles and networks
│   ├── DrugFacts        the monograph types — candidates, label sections, attribution
│   ├── RxNormParser     RxNorm JSON → candidates, ingredients, strengths, schedule
│   ├── OpenFdaParser    openFDA JSON → the label's own sections, in reading order
│   ├── EpisodeSummary   an illness read back: peak, trend, fever run, advice
│   ├── Timeline         everything that happened, in order, by day — and what was filled in later
│   └── Age              birth date → months/years, and the label people actually use
├── data/             Room (HealthDatabase + HealthMigrations, entities, HealthDao) + repository + prefs
│   ├── model/        domain types with the string columns resolved into enums
│   ├── net/          DrugLookupClient, ProviderDirectoryClient — the only two classes that connect
│   ├── store/        CardImageStore, DocumentStore — files beside the database rather than in it
│   │                 (+ ImageDownsampler, the two-pass decode both of them share)
│   ├── repository/   HealthRepository — what happens; Mappers.kt — what a row means. Every
│   │                 judgement is delegated to logic/, and a mapper never invents a value.
│   │                 RestorableDelete — a delete that can be put back *exactly*, and the line
│   │                 that decides which deletes are offered back rather than confirmed
│   └── prefs/        HealthPrefs — selected person + display units (deliberately not in the db)
├── card/             InsuranceCardPdf — the wallet card as a card-sized PDF, on demand
├── reminder/         MedicationReminderWorker + scheduler (WorkManager; timing lives in logic/)
├── ui/               Compose, one package per tab, each with its own `*ViewModel.kt`:
│                     today · vitals · meds · information · record · coverage (+ common, theme).
│                     common/ carries the shared vocabulary, Undo (the offer-it-back snackbar) and
│                     ConfirmDeleteDialog (the deletes that ask first).
│                     No `people/` — Health has no household screen; the People app owns the
│                     household and Health is a peer on its sync seam.
│   └── information/  InformationScreen (the shell) · PersonCards (who they are, what is normal,
│                     the °C/°F and kg/lb choices) · EpisodeCards · EpisodeHistory · EpisodeDialogs ·
│                     BackfillRecord (the vocabulary the dialogs and the view model share)
├── backup/           HealthBackupContributor (health.db + health_* prefs + insurance-cards/ + documents/)
├── HealthFileProvider.kt  exposes cacheDir/exports only — the card PDF, and on-request copies of
│                          stored documents. Never the record directories themselves.
├── HealthApp.kt      tiny runtime container (install/get), like LogisticsApp
└── MainActivity.kt   tabbed shell over the one runtime
```

Health owns temperatures, doses and illnesses outright and shares them the two ways the suite already
shares things: a backup contributor and a read-only Advisor source. It does **not** own the people
they are recorded against, so it depends on `:people` for the sync contract — the packet, binder and
merge rule — and joins that seam as the peer that creates only for ticked household members. It does
not read People's database; the roster is replicated over a mailbox, not borrowed live.

## Storage

One `health.db`, nineteen tables:

- **`profile_tombstones`** — profiles removed here, kept only long enough to publish the un-tick so
  the next round doesn't hand the person straight back. See PEOPLE.md.
- **`profiles`** — the people. Name, relationship, birth date (ISO `yyyy-MM-dd`), colour, their own
  baseline temperature, notes (allergies, conditions, the doctor's number), the `household` tick,
  plus the `personKey` and
  `syncVersion` that make a profile a peer's view of a household member. *Schema v2 adds those two
  via `MIGRATION_1_2`; the colour, baseline and notes are Health's own and never leave it.*
- **`readings`** — every measurement, in the canonical unit for its type (temperature always in °C,
  weight always in kg), with the site for temperatures and a nullable episode link.
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
- **`insurance_plans`** — one policy as copied off the card: carrier, plan, group, payer id, Rx
  BIN/PCN/Group, the phone numbers on the back, the dates, and the provider-directory address with
  whatever the last probe found at it. *Schema v6, household-scoped — a family policy is one policy.*
- **`insurance_members`** — one person's place on one policy: their member number, person code,
  subscriber and their own dates. Card photographs are **file names** under `insurance-cards/`, never
  blobs; the plan's own photos are the fallback, for the single card that arrives for the family.
- **`providers`** — a doctor, dentist or practice, with their NPI. *Household-scoped and deliberately
  **not** owned by a plan: the policy changes every January and the paediatrician doesn't.*
- **`provider_links`** — who sees whom, in what capacity. Many-to-many in both directions, because
  both happen: one paediatrician for three children, three clinicians for one child.
- **`network_checks`** — **append-only**. What a directory said about one provider under one plan at
  one moment, plus what somebody was told on the phone. Never overwritten, because the earlier
  answers are the only thing that can tell "has left the network" apart from "never was in it".
- **`allergies`** — substance, kind, severity, the reaction, when it was first noticed, and the RxNorm
  concept when it was recorded by lookup. Per-person and never household-scoped: the least shareable
  fact in the app. *Schema v7.*
- **`conditions`** — the long-running things, with an onset rather than a start and a status rather
  than an end. Points optionally at the clinician who manages it and at the one reading that matters
  for it. *Schema v7.*
- **`immunizations`** — one recorded dose: the vaccine, when, which dose in the series, the lot number,
  and **where the record came from**. No "due" column, and there will not be one. *Schema v8.*
- **`documents`** — the paperwork. Only a **file name** under `documents/`, never bytes. `profileId` is
  **nullable** — the one per-person table where it is — because a statement belongs to the house and a
  lab result belongs to a person. *Schema v9.*

*Schema v5 adds `createdAt` to `doses`, `symptoms` and `care_notes` — when the **row** was written, as
against when the thing happened. Nullable, with no backfill: every row that predates the column was
written by an app that could only record the present, but "almost certainly recorded live" is an
assumption, and inventing one for thousands of existing rows to make a badge tidy is the kind of quiet
fiction this app refuses. Null means "Health doesn't know when this was entered", and the history says
so by saying nothing. Readings have carried a non-null `createdAt` since v1.*

Two conventions run through them: **instants are epoch millis, dates are ISO strings** (a moment gets
subtracted and windowed; a birth date must not shift across time zones), and **every row about a
person carries its profile id** — there is no ambient "current person" at the data layer.

The household-scoped tables are the deliberate exception, and the exception is the design.
`cabinet_items` and `drug_facts` are about *things* — a bottle is a household possession and a drug
label is a fact about a product. `insurance_plans` and `providers` are the same shape one layer up: a
family policy is one contract several people are named on, and a doctor is one person several people
see. Scoping any of them to a profile would mean one row per person, one expiry date or one renewal
date per person, and most of them wrong within a month.

*Schema v6 adds the five coverage tables, and touches nothing that existed. A household that never
opens the Care tab has five empty tables and no other change at all.*

*Schema v7 adds `allergies` and `conditions`, v8 adds `immunizations`, v9 adds `documents` — and none
of them touches a column that existed. In particular v7 **parses nothing out of `profiles.notes` and
deletes nothing from it**: reading a free-text note into structured allergy rows means guessing at
exactly the data where a wrong guess is worst. The note is surfaced on the Record tab instead, so a
household can see what is in there and re-enter what should be checkable.*

Cross-table links are plain nullable ids rather than foreign keys — a reading taken before anyone
declared an illness is still a real reading, deleting a medicine must not delete the record that a
dose of it was given, and throwing a bottle away must not delete either the regimens given from it or
the doses recorded against it.

## The chart — any measurement, one line

The curve is deliberately plain: a line, a dot per reading, and one dashed rule where there is a
threshold worth drawing, because the only question a chart like this is asked is *is it above the
line, and is it going up or down*. Points are plotted against **real time** rather than reading
number, so the gap where everybody slept looks like a gap.

It drew temperatures and nothing else for most of the app's life, which left the household tracking a
weight for a thyroid problem or a blood pressure for hypertension reading a column of numbers and
doing the trend in their head — the one job a chart exists for. Any measurement with two readings is
now drawable and the picker appears once there is a choice to make; a household that only takes
temperatures is never asked which chart it wants.

**Only temperature brings a threshold and flagged points with it.** `Fever` is a judgement from
published thresholds, and Health has one of those for body temperature and none for the rest —
colouring an oxygen saturation red would be the app inventing a clinical opinion it does not have.
The other charts say what was measured and when, and leave the reading to the reader.

A blood pressure draws **both** of its numbers, because a systolic on its own is not a blood
pressure.

## Correcting a reading

A record you can only delete is a record you re-type. Tapping any row in the Vitals history reopens
**the same dialog that recorded it**, filled in — not a reduced one, because a 384 typed for 38.4 is
wrong in exactly the way a new reading can be wrong, and a household that can only delete and
re-enter it loses the site, the note and the time along with the typo. The kind is the one thing a
correction can't change: a heart rate that should have been a weight isn't a typo in that row, it is
a different row.

The row keeps its id, so nothing pointing at it is disturbed, and the illness it belongs to is
**only** re-derived when the time moves. That restraint matters more than it looks: starting an
illness adopts the readings taken in the hours before it was declared, so a reading can legitimately
sit inside an episode that began after it — and a rule that recomputed the link on every edit would
evict exactly those the moment somebody fixed a typo in a note.

## Deleting — offered back, or asked about first

Every Delete button in Health used to fire on the first tap, with no confirmation and no way back.
That is the wrong default for this app in particular: its records are typed once, often at 3am, and
most of them cannot be reconstructed afterwards. Nobody remembers what the thermometer said on
Tuesday.

There are two honest answers to that, and which one a delete gets is decided by a single question —
**can it be put back exactly?**

- **Offered back.** A reading, a dose, a medicine, a care note, an allergy, a condition, a
  vaccination. The row is read before it is dropped and returned to the caller as a
  `RestorableDelete`, which the screen offers as an "Undo" snackbar. It goes back under **its own
  id**, so everything that pointed at it — a document filed against a condition, a certificate
  against a vaccine dose — finds it again. The restore is the inverse of the whole delete rather
  than of the row write: deleting a dose puts its stock back in the bottle, so undoing it draws that
  stock out again.
- **Asked about first.** A document, an insurance policy, somebody's membership of one, a doctor, a
  bottle, an illness, a recorded network check. Each of these takes something with it that no row
  restore would bring back — the file behind a document, the photographs of a card, the checks
  recorded against a doctor, the links from a bottle to the medicines given from it — so they get a
  dialog that says **what else goes**, in the same sentence as what is being deleted. "Delete this?"
  teaches somebody to tap Delete without reading it; "the stored file goes with the record, and
  Health cannot get it back" does not.

The line matters more than either mechanism. An undo that quietly put back less than it took would
be worse than no undo at all, because the household would stop checking — so a delete that cannot
restore exactly is never offered back, it asks.

Undo is the better answer wherever it is available, because it costs the common case nothing: a
household deleting a mis-typed reading taps once and moves on, and only the one who deleted the
wrong row pays anything. A confirm dialog charges all of them for the mistake of a few. The snackbar
is deliberately shown on the long duration rather than the default four seconds — this is the window
in which somebody realises what they have just done.

## Backup

`HealthBackupContributor` (registered as `AppId.HEALTH`) copies the whole `health.db` into the sandbox
archive and swaps it back on restore — complete by construction, the same approach LifeOps, Citation
and Logistics use. It also carries Health's own `health_*` preferences (selected person, display
units) and, like LifeOps' contributor, touches **only** files matching its own prefix: the hosted apps
share one `shared_prefs/` directory. The manifest's data version is read from
`HEALTH_DB_VERSION` rather than hand-copied, so it cannot drift from the schema.

**The two file directories go with it.** Insurance card photographs in `filesDir/insurance-cards` and
the household's paperwork in `filesDir/documents` are what Health keeps outside the database — a
couple of megabytes each, with only the file name on the row — so each directory is copied entry by
entry and restored the same way, *before* the database that names them. A backup that carried the row
and not the file would restore a card, or an after-visit summary, pointing at nothing: worse than not
backing it up at all, because the app would look like it had the document and quietly wouldn't.

Restoring also **re-arms every medication reminder** against the database that has just arrived. The
work queue survives the restore and still refers to the medicines of the database that was replaced,
so it is cancelled wholesale and rebuilt from the restored rows — the only version of this that can't
leave somebody nudged about a medicine they don't have, or not nudged about one they do.

## Privacy

**The promise is that nothing about a person leaves this device.** Not a name, not a temperature, not
a symptom, not a dose, not an illness. Health held that by declaring no permissions at all, which was
the simplest possible way to keep it — but the permission count was the *means*, never the promise
itself, and it is worth writing the real one down before reading further.

The test is therefore not "does this touch the network" but **"could this request tell anyone
something about a member of this household?"** And that is the line the drug lookup sits on the right
side of:

| | |
|---|---|
| *"What is acetaminophen oral suspension, and what does its label say?"* | A question about a **product**. Anyone could type it into a search engine. It says nothing about who is asking or why. |
| *"Does this public directory list Dr Okafor?"* | A question about a **practitioner** — the question the directory was published to answer, for anybody who asks. |
| *"This person takes these medicines"* / *"Dr Okafor is my daughter's paediatrician"* | Questions about a **person**. They never leave. |

Health asks the first two and cannot ask the third. **The Record tab adds nothing to this list.**
Allergies, conditions, vaccination records and documents are the most identifying data in the app and
there is no code path from any of them to the network — no lookup, no upload, no sync. The tab holds
no client and makes no request, ever.

Two permissions, no sensors, no contacts:

- **`INTERNET`** — two features, and nothing else:
  - `data/net/DrugLookupClient` takes a search term and an RxNorm concept id. There is no parameter,
    and no code path, by which a profile, a reading or a dose could reach it. It asks two public,
    keyless U.S. government references — RxNorm and openFDA — and caches the answer locally so the
    same question isn't asked twice.
  - `data/net/ProviderDirectoryClient` takes a base URL, a practitioner's **surname** and a
    practitioner's **NPI**. That's the complete list of what can cross the wire, and all three are
    facts about a URL or about a doctor: the NPI is the national identifier printed on prescriptions
    and published in the federal NPPES registry. **The member id in particular is never sent, and
    cannot be** — `findPractitioner` holds no reference to a plan, a membership or a profile. The
    insurance card itself is stored, rendered and exported entirely on-device; the only way one
    leaves is the PDF the user explicitly shares.

  Neither runs on a timer, at startup, or in the background. A household that never opens the search
  and never presses Check makes no request at all.
- **`POST_NOTIFICATIONS`** — medication reminders the user sets up per medicine. Nothing is scheduled
  until a reminder is turned on, and the notification is composed and delivered entirely on-device.

The manifest states all of this at the point of declaration, which is where anyone auditing the
module will look first.

Advisor can read Health, but only behind the same explicit per-app gate as every other source, denied
by default. `HealthKnowledgeSource` is read-only and **names the person in every document it
produces** — the retrieval corpus is flat text with no per-row scoping, so a document that says
"38.4 at 21:00" without saying whose is one that can be retrieved into an answer about the wrong
person. Recent readings are listed individually and older ones characterised, so a year of
temperatures can't drown the rest of the corpus, and each temperature carries Health's own assessment
rather than inviting the model to form a second opinion.

**Documents never leave the device except when you send one.** They live in `filesDir/documents`, and
`HealthFileProvider` exposes only `cacheDir/exports` — a copy written at the moment somebody asks to
open or share a particular document. Widening the provider to the store would make every lab result in
the house readable by anything that could guess a URI. Nothing reads the contents of a stored file
either: no OCR, no extraction, no indexing.

**Coverage is deliberately not published to Advisor.** Member numbers, group numbers and card
photographs are identifiers rather than health history — they answer nothing an assistant is useful
for, and a retrieval corpus is flat text that ends up quoted back into answers. The care team is left
out for the same reason: "who is her doctor" is a question with a name in it, and the corpus is not
the place for one. The Care tab's records stay in the Care tab.

## Tests

Pure-JVM suites under `health/src/test` (run with `gradle :health:testDebugUnitTest`) — 248 tests.
Four stand a context up with Robolectric — `RestorableDeleteTest`, `BackfilledReadingTest`,
`LatestReadingsTest` and `CorrectedReadingTest` — because what they have to prove is what the
*database* looks like afterwards, which is not a claim reasoning about the code can settle. The rest
are framework-free:

- `TemperatureTest` — conversion both ways, a *difference* converted as a difference (0.5 °C is
  0.9 °F, not 32.9), tolerant parsing (`" 38,4 °C "`), rejection of impossible values (`986`), and
  one-decimal formatting.
- `WeightTest` — conversion both ways against the avoirdupois pound, a round trip through pounds
  coming back to the same kilograms, tolerant parsing (`" 70,5 kg "`, `"100 lbs"`), rejection of
  weights no person has (`705` kg, `0`), a newborn still being believable, and one-decimal formatting
  with a change carrying its sign.
- `VitalsTest` — the three typos these bounds exist for (an oxygen saturation with a zero too many, a
  pulse that lost its decimal point, a systolic typed into the diastolic box), readings that are
  unusual but real going in anyway, each range's ends counting as inside it, blood pressure bounded
  as two numbers rather than one, and the two unit-converting parsers taking their bounds from here
  rather than keeping their own.
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
- `TimelineTest` — one consistent direction in both senses, days and entries together, with two
  readings either side of midnight landing next to each other whichever way round it runs; flipping a
  history sorting it rather than assuming which way it already ran; the day an illness started being
  Day 1, no day number invented when there is no episode to count from, a record written up hours
  later marked as filled in and one written at the time not, a row from before Health tracked it not
  being accused of anything, simultaneous entries reading in the order they happened, and days grouped
  in the reader's own zone rather than UTC.
- `ReadingTimelineEntryTest` — a temperature in the history written in the unit asked for, the site
  coming with it, the fever verdict being the same call in either unit, a weight written in the unit
  asked for, and the weight unit not leaking into the numbers it has no business changing.
- `RestorableDeleteTest` — an undone reading coming back under its own id rather than as a copy, an
  undone dose taking its stock back out of the bottle it was returned to, an undone condition being
  found again by the document still filed against it, and a second delete of the same row offering
  nothing back rather than an undo that would restore nothing.
- `BackfilledReadingTest` — a weight taken during a past illness filed against *that* illness rather
  than whichever one happens to be open now, and a reading from a week nobody called an illness
  belonging to none rather than being adopted by the nearest.
- `CorrectedReadingTest` — a corrected value keeping the row's id, its site and the illness that
  **adopted** it (the case a naive "re-derive the episode on every edit" would silently evict), a
  corrected *time* re-filing it against the illness it really happened in, and an edit to a row that
  has since been deleted changing nothing rather than resurrecting it.
- `LatestReadingsTest` — the most recent reading of each kind rather than the first one recorded,
  nothing invented for a kind nobody has measured, and one person's readings never answering for
  another's.
- `InsuranceTest` — a card with no dates saying so rather than assuming it is current, the end date
  itself still counting as covered, a renewal typed in back-to-front still reporting as ended, fields
  nobody filled in never reaching the card, the subscriber named only when it is somebody else, the
  disclaimer on both faces, and a member number masked to its last four in the browsing list.
- `ProviderDirectoryTest` — a pasted address tried where FHIR servers actually live and an address
  that already names FHIR tried alone, a `/metadata` URL not asked for twice, only the surname being
  sent because that is what FHIR name search matches, a credential's stray letter dropped while an
  honorific's initial is kept, a directory that files a name backwards still matching, and — the one
  that matters — **conflicting NPIs being certainty the other way**, because an entry with the right
  name and somebody else's number is a different clinician.
- `DirectoryVerdictTest` — an empty answer being a real no, two people with the same name never being
  the first one, a surname-and-initial never concluding anything, a directory full of other people
  having answered the question, and a listing marked inactive not being a yes.
- `NetworkStatusTest` — a current listing naming the network it was found in, "listed once, not
  listed now" reading as a doctor who has left rather than one never in it, a directory that is down
  not overwriting the answer it gave last month, a name that has become ambiguous unsettling an older
  yes while never burying a drop, a phone confirmation staying a phone confirmation, checks read in
  time order however they arrive, and an answer from before the last renewal flagged as stale.
- `FhirDirectoryParserTest` — a marketing page reading as "no directory here" rather than "your
  doctor isn't in it", a network reference falling back to its id when the server gave no display
  name, roles folding only into the practitioner they reference, an `OperationOutcome` read for what
  the user can act on, and malformed JSON parsing to nothing rather than throwing.
- `AllergiesTest` — an ingredient spelled out in full matching the name that was written down, and —
  the ones that matter — everything Health refuses to say: penicillin never warning about amoxicillin
  because relatedness is pharmacology, "codeine" never firing on "hydrocodone", an aspirin-free product
  never being read as containing aspirin, a food allergy not being pretended to have been checked
  against a medicine, a concept id beating every amount of string comparison, an ingredient preferred
  over a name when both match, worst-first ordering, and a severity nobody recorded never being
  promoted to a severe one.
- `ConditionsTest` — a bare year read as a year and said as one, an onset rounding to the start of its
  period where an expiry rounds to the end of one, an unparseable onset being no date rather than a
  guessed one, a future onset described as nothing rather than as negative time, elapsed time in the
  unit a person says out loud, what is still going read before what is over, and remission being a
  status of its own rather than a kind of resolved.
- `ImmunizationsTest` — doses grouping into a series oldest-first, punctuation never splitting a
  child's record in two, the summary reporting what is **recorded** and asserting in as many words
  that it never says "up to date" or "due", an undated dose kept and sorted last rather than first,
  a dose from memory marked as one while a witnessed dose is not accused of anything, and a source
  nobody recorded saying nothing rather than guessing which it was.
- `DocumentsTest` — an extension taken from the file's own name before its claimed type (resolvers
  report octet-stream for ordinary PDFs), a word after a dot not mistaken for a file type, a size
  nobody recorded saying nothing rather than zero bytes, documents read newest-first by the date on
  the document with undated last, a household document belonging to nobody rather than to whoever was
  selected, and a row never being allowed to name a path outside its directory.
- `DrugLookupParserTest` — products sorted ahead of bare ingredients, suppressed and non-English
  concepts dropped, the approximate search keeping the best score per concept, a numeric DEA schedule
  written out and an unscheduled one saying nothing, label sections kept in reading order with the
  label's own words untouched, RxNorm keeping identity while openFDA fills in what it has no opinion
  about, and malformed JSON parsing to nothing rather than throwing.
