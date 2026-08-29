# LifeOps — User Guide

Welcome. LifeOps is a weekly operations log for your life: plan a week, do the work,
log the hours, close the week. It keeps an honest, permanent record of what you actually
did — not what you meant to do.

This guide covers everything you need to use the app. It's long because the app does a
lot; the **Quick start** below is enough to get going in five minutes.

---

## Quick start (5 minutes)

1. **Set up your aspects.** Go to **Settings → Aspects** and create a few areas of life
   you care about — e.g. *Body*, *Mind*, *Craft*, *Home*. Give each a colour. (A couple of
   defaults may already exist; edit them to taste.)
2. **Add this week's tasks.** On **This Week**, tap **+** and add what you intend to do.
   Pick an aspect and a priority. Optionally add a time **estimate**.
3. **Do the work, and log the time.** When you work on a task, start its **timer** (or log
   minutes manually). Logged time is what powers your scores and your Growth rings.
4. **Mark outcomes.** Tap a task to complete it. Skip, carry forward, or mark unsuccessful
   as needed.
5. **Close the week.** When the week is done, use **Close Week**. LifeOps snapshots the
   week, carries forward what you kept, starts the next week — and draws your first
   **Growth ring**.

Repeat weekly. The Growth Record (the rings) is the long game: it can't be faked or
back-dated, so it's worth feeding honestly.

---

## Core concepts

- **Aspect** — a top-level area of life (e.g. *Body*). Has a name and a colour. Aspects are
  the backbone: tasks, time, resources, reports, and rings are all organised by aspect.
- **Category** — an optional sub-grouping inside an aspect (e.g. *Body → Running*).
- **Operation** — a longer effort that groups related tasks within an aspect (e.g. *Write the
  book*). Track its progress over many weeks. These were called *Projects* until the suite
  grew a Project app of its own — same thing, new name.
- **Week** — runs Monday→Sunday. Exactly one week is "open" at a time. You **close** it
  yourself when you're ready; there is no automatic rollover.
- **Task** — a unit of work with a priority, optional estimate, optional due date, and any
  time you log against it.
- **Resources** — points you earn by completing work, themed as RPG-style resource slots.
- **The Growth Record** — one permanent ring per week, encoding your effort.

A note on tone: LifeOps is deliberately blunt. The headers and messages are sardonic by
design. The data underneath is sincere.

---

## This Week

This is where you live day-to-day.

### Creating a task

Tap **+** and fill in:

- **Title** (required).
- **Aspect** and optionally **Category** — what area this belongs to.
- **Priority** — *Low, Medium, High, Critical*. The main driver of how much completing the
  task is **worth** (its resource value is computed from the priority, plus whether it has a
  hard deadline and an estimate).
- **Estimate (minutes)** — optional, but recommended: estimating well is rewarded (see
  **Scoring**).
- **Due date** and **Hard deadline** — see below.
- **Recurring** — if set, the task is re-seeded into next week when you close the current
  one.
- **Operation** — optionally attach the task to an operation.

### Logging time (this is the important part)

Time is the currency of LifeOps. Three ways to log it:

- **Timer** — start a task's timer; stop it to log the elapsed minutes.
- **Pomodoro** — a 25-minute timer that auto-stops and logs.
- **Manual** — enter minutes directly from a task's detail sheet.

Logged time drives your **accuracy score** and your **Growth rings**. Untracked work is
invisible to both.

### Task outcomes (statuses)

- **Pending** — not yet done.
- **Completed** — done. Earns `resourceValue × accuracy`.
- **Skipped** — intentionally not doing it. No penalty, no reward.
- **Unsuccessful** — you tried and it didn't work out. Earns **50%** of the value (effort
  counts).
- **Carried forward** — push it into next week (keeps a lineage so you can see how many
  times something has been deferred).
- At week-close, anything still **Pending** becomes **Incomplete** — or **Expired** if it
  had a hard deadline.

Most of these are reversible (un-complete, un-skip, un-carry) until the week is closed.

### Due dates & hard deadlines

- A **due date** is a soft target; you'll get a reminder.
- A **hard deadline** is real: if the task is still pending at week-close, it **expires**
  rather than rolling over. Use it for things that genuinely can't slip.

### The week's bar (commitments)

A week is rarely a flat list of equally-important things. Tap the **star** on a task to mark it
as one of the week's **commitments** — the few whose completion decides whether the week actually
worked. The This Week header then shows **Bar: 3/5**, and when every one is done it says so:

> ★ The week's bar is met — 5/5. Rest is earned.

