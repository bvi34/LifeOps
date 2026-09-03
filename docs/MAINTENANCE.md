# Maintenance — the register of what you own and what it needs

Maintenance is the app that owns *the things you own*. A house, a car, the furnace, the mower; what
each one **is** (its VIN, its parcel number, its serial), what is **owed** on it (the mortgage, the
car note), what it **costs** to keep (services, premiums), and what it **next needs done**.

It is a hosted library module inside the Operations Sandbox container (`:app`), a peer to LifeOps,
Citation, Logistics, Advisor, Health, People and Project.

```
Operations Sandbox  →  Maintenance  →  Due (the docket)
                                    →  Assets  →  one asset  →  Overview · Upkeep · History · Money
```

## What it is, and what it deliberately is not

The shape is borrowed from [Homi](https://github.com/Yuss9/homi) — a self-hosted home journal of
rooms, appliances, warranties, recurring maintenance and repairs — and then narrowed hard. Homi is a
*server*: accounts, email verification, password resets, sessions, four household roles, object
storage. None of that is purpose-built for a single-user, offline suite that already ships inside one
installed app, so none of it is here:

| Homi has | Maintenance has instead |
|---|---|
| Owner / admin / member / viewer roles, invitations, sessions | Nobody to authorise. One person, one device, no account. |
| Postgres, MinIO, Mailpit, Docker Compose | One Room database and the sandbox's own backup zip. |
| Homes containing rooms containing items | **Assets** of a kind, one flat register. A room is a field on an appliance ("where it lives"), not a table. |
| Uploaded manuals, invoices and photos | Nothing binary, yet. What is here is what you typed, and the [file store is a deliberate later question](#what-is-not-here). |

What it takes from Homi is the part that earns its keep: **recurring maintenance with a completion
history**, warranties and providers, and costs attached to the thing they were spent on.

What it adds — and the reason this is not a home inventory app — is the two halves of an asset that
a household actually asks about: the **identity** it is registered under (a VIN that can be checked
without asking anyone), and the **debt** against it. A house you service and a house you are paying
off are the same house, and answering "what is it worth, what is left on it, when is it clear" in a
different app to "when was the furnace last serviced" is how neither question gets answered.

Maintenance also does **not** schedule anything, and raises no notifications of its own. It knows
*what is due and when*; it never decides when you will get to it. Deciding what today looks like is
LifeOps' job, and a second app pushing tasks at you is a second answer to "what am I doing today" —
which, in practice, means both stop being true.

What it does instead is hand the week planner the fact: an upkeep plan **publishes itself onto the
LifeOps week** as a task dated the day it falls due, and the tick comes back. See
[The LifeOps week](#the-lifeops-week).

Like Project, it is **not on the suite's sync spine**. People replicates because two apps genuinely
write the same person; nothing else in the suite writes into an asset, so there is nothing to
reconcile and no `syncVersion` column anywhere in the schema.

## The one architectural decision

**Kind-specific fields are rows, not columns.**

A car has a VIN, a plate and an odometer. A house has an address, a year built, a square footage and
a parcel number. A furnace has a serial number and lives in the basement. The obvious schema — a
`vin` column, an `address` column, a `serialNumber` column — is one where every asset carries a dozen
nulls belonging to kinds it isn't, and where adding "Boat" is a migration.

So `logic/AssetKind` **declares** each kind's fields as data:

```kotlin
VEHICLE(
    key = "vehicle", label = "Vehicle", plural = "Vehicles",
    meter = MeterUnit.MILES,
    attributes = listOf(
        AssetAttributeSpec("vin", "VIN", AttributeInput.TEXT, AttributeCheck.VIN, hint = "17 characters, no I, O or Q"),
        AssetAttributeSpec("trim", "Trim"),
        AssetAttributeSpec("bodyStyle", "Body style", hint = "Sedan, pickup, SUV, …"),
        AssetAttributeSpec("engine", "Engine", hint = "3.6L V6"),
        AssetAttributeSpec("licensePlate", "License plate"),
        …
    )
)
```

A vehicle asks for twelve: the VIN, the six a decode can fill in (trim, body style, engine, fuel,
transmission, drivetrain), the colour, the plate and where it is registered, and the two nobody can
recall at a counter — tyre size and oil spec. The first seven are ordered the way the decode returns
them, so a decoded vehicle reads top to bottom on the detail page.

A home asks for eight, and three of them are **pickers** rather than text — which is the other half of
what declaring fields as data buys, because `AttributeInput.CHOICE` and `CHOICES` were added once and
every dialog, the overview and the validation grew them for free. "Region" says what the weather
does here and is normally filled in from the ZIP; "Type of home" decides what the *building* owes;
"What it has" is a multiple choice of twelve, each of which brings its own schedule. See *What the address opens* below.

…and the values live in `asset_attributes` keyed by `(assetId, key)`. The **add** dialog, the **edit**
dialog, the overview screen and the validation are all generated from that list, so adding a kind is
**authoring**: one entry, and it grows its own fields everywhere, already checked, already stored.

Both dialogs asking from the same list is what stops them drifting: whatever a vehicle is asked for
when you first type it in is exactly what it is asked for afterwards. Adding an asset therefore
offers **everything** its kind has — the moment somebody is typing the car in is the moment the title
is in their other hand — while **requiring nothing but the name**: every kind-specific field may be
left blank, the Add button watches the name alone, and the form scrolls. What stays a real
column on the asset is only what every kind has — a name, a make, a model, a year, what it cost, what
it is worth.

The second half of the same decision: **a meter belongs to a kind**. Vehicles count miles, equipment
counts hours, a house counts nothing — so a mileage interval is only offered where there is a meter
to measure it against.

## The docket

The app opens on **Due**, not on the register, because "what needs doing?" is the only question it
exists to answer. A list of what you own is something you consult; a list of what it needs is
something you act on.

Every line is assembled on read from the schedules and the paperwork — nothing is stored as "next
due" or flagged "overdue", so nothing can be stale after a fortnight in a drawer, and a restored
backup cannot come back inconsistent.

The sort is deliberately **not** by date:

1. **Overdue**, most recently missed first. Ordering purely by date puts an inspection that lapsed in
   March above one that lapsed last week, which is backwards: the recent miss is the one you can
   still fix cheaply.
2. **Due soon** — the next fortnight — soonest first.
3. Everything else with a date.
4. Everything that cannot be dated yet.

By default only the first two are shown. A docket that also carries brake fluid due in nineteen
months is a docket nobody reads to the bottom of.

Archived assets are left off it entirely: a truck you sold does not need its registration renewing.

## Upkeep — the thing that comes round

A plan carries **either or both** intervals:

```
every 5,000 miles   ·   every 6 months   ·   whichever comes first
```

which is how every owner's manual in the world writes it, and which an app that understands only one
of the two halves gets wrong the first winter the car sits still.

The rules in `logic/Upkeep` are worth stating plainly, because each one is a decision:

- **A plan with no interval is dormant**, not overdue. "Repaint the shutters" with no *every* on it
  is a note, and a note claiming to be overdue trains you to ignore the ones that are.
- **Never done still starts the clock.** The date leg is anchored to the last completion *or the day
  the plan was written*. Anchoring only to completions would make every plan you add instantly
  overdue — the fastest possible way to make a due list meaningless on day one.
- **A meter leg needs a baseline.** Without a mileage at the last service there is nothing to add
  5,000 to. Rather than quietly measure from today's odometer — which grants a free 5,000 miles —
  the plan says `Every 5,000 mi — log one service to start the clock`, and one logged service fixes
  it forever.
- **Whichever leg falls due first governs**, and the verdict says which it was, because "due in 300
  miles" and "due in 3 weeks" are acted on differently.

### Miles into dates

A mileage interval is useless on its own: the question is *when*. Two readings answer it. `logic/Meter`
measures the rate from the readings themselves — there is no assumed 12,000 miles a year — and
projects the date the interval lands on. One reading gives no rate and therefore **no date**, which
the screen says out loud rather than guessing; without a rate, urgency falls back to the last tenth
of the interval ("due in 200 mi").

Meters do go backwards — a cluster is replaced, an hour meter is swapped with the engine, somebody
types 12,000 for 21,000. The rate is measured from the run since the last decrease, and the reading
dialog says so instead of refusing the number.

Readings are stored rather than a single "current mileage" being overwritten, because two of them are
the whole point.

### Logging is the only way to move a clock

There is no "mark done" button that doesn't record anything. **Log it** writes what was done, who did
it, what it cost and what the meter read — and *that* is what advances `lastDoneAt` / `lastDoneMeter`.
A schedule that can be silently reset is a service history with holes in it, and the history is the
reason the costs mean anything.

The mileage typed into a service is also filed as a reading, because the odometer at an oil change is
exactly as good a data point as one typed at a petrol pump.

## Money

### The mortgage

You type what the note says — principal, rate, term, first payment, and the payment itself only when
it differs from what the note works out to. Everything else is **derived** by `logic/Loan`:

- the scheduled payment (standard amortisation, with the zero-rate case handled rather than divided
  by, because interest-free family loans are real),
- the balance today, in closed form,
- principal and interest paid so far,
- the payoff month — and `null`, meaning *never*, when the payment does not cover the month's
  interest, which is the only honest answer to that,
- equity against the asset's stated value, negative when it is negative.

There is deliberately **no "current balance" field**. A stored balance is the field that makes every
other app's mortgage page wrong within a month.

Two smaller decisions with teeth:

- **Rates are basis points** (6.25% → `625`) and **money is whole cents**, everywhere. A rate stored
  as 6.2499999 compounds three hundred and sixty times; a balance in floating point drifts. The app's
  entire claim is that these numbers match your statement.
- **Escrow is not debt.** Taxes and insurance collected with the payment are stored separately, added
  to the monthly figure, and never amortised.
- Paying a note at its own scheduled payment clears it in **exactly its term**. The formula says one
  month more, because a payment rounded down to whole cents leaves a few cents at the end — true of
  every mortgage ever written, and not what the paperwork says.

### Cover

Insurance, warranties, registrations, inspections and service contracts sit beside the loan and land
on the same docket, with a **month** of warning rather than a fortnight, because renewals take
paperwork. A warranty *expires*; a policy or a plate *lapses* — same arithmetic, two different things
to do about it, so they don't get the same sentence.

Premiums annualise (monthly × 12, semi-annual × 2, a one-off to nothing) so the standing cost of
owning the thing can be added to what has been spent on it.

### What it has cost

`logic/Costs` keeps one rule: **it never annualises a window shorter than a year**. Two oil changes
in three months does not mean "$1,600 a year", and an app that says so is an app whose numbers get
quoted back at people. So "per year" is simply absent until there is a year of history, and the
screen says why. Cost per mile is the same: it needs a meter that actually moved.

## The VIN

Maintenance has no `INTERNET` permission and no decoder behind it, which rules out "type a VIN, get a
car". What it can do is everything the number itself carries, offline: a VIN is **self-checking** —
17 characters from a restricted alphabet, with a check digit in the ninth position computed from the
other sixteen — and its tenth character is the model year.

`logic/Vin` grades what is wrong rather than refusing the value:

| Problem | Treated as |
|---|---|
| Not 17 characters | A typo. Said plainly. |
| Contains `I`, `O` or `Q` | A typo — those letters cannot appear in a VIN. |
| Check digit disagrees | **Worth a second look, and stored anyway.** |

That last row is the important one. Check-digit validation is a North American requirement; plenty of
vehicles built elsewhere carry a perfectly valid VIN that fails this arithmetic. A field that refused
them would be a field that is wrong about your car and won't be told.

The model year is resolved against the current year, because the code repeats every thirty years and
the character alone cannot tell 1996 from 2026. The tie is broken the way a person would break it —
the most recent occurrence that isn't in the future, allowing a year of lead because next year's
models are sold this year — and the answer is shown as *"2019 model year, by the VIN"* beside the
year you typed. It never overwrites it.

## The LifeOps week

A schedule nobody is reminded of is a schedule nobody keeps. Maintenance could have grown a
notification for that — and deliberately hasn't, because the suite already has an app whose whole
job is *what am I doing this week*. So an upkeep plan puts itself **on the LifeOps week**, and the
week gives the tick back.

```
Maintenance                                   LifeOps
   plan "Oil change", due 30 May   ──────▶   task "Truck: Oil change", due 2026-05-30
                                              (parked in Future Tasks until that week opens)

   service logged, clock restarted ◀──────   ✓ ticked
   next occurrence published        ──────▶   task "Truck: Oil change", due 2026-08-26
```

The dated task is the point. LifeOps already parks a task whose due date is beyond this week in its
**Future Tasks** queue and wakes it into the week that contains that date — so publishing an oil
change five months out lands it in the right week five months out, rather than sitting on today's
list looking like something you chose to do now.

### What gets published

| | |
|---|---|
| Title | `Truck: Oil change` — the asset leads, because a week's list is read across a dozen unrelated things and "Oil change" on its own is a question |
| Due date | The day the verdict falls due; **today** when a plan is overdue with no date behind it yet (a mileage interval with no rate) |
| Note | Where it came from, its cadence, where it stands, and what ticking it will do |
| Aspect | Whatever **LifeOps** says in `Settings → Maintenance upkeep` — the same arrangement Citation's reading time has. Which part of your life an upkeep job counts towards is LifeOps' decision, not this app's; unfiled is a fine answer, and the task still scores |
| Duplicates | Handled by **id, not title**: an open task with the same title is adopted, and the create that follows opts out of LifeOps' same-week title check. That check counts completed rows, and a future-dated task lives in the current week until it closes — so without opting out, the occurrence published seconds after you tick this one would be silently swallowed |
| Recurring | **Never.** LifeOps can repeat a task on its own cadence, and a plan using that would put two engines in charge of when the next oil change is. Maintenance owns the cadence; each occurrence is published as a one-off |
| Hard deadline | **Never.** A hard deadline expires the task at week close, which would quietly bin a job that simply didn't get done that week |

It is a **per-plan switch**, on by default. "Change the furnace filter" belongs on a week; "check the
roof after a storm" does not.

The aspect is read from LifeOps' settings **at publish**, not stamped on the plan. So there is one
place to change it — the app that owns aspects — and changing it re-files everything published from
then on while leaving weeks already planned alone. A task you moved to a different aspect by hand in
LifeOps keeps where you put it: the round fixes the title and the date, because it is the only thing
that knows them, and never touches your filing.

### The tick coming back

LifeOps announces every completion on a small in-process bus (`connection/TaskCompletionBus` — the
one thing in its connection layer that points *outward*), and Maintenance listens. When the ticked
task is one of ours:

- the service is **logged** — cost zero, no vendor, and the note says so, because a tick in a week
  planner says *that* the job was done and nothing about what it involved;
- the **clock restarts** from the completion time;
- the **next occurrence is published** onto the week;
- and for a plan with a mileage interval, the **last known reading becomes the new baseline** —
  without it, the mileage leg would never move and the plan would be permanently overdue on one of
  its two legs. It is written onto the record but *not* filed as a new reading, because nobody read
  the dial.

### It is a reconciliation, not an event handler

This is the part worth insisting on. The announcement makes the tick land immediately, but the same
**round** also runs when Maintenance comes to the foreground and after every edit to a schedule, and
it reaches the same answer either way: read what is true, decide one thing per plan, do it. Nothing
depends on having caught a particular moment — which is what makes the awkward cases ordinary:

| What happened | What the round does |
|---|---|
| You **deleted** the task in LifeOps | Drops the link and does **not** put it back. An app that silently re-adds what you just deleted is one you delete from twice. The next occurrence publishes normally |
| The week closed and **carried** it forward (a new row, a new id) | Follows the hop and re-points the link — no duplicate |
| The week closed and left it **stranded** (not done, not carried) | Publishes it again on the current week. The stranded row is left where it is: that week has already been reviewed |
| You **paused** the plan, switched publishing off, or **archived** the asset | Takes the task off the week |
| You **deleted** the plan or the asset | Takes its task off the week first — a database cascade cannot reach into another app, and "Truck: Oil change" outliving the truck is exactly the orphan that teaches people to distrust a shared week |
| You had already written that job onto the week **by hand** | It is *adopted* — the plan links to your row rather than adding a second beside it, so ticking the one you wrote ticks the one Maintenance is watching. Its date is brought in step on the next pass |
| Two plans on one asset are **named the same** | Only one gets the task. Adoption is by title, so the second would otherwise share the first's row and be completed by the same tick; it goes without until it is renamed |
| The plan **can't be dated** today (a mileage interval whose usage rate has become unknowable) | Nothing is published, and anything already on the week is left where it is. Undatable is not the same as unwanted |
| LifeOps **isn't installed** in the process | The round is a no-op. Maintenance keeps its schedules and its docket; it simply stops putting them on a week that isn't there |

One thing the bus deliberately does not carry is an **un-completion**. Un-ticking a task in LifeOps
is a correction to LifeOps' week; the service record Maintenance already wrote in response is *its*
record to correct, and reaching back to delete somebody's service history from a checkbox would be
worse than leaving it.

### Which way the dependency points

`:maintenance` depends on `:lifeops`, never the other way. LifeOps announces completions to a bus
and knows nothing about who is listening — it behaves identically whether or not anybody is — and
publishing a task is a call into LifeOps' own `TaskService`, the same seam Logistics uses for the
food catalog. One week planner in the suite, with a door into it.

## What the VIN opens

Seventeen characters on a door jamb are the most useful thing a vehicle carries, and the app can get
three things out of them without asking you anything else.

```
VIN ──11 chars──▶ vPIC ──▶ VehicleFacts ──▶ schedule pack ──▶ plans ──▶ the LifeOps week
                              └──make/model/year──▶ NHTSA recalls ──▶ the docket
```

### The eleven characters, and the six that stay here

A decode sends `1C4HJXDG5JW******` — the world manufacturer, the descriptor section, the check
digit, the model year and the plant. The six dropped are the serial: the part on your title, the
part an insurer quotes, the part a vehicle-history service is keyed on.

They are dropped for two reasons and the second one is what makes it easy. They identify *your*
vehicle rather than a model — and **vPIC returns an identical answer without them**, which was
checked against the live API before any of this was written. So the app asks *"what is a 2018
Wrangler Unlimited Sport 3.6 4x4?"* and has no code path that could ask *"which truck is parked
outside this address?"*

The rule lives in `logic/Vin.decodeQuery` and is unit-tested, because a privacy promise that depends
on somebody remembering to truncate a string is not a promise. It is the same test Health applies to
its drug lookup: *could this request tell anyone something about this household?*

The decode is **offered, never applied**. It fills in only the fields you left blank — make, model
and year on the asset itself, and the trim, body style, engine, fuel, transmission and drivetrain in
the vehicle's own fields — and the schedules it matches are listed for you to choose from rather than
imported on your behalf. Every field vPIC answers has somewhere to land, which is asserted in
`AssetKindTest`: a decoded fact with no field to go in is a fact silently thrown away.

### Schedule packs

There is no public Mopar API for maintenance intervals — no public OEM API for them at all. The
schedules live in owner's manuals, and the owner sites that hold them are behind logins. Scraping
one would put OEM credentials inside an offline household app and a parser that breaks the week they
redesign: a worse app that is also more likely to be wrong.

So a pack is **transcribed once, by hand, from the manual** and shipped as Kotlin — versioned in
git, reviewable in a diff, unit-tested, offline. The decode's job is not to fetch a schedule but to
**choose** one. This build ships two:

| Pack | For | Items |
|---|---|---|
| `jeep-jl-36-a` | Jeep Wrangler JL, 3.6L, 2018–2026 — **Schedule A** | 12 |
| `generic-vehicle` | Anything, as a starting point | 6 |

The generic pack matches everything and is always offered *beneath* whatever specific pack matched,
because a wrong-but-specific schedule applied silently would be the worst outcome available here.

Every pack carries its [source] and, until somebody checks it against a manual in their own
glovebox, a **provisional** flag that the screen shows. The numbers are a starting point: the moment
a pack is applied its items become ordinary plans — yours to rename, re-time, pause or delete — and
nothing re-imposes them afterwards. Applying a pack again adds only what is missing.

### Recalls

NHTSA's recall API is keyed by **make, model and year — no VIN**, so this costs nothing in privacy:
the answer is the same for every 2018 Wrangler in the country. A recall is the third thing a vehicle
can owe you, and the only one somebody else raised — which is exactly why it is worth surfacing, as
the letter goes to whatever address the DMV last had.

How loudly they speak is a judgement. NHTSA publishes two flags — **do not drive** and **do not park
indoors** — and those come through as *overdue*, at the top of the docket. Every other campaign is
*scheduled*: a used vehicle can carry a decade of open recalls, most long since done by somebody,
and fourteen red lines on the day you add a truck is a docket you stop reading. They stay on the
list, on the asset's page, and counted — just not shouted.

**Acknowledging** one is the only part of a recall this app owns: NHTSA says what is open for the
model, you say whether it has been dealt with on yours. It comes off the docket and stays on file.

And the answer **goes stale on its own**, which nothing else on the docket does. Every other line is
owed because your vehicle changed — miles went on it, a policy ran out. A recall list changes while
the vehicle sits still: campaigns are opened years after a car is built. So a check has a shelf life
(`logic/RecallChecks.EVERY_DAYS`, six months), the vehicle page says out loud when the answer on file
is older than that, and every vehicle schedule carries a standing **"Check recalls"** — a prompt, not
work, in exactly the shape the odometer prompt has below: running the check is what satisfies it, so
pressing *Check recalls* here ticks the task off in LifeOps. A vehicle that predates the prompt, or
whose owner never applied a schedule, is offered it on the page rather than given it silently: a plan
puts a task on somebody's week, and inventing those unasked is how an app stops being trusted.

### The odometer prompt

Every mile-based interval in this app rests on readings, and nothing collects them on its own. So a
vehicle schedule includes a **weekly "Odometer reading"** — which is a plan, but not a job:

- it writes **no service record** when satisfied (a history full of weekly zero-pound entries called
  *Read the odometer* would bury the eleven entries that matter), and
- **the reading satisfies it, not the tick**. A LifeOps task cannot carry a number, so typing the
  reading in here is what completes the task over there. The task is the nudge; the reading is the
  work.

That is the seam running the other way — Maintenance completing a LifeOps task rather than reacting
to one — and it is why `logic/PlanKind` exists. The recall check is the second of exactly two things
shaped like that, for the same reason: a task in a week planner cannot carry a number and cannot go
and ask NHTSA anything, so in both cases *doing the thing here* is what completes the task there.
`PlanKind.isWork` is the line between them and real upkeep — only work writes a service record.

## What the address opens

A house is the other half of this module and it does not work like the car at all.

```
address ──ZIP──▶ region ─┬─▶ climate
      "region" ──────────┘   └─ hazards
"type of home" ──────────────┐
year built ──────────────────┼──▶ HomeFacts ──▶ the schedules that fit ──▶ plans ──▶ the LifeOps week
"what it has" ──features─────┤
a loan on the asset ─────────┘
```

Every arrow in that diagram is inside the phone.

### Why a house sends nothing anywhere

The vehicle side asks a public decoder *"what is a 2018 Wrangler Unlimited Sport?"* — a question
about a product, of the kind anyone could type into a search engine, which is why eleven characters
of a VIN are allowed to leave the device at all. **There is no equivalent question about a house.**
An address is not a model number; it is where these people live. Every property API worth having —
the Census geocoder included — is keyed on the street line, and there is no half of an address that
describes a *type* of house rather than a particular one. The ZIP comes closest, and even it buys
only a climate.

So `logic/HomeFacts` runs entirely offline, and that is a finding rather than a missing feature. The
test this module already applies to going online — *could this request tell anyone something about a
member of this household?* — is failed by the very first request, so none is made.

What is left is better than it sounds, because the household has **already typed the answers in**:

| Read from | What it gives |
|---|---|
| **"Region"**, or the ZIP in the address | `Region` — one of eighteen, carrying a `Climate` and any `Hazard`s |
| **"Type of home"** (one choice) | `HomeStructure` — site-built, manufactured, townhouse, condo |
| Year built | Whether the jobs peculiar to pre-1980 housing stock apply |
| **"What it has"** (multiple choice) | `HomeFeature` — twelve, each of which brings a schedule |
| A loan row against the asset | Whether there is a mortgage's paperwork owed as well as work |

### Announcing rather than describing

The two fields that drive nearly all of this are **pickers**, not prose, and that is the point: the
set of right answers is short, closed, and known to the app. "Septic" spelled three ways is three
answers to a question that has one, and a schedule that only appears when somebody happens to write
the word the parser was hoping for is a schedule that mostly does not appear.

**"Type of home"** is one choice and it is the field that decides what the *building* owes:

- a **manufactured home** is not founded, it is *set* — on piers that settle, held by anchors that
  loosen, skirted rather than walled, with its plumbing in a wrapped belly under the floor and a roof
  that is coated rather than shingled. None of that is on any site-built checklist and all of it is
  what actually goes wrong, so `home-manufactured` exists and is offered to nothing else.
- a **condo** owner does not own the roof anybody would otherwise tell them to go and look at, so the
  jobs on the outside of the building are a pack of their own (`home-envelope`) that a condo is
  excluded from. They keep the alarms, the filter, the water heater and the dryer vent.

The two structure clauses in `PackFit.Home` pull in opposite directions on purpose, and the
difference is what a **missing** answer does. `structures` asks for a kind of building and an
unpicked type matches nothing — right for the manufactured list, which would be nonsense on a condo.
`excludeStructures` rules one out and an unpicked type is *not* ruled out — right for the outside of
the building, which almost every home owes. Silence should cost a household the schedule that is
usually wrong, never the one that is usually right.

**"What it has"** is a multiple choice of twelve, and there is exactly one pack per entry — septic,
well, gas or propane, all-electric, solar, central air, fireplace, sump pump, sprinklers, pool, deck,
standby generator. Tick solar and a solar schedule appears; tick gas and the flue and shut-off checks
do. `HomePacksTest` asserts that map in both directions, so a feature added without a schedule fails
the build rather than becoming a checkbox that does nothing.

Two of those overlap with the climate packs on purpose. `home-cooling` and `home-hot` carry the same
two AC jobs under the same titles, so a house in Phoenix matches both and `SchedulePlans` adopts the
plan that is already there rather than adding a second beside it — which is how a cooling schedule
reaches a house in Vermont that has ducted air and a climate that never suggested one.

Values are stored as the option **keys**, comma-joined, and a key this build does not recognise is
kept rather than dropped: a row can arrive from a backup written by a later build, or from the days
when the field was free text. The free-text reader is still there as the fallback — a phrase claims a
feature when it names one and does not deny it, so *"septic tank, no sprinklers"* still finds the
tank and not the sprinklers, and *"stairwell"* is still not a well.

### The region lookup

**"Region"** is the third picker and the only one with a lookup behind it. `HomeLookup.regionOf` is a
table of ZIP prefixes — the first three digits, which run in geographic order — each pointing at one
of eighteen `Region`s, and a region carries two things:

- a **`Climate`** — cold, four seasons, hot and humid, hot and dry, mild and wet — which is what the
  weather does on an ordinary Tuesday, and
- any **`Hazard`s** — hurricanes, wildfire, hail and tornadoes, earthquakes — which is what it does
  at its worst, and a completely different list of jobs.

That second axis is the reason a region exists rather than a bare climate. Miami and Houston are both
hot and humid; only one of them is somewhere the shutters and the roof straps want finding before
June. Boulder and Burlington are both cold; only one has thirty feet of ground round the house that
has to stay clear of anything that burns.

All four hazard packs are **preparation on a clock**, which is the only reason weather this sudden
can be scheduled at all: the moment any of it matters is the moment it is far too late to start.
Nothing here watches a forecast. Maintenance says what is owed and LifeOps says when, and neither of
them knows what the sky is doing.

#### The guess, and the field that overrules it

The table is coarse and it is drawn roughly — "the dry Southwest" contains California's Central
Valley, and Texas is filed with the Gulf Coast although most of it is nowhere near the water. Any
line drawn on a country this wide is wrong for somebody, and the answer is not a finer table:

- with the field **empty**, the region is worked out from the ZIP, and `RegionSource.ZIP` makes the
  screen draw it as a guess — *"a guess from the ZIP code 96161"* — with a **Use the Mountain West**
  button beside it;
- pressing that writes the key into the field, which is what stops it being a guess. Nothing
  re-derives a picked region, and the ZIP is consulted only while the field is empty;
- picking a different one in Edit does the same thing directly.

Truckee is the case: the ZIP prefix says California, so the guess offers earthquake preparation and
no winterising, and the house is in the snow behind it. One tap fixes it, permanently, and
`HomePacksTest` asserts that the correction runs all the way through to the schedules.

Accepting a region **applies nothing**. It is the same gesture as *Use these details* on a VIN
decode — what the app worked out is an offer until somebody accepts it, and a plan still only reaches
anybody's week when a pack is applied on purpose.

### One house, several schedules

A vehicle gets **one** pack because a manufacturer wrote one. Nobody writes one for a house, and what
a house owes is a sum: what living in any building owes, plus what *this* building owes, plus what
the weather does here and what it does here at its worst, plus its age, plus one pack per thing the
household announced it has, plus the paperwork of owning it. So `HomePacks` ships twenty-five small
packs and a house is normally offered five to eight:

| Pack | Offered when | Items |
|---|---|---|
| `home-core` | Always — as true in an apartment as on a farm | 10 |
| `home-envelope` | Any type of home but a condo, and when none is picked | 5 |
| `home-manufactured` | Type of home is *manufactured* | 5 |
| `home-cold` | Cold or four-season climate | 4 |
| `home-hot` | Hot and humid, or hot and dry | 3 |
| `home-damp` | Hot and humid, or mild and wet | 3 |
| `home-hurricane` | The region carries that hazard | 3 |
| `home-wildfire` | " | 4 |
| `home-severe-storm` | " | 3 |
| `home-earthquake` | " | 4 |
| `home-older` | Built before 1980 | 3 |
| `home-septic` · `home-well` · `home-gas` · `home-electric` · `home-solar` · `home-cooling` · `home-fireplace` · `home-sump` · `home-irrigation` · `home-pool` · `home-deck` · `home-generator` | That feature was ticked | 2–3 each |
| `home-ownership` | Always | 4 |
| `home-mortgage` | There is a loan against it | 4 |

Three pairs of packs deliberately share an item **title** so the overlap costs nothing: the two AC
jobs in `home-cooling` and `home-hot`, the gas shut-off in `home-gas` and `home-earthquake`, and
photographing the rooms in `home-ownership` and `home-hurricane`. Each is one job with two reasons
behind it, and `SchedulePlans` adopts a plan that is already there by title rather than adding a
second beside it — which is also how a cooling schedule reaches a house in Vermont with ducted air
and a climate that never suggested one.

Small packs rather than one composed list is what makes the ordinary case work: you type the house in
on the day you buy it, and six months later you finally tick *"septic system"* in the field that
asks what it has — at which point the septic schedule simply appears as one more thing to apply, and
nothing you had already re-timed is touched. A single list regenerated from the facts would either
re-impose intervals you had edited or refuse to grow. It is also what keeps provenance honest: a plan
created two years ago can still say which schedule it came from, because pack ids are permanent.

The numbers come from trade and fire-service practice transcribed by hand, not from a manual, so
every home pack is `provisional` and says so on the row. Several are genuinely contested — how often
a septic tank wants pumping depends on how many people live over it — and they are meant to be
corrected: the moment a pack is applied its items are ordinary plans, yours to re-time or delete.

### The mortgage is upkeep too

`home-mortgage` is the answer to *what does a loan actually need doing to it*, and every item on it is
**reading something that arrives on its own**: the annual escrow analysis, the statement checked
against the balance this app computes, the twice-yearly *can the mortgage insurance come off yet*, and
the interest statement that arrives in January alone and is needed in April. None of them advises
anything. `logic/Loan` already takes the line that this is not a payoff optimiser, and scheduling the
opening of an envelope is a different thing from telling somebody whether to refinance.

The PMI item is the one that pays for the rest. A US lender must drop mortgage insurance at 78% of
the original value and will normally consider a written request at 80% — and nobody rings to tell you.

## The screens

**Due** — the docket, pressing by default, everything one chip away.

**Assets** — the register, grouped by kind. Sold or scrapped things stay behind a filter rather than
disappearing: a car's service history is the most useful thing you own about it right up until the
day after you sell it, and "no longer mine" is not "gone".

**Costs** — the same arithmetic asked sideways. Every figure on it exists on some asset's page; what
does not exist anywhere else is the comparison, which is the part that changes what somebody does. An
asset page says the truck cost $1,900 this year. Only this screen says that is most of everything.

Two decisions in it. **Costs include what you have sold; worth and owed do not** — spend is history
and history includes the truck you had until March, while what a thing is worth and what is owed on
it are claims about now. And a total worth **says how complete it is** ("3 of 5 have a value typed
in"), because a net-worth figure assembled from two filled-in fields is worse than no figure at all.

It also answers *who did the brakes last time*, which only has an answer across assets: the garage
that did the truck is the one you would ring about the mower. Vendors are free text — a household
will not maintain a directory — so the names are folded together case-insensitively for counting
(`logic/Vendors`) and offered back as chips while you type in the log dialog, which fixes the
spelling where it is introduced rather than afterwards.

**One asset**, in four tabs, because they are four views of one thing rather than four screens:

| Tab | Answers |
|---|---|
| Overview | What it *is* — the kind's fields, what it cost, what it's worth, the meter and its rate |
| Upkeep | What it *needs* — the schedules, their verdicts, and the one button that logs and advances them |
| History | What has been *done* — every service, newest first, with the readings alongside |
| Money | What it *owes and costs* — the loan, the cover, the running totals |

Adding an asset asks for the kind, the name, make/model and year — and then for everything that kind
has: pick "Vehicle" and the VIN, the trim, the engine and the plate are right there, because the
moment somebody is typing the car in is the moment the title is in their other hand. None of it is
required. The mortgage and the schedule still live one screen in.

### The day you sell it

A car's history is worth money on exactly one day, and on that day the app's backup is no use: it is
a whole-suite restore into an app the buyer does not have. So an asset's history leaves as **CSV**
through the system file picker — no storage permission, no folder of its own, and the file stops
being this app's business the moment it is written.

`logic/Handover` builds it, and everything it does is about being read by something that is not this
app: money written plain (`1234.56`, so a column of it adds up), ISO dates (which sort as text and
mean the same thing in every country), the meter column named for what it counts, and RFC 4180
quoting — the field that makes that matter is the notes, where *"replaced belt, cheaper than the
dealer"* would otherwise silently shift every later column by one in a file somebody is reading to
decide what your car is worth.

## Layout

```
maintenance/src/main/java/com/maintenance/app/
├── logic/          Pure JVM, unit-tested: AssetKind · Vin · Meter · Upkeep · Coverage · Loan · Money · Costs · Docket
│                   Ledger/Ledgers/Vendors (the register's money, sideways) · Handover (the CSV you sell with)
│                   VehicleFacts · VpicParser · Recalls · SchedulePack/SchedulePacks · SchedulePlans
│                   …and the LifeOps seam's brain: UpkeepTasks (what should happen to a plan's task)
│                   and UpkeepRound (the reconciliation, over two interfaces)
├── data/
│   ├── db/         Room database, seven entities, one DAO
│   ├── model/      Asset, AssetCard, AssetDetail, PlanView, LoanView, CoverageView
│   ├── prefs/      maintenance_prefs — which tab, which filters
│   ├── net/        VehicleLookupClient — the only class here that touches the network
│   └── repository/ MaintenanceRepository — rows in, logic types out, every multi-row write
│                   LifeOpsTasks — the bridge into LifeOps' task service
│                   UpkeepPublisher — finds the week planner and runs a round against it
├── ui/             due · assets · costs · asset (+ its dialogs) · common · theme
└── backup/         MaintenanceBackupContributor
```

Nothing derived is stored. Balances, due dates, statuses and costs are computed on read, from
`logic/`, which is why there is no `nextDueAt` column to go stale and nothing for a restore to leave
inconsistent. The cost is a fold over a few hundred rows whenever a screen collects — at household
scale, nothing.

## Tests

`gradle :maintenance:test` — 206 JVM tests, no emulator needed. Almost all of them are over `logic/`
and need nothing but a JVM; the handful that exercise the database run through Robolectric, which is
the only reason this module has a test dependency beyond JUnit at all.

- `VinTest` — the check digit on a real VIN, the two typos a VIN catches by itself, a failing check
  digit reported rather than rejected, and the thirty-year model-year cycle resolved against three
  different "current" years.
- `UpkeepTest` — dormant plans, the never-done clock, the missing baseline, whichever-comes-first in
  both directions, mileage overdue, and the no-rate fallback.
- `MeterTest` — a rate from two readings, no rate from one, the run after a meter goes backwards, an
  interval turned into a date, and a target already passed reported as *now* rather than dated in the
  past.
- `LoanTest` — the textbook $300k/6%/30yr payment to the cent, the zero-rate case, a year in, paying
  extra, a payment that never clears, and equity going negative.
- `CoverageTest`, `CostsTest`, `DocketTest`, `MoneyTest`, `AssetKindTest` — renewal windows and
  wording, the refusal to annualise a short history, docket ordering, cent parsing, and the
  kind-catalogue invariants (unique keys, blanks always allowed, VIN normalised on the way in, a
  picker toggled and read back as labels rather than keys).
- `UpkeepTasksTest` — the decision behind the LifeOps seam: what to publish, when a moved date
  reschedules rather than duplicates, the deleted task that isn't put back, the stranded one that
  is, and the note the task carries.
- `UpkeepRoundTest` — the seam driven end to end against a fake week planner and a fake store:
  publish once and stay put; tick it and watch the service logged, the clock move and the *next*
  occurrence go on; delete it and see it stay deleted until the plan moves on; carry it forward and
  see the link follow rather than duplicate; write the job by hand and see it adopted; name two
  plans the same thing and watch them refuse to share a task.

  The fake planner deliberately reproduces the two LifeOps behaviours that have caught this seam
  out — titles collide **within a week and completed rows count**, and a carried-forward task is a
  new row beside the old one. A fake that modelled the first as "some titles are refused" is what let
  *"tick it and the next occurrence never lands"* through the first time.

- `UpkeepTest` (milestones) — a number on the odometer rather than a distance from now; milestones
  already behind you taken as done; the one you have driven past; a milestone racing a time interval.
- `SchedulePlansTest` — which pack a VIN's facts choose and which they don't, the fallback always
  offered beneath, applying twice adding nothing, and a plan you typed by hand being adopted.
- `HomeFactsTest` — a house read off its own fields: the ZIP found at the bottom of an address rather
  than in the house number, ZIP+4 accepted and the +4 dropped, the region a prefix lands in and the
  climate and hazards it carries, a prefix the table doesn't cover answered with *null* rather than a
  guess, a picked region winning over the ZIP and reporting itself as picked, a region key from a
  later build falling back to the ZIP rather than blanking the answer, a picker's keys read back,
  keys and prose in the same value both read, and the join between the three enums and the three
  field specs — every option offered is a key the reader knows.
- `HomePacksTest` — which schedules a house is offered, and mostly which it isn't: no septic list for
  a house on mains drainage, no winterising list in Miami, no manufactured-home list for a condo *or*
  for a type nobody picked, no gutters for a condo but gutters for a house whose type is still blank,
  no hazard list for a region nobody knows, no older-house list for a year nobody typed, no mortgage
  list without a loan. Both the feature-to-pack and hazard-to-pack maps are asserted in both
  directions, so either kind added without a schedule fails rather than becoming a tick that does
  nothing. Plus Truckee — a picked region overruling the ZIP all the way through to the schedules —
  the three deliberately-overlapping pairs not scheduling their shared job twice, the catalogue's own
  invariants (every item has a date interval, none is measured in miles, ids unique across every pack
  of every kind because provenance is keyed on them), and the case the shape exists for: the septic
  schedule arriving six months late and adding only itself.
- `VpicParserTest` and `RecallsParserTest` — both against fixtures trimmed from **real** responses,
  because a hand-written ideal payload proves only that a parser can read itself. Including the rule
  that the serial never leaves the device.

- `LedgerTest` — the register's money: biggest spender first, the window really being a window,
  premiums pro-rated the same way one asset's page does it, a sold car still counting as spend but
  not as worth, equity refusing to be a bare minus sign, and one garage typed three ways being one
  garage.
- `HandoverTest` — the CSV a buyer opens: newest first, plain money, a note with a comma in it
  staying one column, a quote doubled, and an empty history exporting a header rather than a file
  that looks like a failure.
- `RecallChecksTest` — the shelf life on a recall answer (never asked counts as stale), the standing
  check every vehicle schedule carries, and the line between a prompt and work.
- `MaintenanceMigrationTest` — a database written at **version 1** opened through the production
  builder: the rows survive, the columns added since arrive with the defaults their migrations
  promise, and Room's own post-migration validation is what proves the schema is right. Removing a
  single `ALTER TABLE` from a migration fails it, which was checked rather than assumed.
- `MaintenanceRepositoryTest` — the rules only SQLite can be asked about: a cleared field *deleted*
  rather than stored as an empty string, a decode filling what is blank and arguing with nothing you
  typed, both writes that run back into the LifeOps week handing over the right task ids, an
  acknowledged recall surviving the next fetch, and deleting a truck really taking its plans,
  attributes, readings and recalls with it.

LifeOps' half has its own: `TaskCompletionBusTest` (`gradle :lifeops:testDebugUnitTest`) holds the
one promise that makes the bus safe to have — a listener that throws cannot break a tick, or the
listener after it.

## What is not here

Named so it is a decision rather than an omission:

- **Milestones are shown, not edited.** A pack's `at 60,000, 120,000` list is visible on the plan
  and in its dialog but has no editor yet — it is a list rather than a number, and a text box that
  turned it into one wrong figure would be worse than not offering one.
- **One vehicle pack.** Adding another is authoring — one entry in `SchedulePacks` — but nobody has
  authored it.
- **No files.** Manuals, invoices and photos are the obvious next thing; they need a file store, a
  backup story for it and a viewer, which is a piece of work rather than a field. Notes hold the
  gist meanwhile.
- **No reminders of its own.** Deliberate, per above: upkeep goes onto the LifeOps week and LifeOps
  does the reminding. Nothing here posts a notification.
- **No Advisor indexing.** Advisor can already read LifeOps, Citation, Logistics, Health and People
  under its permission gate; Maintenance would be a natural sixth source ("when did I last service
  the truck?") and is not wired in yet. It needs a knowledge source and a permission entry, both
  small, neither guessed at here.
- **No valuation.** "Worth now" is what you last looked up. Nothing in an offline app knows what a
  2009 Odyssey is worth this month, and a number the app invented would be worse than a stale one you
  typed.
