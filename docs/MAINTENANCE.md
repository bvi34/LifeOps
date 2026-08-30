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
        AssetAttributeSpec("licensePlate", "License plate"),
        …
    )
)
```

…and the values live in `asset_attributes` keyed by `(assetId, key)`. The edit dialog, the overview
screen and the validation are all generated from that list, so adding a kind is **authoring**: one
entry, and it grows its own fields everywhere, already checked, already stored. What stays a real
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
| A task with that **title already exists** in the week | LifeOps declines the duplicate and keeps the one you wrote by hand. The occurrence is recorded as published anyway, so the round stops arguing about it |
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

## The screens

**Due** — the docket, pressing by default, everything one chip away.

**Assets** — the register, grouped by kind. Sold or scrapped things stay behind a filter rather than
disappearing: a car's service history is the most useful thing you own about it right up until the
day after you sell it, and "no longer mine" is not "gone".

**One asset**, in four tabs, because they are four views of one thing rather than four screens:

| Tab | Answers |
|---|---|
| Overview | What it *is* — the kind's fields, what it cost, what it's worth, the meter and its rate |
| Upkeep | What it *needs* — the schedules, their verdicts, and the one button that logs and advances them |
| History | What has been *done* — every service, newest first, with the readings alongside |
| Money | What it *owes and costs* — the loan, the cover, the running totals |

Adding an asset asks for four things: kind, name, make/model, year. The VIN, the mortgage and the
schedule all live one screen in — asked for when you are sitting with the paperwork, not while you
are standing in the garage trying to get the car into the app at all.

## Layout

```
maintenance/src/main/java/com/maintenance/app/
├── logic/          Pure JVM, unit-tested: AssetKind · Vin · Meter · Upkeep · Coverage · Loan · Money · Costs · Docket
│                   …and the LifeOps seam's brain: UpkeepTasks (what should happen to a plan's task)
│                   and UpkeepRound (the reconciliation, over two interfaces)
├── data/
│   ├── db/         Room database, seven entities, one DAO
│   ├── model/      Asset, AssetCard, AssetDetail, PlanView, LoanView, CoverageView
│   ├── prefs/      maintenance_prefs — which tab, which filters
│   └── repository/ MaintenanceRepository — rows in, logic types out, every multi-row write
│                   LifeOpsTasks — the bridge into LifeOps' task service
│                   UpkeepPublisher — finds the week planner and runs a round against it
├── ui/             due · assets · asset (+ its dialogs) · common · theme
└── backup/         MaintenanceBackupContributor
```

Nothing derived is stored. Balances, due dates, statuses and costs are computed on read, from
`logic/`, which is why there is no `nextDueAt` column to go stale and nothing for a restore to leave
inconsistent. The cost is a fold over a few hundred rows whenever a screen collects — at household
scale, nothing.

## Tests

`gradle :maintenance:test` — 88 JVM unit tests over `logic/`, no SDK or emulator needed:

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
  kind-catalogue invariants (unique keys, blanks always allowed, VIN normalised on the way in).
- `UpkeepTasksTest` — the decision behind the LifeOps seam: what to publish, when a moved date
  reschedules rather than duplicates, the deleted task that isn't put back, the stranded one that
  is, and the note the task carries.
- `UpkeepRoundTest` — the seam driven end to end against a fake week planner and a fake store:
  publish once and stay put; tick it and watch the service logged, the clock move and the *next*
  occurrence go on; delete it and see it stay deleted until the plan moves on; carry it forward and
  see the link follow rather than duplicate.

LifeOps' half has its own: `TaskCompletionBusTest` (`gradle :lifeops:testDebugUnitTest`) holds the
one promise that makes the bus safe to have — a listener that throws cannot break a tick, or the
listener after it.

## What is not here

Named so it is a decision rather than an omission:

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