That sentence is the whole point. A completion percentage tells you how much of the list moved;
it has no idea which of it mattered, so it can never tell you that you're finished. The bar can.

A few deliberate rules:

- **Marking a task essential earns you nothing.** No extra resources, no scoring change. If the
  star paid out, every task would end up wearing one and it would stop meaning anything.
- **Mark a handful, not the list.** If you mark more than about 60% of a week's tasks, week-close
  will tell you: *"8 of 10 tasks marked essential. That's not a bar, that's the list."*
- **A carried task keeps its star.** Something you called essential and didn't do hasn't stopped
  being essential because the week ended — un-star it next week if you've genuinely changed your
  mind. A **recurring** task does *not* inherit it: each new week sets its own bar.
- **Nothing marked means no bar**, not a failed one. Week-close stays quiet about it.

At close, **Commitment** leads the Week in Review with its own week-over-week delta, and a bar you
missed is named: *"3/5 on the bar you set. 2 you called essential didn't happen."*

### Does this week fit?

Once you've closed a few weeks, the This Week header also holds your plan against your own
history:

> 23h planned. Your weeks hold about 11h. This one doesn't fit.

It's the sum of your **estimates** against the **median** logged time of your last eight sealed
weeks. Median, not average — one 40-hour crunch week shouldn't quietly license the next one.

It never blocks you and never re-plans anything, and it stays silent when it hasn't earned the
right to speak: fewer than three weeks of history means there's no baseline, and a guess dressed
as a baseline is worse than nothing. If some tasks have no estimate, it says so — *"4 tasks
unestimated, so that's a floor"* — because a partial total presented as the whole plan is the
same over-commitment wearing a badge.

The point is timing. The honest mirror at week-close can only tell you about a week you can no
longer change; this says it while the week is still yours to shape.

### Estimates & scoring

When you complete a task, its resource value is multiplied by an **accuracy multiplier**
based on estimate vs. logged time:

| Situation | Multiplier |
|---|---|
| No estimate set | ×1.0 |
| Estimate set, but no time logged | ×0.5 |
| Logged time within ±15 min of estimate | ×2.0 |
| Finished faster than estimate (−15+ min) | ×0.9 |
| Ran over the estimate (+15+ min) | ×0.75 |

The lesson the app is teaching: **estimate, then track.** Accurate planning is worth double.

### Operations, notes & costs

- **Promote to operation** turns a task into (or assigns it to) an operation.
- **Notes** — attach free-text notes to a task.
- **Costs** — log usage of an external **cost resource** against a task (e.g. credits/API
  usage). Costs are tracked for reporting; they don't affect scoring.

### Importing tasks

You can bulk-add tasks from JSON (paste it, or share text into LifeOps). The import shows a
**preview** before committing, and can fold unrecognised fields into notes. Handy for
planning a week elsewhere and dropping it in.

### Closing the week

When the week is done, **Close Week**:

1. Snapshots the week (counts, resources, the week's bar, and a sealed per-aspect record for
   your rings).
2. Marks leftover pending tasks **Incomplete** (or **Expired** for hard deadlines).
3. Carries forward the tasks you chose to keep, and re-seeds **recurring** tasks.
4. Starts the next week and draws the closed week's **Growth ring**.

You drive this — close when *you* decide the week is over.

---

## Resources

Completing tasks earns **resources**, shown as a set of named slots. Each slot can be fed by
one or more aspects (configurable), so your effort in, say, *Body* fills a resource you've
themed for it. It's a lightweight RPG layer to make consistent effort feel like it's
accruing into something. Resource slots can be renamed in **Settings → Game Resource Slots**.

---

## Reports

Trends and breakdowns over a selectable range (**30 days / 90 days / Lifetime**):

- **Completion rate** week over week.
- **Aspect balance** — where your resources came from.
- **Time spent** by aspect.
- **Scoring trend** — resources earned per week.
- **Operation health** — completion and time per operation.
- **Completion by priority.**
- **Resource usage** — cost resources consumed.
- **Category slip rates** — where things tend to fall through.
- **Carryover summary** — what keeps getting deferred, and the total time sunk into it.

Reports are read-only; they reflect your closed-week snapshots and logged time.

---

## The Growth Record (rings)

The long game. Each **closed week becomes one ring**, drawn once and never changed. Newer
weeks circle further out from the centre seed.

### How to grow a ring

1. **Log time** against your tasks during the week.
2. **Close the week** on This Week.

That's it — the closed week appears as a new ring. The current (open) week also shows a live
ring from time you've logged so far.

### Reading a ring

- **Bands** — each aspect you spent hours on is a band. The same aspect sits on the **same
  track** in every ring, so you can follow one aspect outward through time.
- **Thickness of a band** — proportional to hours that week (with a small floor so a tiny bit
  of effort still shows).
- **Colour** — brighter/more saturated = more hours. ~50 hours reads as "full"; beyond that
  it gains a glow. A barely-touched aspect is muted, not black.
- **Scar** — a week with **zero logged hours** is a permanent grey ring. Never a gap, never a
  reset. It's there to be seen.

### The one rule

A ring, once drawn, **never changes**. Logging a huge week later won't reshape last month;
adding a new aspect won't repaint old rings; renaming or recolouring an aspect won't alter
history. Your record is sealed at week-close — it can't be faked, ground, or back-dated. That
permanence is the entire point.

### Controls

- **Colour by hours** / **Glow** — display toggles. They change how the rings *look*, never
  what's recorded.
- **Zoom** — pinch, or use the slider. Long records auto-shrink to stay on screen.
- **Pan** — drag. **Fit** re-centres.
- **Tap a ring** — see that week's per-aspect hours.
- **Legend** — the most recent week's hours per aspect, with the rendered colour.

### Exporting rings

From **Settings → Data**:

- **Rings CSV** — a `week × aspect` grid of hours (great for spreadsheets).
- **Rings SVG** — a clean vector image of the whole record, glow and gradients intact.

---

## Settings

- **Aspects & Categories** — create, rename, recolour, and archive. Colours come from a
  curated, well-separated palette. (Archiving keeps history intact; it just stops new use.)
- **Notifications** — set the default daily reminder time.
- **Theme** — light/dark, colour presets (Default, Beacon, Ocean, Sunset), or a fully custom
  palette. This is **suite-wide**: it is the same setting as the Operations Sandbox's gear, so it
  paints Citation, Logistics, Advisor, Health and People too. Each app's own accent colour is
  chosen in that gear, as is the wallpaper behind the sandbox's home screen — a shipped design
  (Midnight, Aurora, Sunrise, Paper…), your own gradient, or the suite's own colours.
- **Data** — Backup JSON, Restore, Export Tasks CSV, and the Rings CSV/SVG exports.
- **Game Resource Slots** — rename your resource slots.
- **Cost Resources** — define external resources to track per task (with optional reset
  cycle and capacity).
- **Operations** — create and manage operations, mark them complete/active.

---

## The home-screen widget

LifeOps includes a widget that shows your top pending tasks at a glance, so the week's work
is visible without opening the app. Add it from your launcher's widget picker.

---

## Notifications

- **Task reminders** at your configured time for tasks with due dates.
- **Week-close reminder** nudging you to wrap up and close the week.

If notifications are disabled, reminders won't fire — you can re-enable them in your system
settings for LifeOps. (Exact-alarm permission may be requested on newer Android versions so
reminders land on time.)

---

## Backup, restore & exports

Everything is stored **locally on your device**. There is no cloud and no account — so
**back up regularly**.

- **Backup JSON** (Settings → Data) — the complete, restorable snapshot: aspects, weeks,
  tasks, time, notes, snapshots, operations, cost data, and your palette. **Restore** reads it
  back. This is the one true backup.
- **Export Tasks CSV** — every task as one row, with aspect/category/operation names, logged
  time, costs, notes, and timestamps. Great for spreadsheets; **not** restorable.
- **Rings CSV / SVG** — your Growth Record as data or as a vector image.

Files are plain text (no compression). Keep a backup somewhere off-device.

---

## FAQ & troubleshooting

**My Growth screen is empty.** Rings appear after you **log time** and **close a week**. Log
some minutes against a task, then close the week.

**A week shows as a grey scar.** That week had zero logged hours. Scars are permanent and
intentional — they're part of the honest record.

**I logged a big week but an old ring didn't change.** Correct, and by design. History is
sealed at week-close and never repainted.

**I renamed/recoloured an aspect and old rings kept the old look.** Also by design — the ring
remembers the aspect as it was that week. New weeks use the new name/colour.

**Where did my time go in the scores?** Only **logged** time counts. If you didn't run a
timer or enter minutes, the app can't see the effort.

**Completing a task with an estimate but no logged time only gave half points.** Yes — an
estimate with no tracked time scores ×0.5. Track the time to earn full (and ×2.0 if you
estimated accurately).

**Did I lose data when I reinstalled?** Possibly — data is local. Restore from your latest
**Backup JSON**. Back up regularly.

---

## Privacy

LifeOps does not phone home. Your data stays on your device and is only ever shared when
*you* export or share it. The honesty of the record is for you, not for anyone else.
