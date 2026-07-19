# LifeOps Game Module — Design Document

Arcade survivors-roguelike attached to the LifeOps app. Run resources are earned
by completing real-life tasks; every run spends them. SAS: Zombie Assault-style
aimed top-down zombie shooter with Vampire Survivors-style progression.

This document is the contract for all implementation sessions. When an
implementation choice conflicts with a rule here, this document wins. If a rule
must change, change it here first.

---

## 1. Invariants (never violate)

1. **Non-fungibility.** No conversions between resources, ever. No exchange
   rates, no converter items, no zany modifier that transmutes one resource
   into another. The incentive design depends on it: work output must never be
   able to purchase what only family/personal time produces.
2. **Everything routes through the event bus** — from day one, even with zero
   listeners. On-hit, on-kill, on-levelup, on-pickup, on-heal, on-spawn,
   on-projectile-spawn. No direct combat function calls that bypass it.
3. **Degrade, don't crash.** The effect resolver has a per-frame effect budget
   and a recursion depth cap. When a modifier combo goes exponential, throttle
   and render as a visual "reality strain" effect. Never ANR.
4. **Modifiers are data, not code.** Artifacts, shop items, and challenge modes
   are rows in one table, differing by `attaches_to`. Adding content must be
   authoring, not engineering.
5. **Only closed weeks emit resources.** Closing the week is the mint. Unclosed
   weeks pay nothing.
6. **Locked out at zero.** No free runs. If you can't pay the energy cost, you
   can't play. Resources bank indefinitely across weeks (sprint, then rest).

---

## 2. Resource economy

Already implemented in LifeOps (Game Resources screen). Resources accrue as raw
earned `resourceValue` from completed tasks in mapped aspects, with per-aspect
multipliers (currently ×1.0). Cards show This Week / Balance / Lifetime.

| Resource                  | Source aspect(s)          | Role in run                          |
|---------------------------|---------------------------|--------------------------------------|
| Level Cap                 | BV IT Services + Beacon   | Ceiling on in-run leveling           |
| Energy                    | Family                    | Coin-in-the-machine; pays run entry  |
| Starting Gold             | Home                      | In-run purchases (barricades, turrets, welds) |
| Max Health                | SWCA                      | Run survivability                    |
| Universal Modifier budget | Personal                  | Permanent draft shop currency (Phase 2) |

**Intentional asymmetry.** Energy and Modifier budget are the scarce, precious
resources; excess Health and Level Cap are harmless because the run structure
itself imposes diminishing returns (finite run length and map XP density bound
how much ceiling/HP can matter). Do not add punitive caps on work-derived
resources; tune where their ceiling bites via XP density and damage numbers.

**Pricing.** Units are arbitrary, so prices carry all balance. Derive prices
from observed trailing income (~8-week average) against target cadences, and
re-derive periodically so the economy tracks real life:

- Run entry: trailing weekly energy income ÷ ~2.5 target runs/week
  (at +25/week observed → ~10 energy per run)
- Modifier draft: ~1 draft per week of full Personal completion (~5 units at
  observed rates)
- Level cap conversion: ~10 banked units per in-run level of ceiling
- Manual override multipliers allowed on top; observed income sets baseline.

**Gold is the comfort resource.** Home income is legitimately lumpy. Runs must
be clearable with zero starting gold; gold buys convenience and holdout
infrastructure, never necessity.

**Energy accrues fractionally, spends in whole units.** Never floor weekly
fractions away.

**Goal-gradient UI.** Each resource card shows the nearest concrete purchase:
"next: [item] — N away." Something affordable-soon must always exist per
resource (ladder of small purchases, not one big-ticket item). Draft previews
show face-down cards behind the price.

---

## 3. Entity & stat model

Player, enemies, turrets, and artifacts all share **one stat block schema**.
Artifacts are components that attach to any entity, read that entity's stats,
and fire that entity's events. (Enemies wielding artifacts must be the same
code path as the player wielding them.)

Every stat row carries a **scope tag**: `aimed` | `auto` | `global`.

- **Weapon stats** (`aimed`) boost the player's aimed starting weapon.
- **Equipment stats** (`auto`) boost artifact weapons (turrets etc.).
- Unmapped stats are **inert** on entities that don't use them — no implicit
  "same field name = same effect."

Cross-entity meaning is assigned only through **explicit remap tables**
(see Challenge Modes): e.g. `player.projectiles → director.spawnMult`. Remap
tables are designable content rows, not code.

---

## 4. Weapons

Two classes:

- **Starting weapon — player-aimed.** The SAS skill layer. Two unlocks:
  - **Sniper**: burst / precision / single-target. Deletes elites, weak vs
    trash. Drafts toward swarm-clearing automatics.
  - **Gatling**: sustained stream, total DPS **normalized across projectile
    count** (more projectiles = same DPS as more, smaller hits). Projectile
    ranks buy coverage/smoothness, not throughput; damage% is its premium
    artifact.
  - ⚠️ Note in the gatling weapon def: DPS-normalization is a baseline-era
    truce. Any future per-hit proc modifier (on-hit heal, on-hit chance) scales
    with hit count and makes the gatling the proc platform. Price Phase-2
    on-hit modifiers with the gatling in mind.
- **Artifact weapons — automatic.** The VS build layer. Run themselves; player
  attention stays on one aim stick. Example: auto-deployed turret on cooldown
  with limited TTL.

Input: hybrid — auto-fire at nearest target with a manual aim-override stick
and strong aim magnetism. One-handed playable; twin-stick feel when wanted.
Decide feel details on-device; do not bury aiming under build complexity.

---

## 5. Artifacts (baseline pool: 4)

Rolled as choices on level-up. Each has **4 pure-additive stacking ranks**
(e.g. +10% → +20% → +30% → +40% damage; +1 → +2 projectiles). Rolling a
duplicate upgrades its rank — no dead offers.

Balance warnings (treat knowingly, don't balance as peers of flat %):
- **+projectile count** is secretly multiplicative (1→2 is +100%, and each
  projectile multiplies with damage/crit/on-hit).
- **+1 concurrent turret** is the same stat in disguise.

Baseline artifacts do **stat math only**. Behavior (event hooks, conditionals,
multiplicative weirdness) is Phase-2 shop territory. Keep the vanilla game
legible so shop modifiers land as transformative.

Store ranks as data rows now: `(artifact_id, rank, stat, scope, value)` —
this table is the seed of the whole modifier system.

---

## 6. Leveling & max-level overflow

- In-run XP levels the player up to the run's Level Cap (funded from bank).
- Every level-up offers a choice (artifact roll or small stat picks). Choice
  cadence is the run's pulse; it must never stop.
- **At cap, the XP bar keeps filling.** Each overflow fill grants a micro-pick
  of 2–3 instant effects (damage burst, heal, gold pile, short temp boost)
  **and** adds score. Overflow rewards are strictly in-run — overflow gold dies
  with the run (protects non-fungibility).
- Score is the arcade layer on top; week-seeded runs make the scoreboard a
  record of which weeks were legendary.
- Target pacing: 4 artifacts × 4 ranks = 16 meaningful level-ups; cap in the
  low-20s means most runs cap out with a few overflow picks at the end.

---

## 7. Run structure

The run is **Geometry Wars meets a zombie-defense holdout**: an open single
screen (no obstacles by default) that you *widen*, defended with waves, upgrades,
and — soon — your own placed turrets and barricades.

- **Open, expandable arena.** A fixed maximum world with a small centred *active*
  region; everything outside is dead margin. Spending gold pushes the active
  region out a stage at a time (the "unlock to widen the space"). Player and
  enemies are bounded by the current active edges; enemies enter at those edges
  and **beeline** at the player. A grid overlays the arena — the lattice
  player-placed turrets/obstacles snap to.
- **Placed defenses (YAZD).** The player spends gold to build turrets (auto-fire
  friendly shots) and barricades (block + soak) on the grid, snapped to cells.
  Permanent for the run and destructible — enemies blocked by one attack it until
  it falls. Select a tool from the palette, tap the arena to place; drag still
  moves.
- **Everything is hits, not HP bars.** The player has a small pool of **hearts**
  (baseline 3, buyable via the Max-Health loadout) with brief i-frames, so one
  contact costs one heart. A normal enemy hits for 1, a boss for 3 (one-shots a
  base player). Turrets fall to 1 hit, barricades to 3. Baseline trash dies to a
  single shot; elites/bosses take more.
- **Waves + a cumulative boss roster.** SAS-style waves, heavier than a classic
  holdout, ending in the finale. Enemy archetypes are **unlocked by tier** — each
  loop reveals a new trash type (Spitter at tier 2, then Rusher, then Brute),
  chosen from the unlocked pool by weight; the ranged Spitter fires in **bursts**.
  The **boss roster is cumulative**: every boss unlocked so far spawns together on
  the final wave — Abomination (tier 1), + Spitter Boss (tier 2, fires without
  pause), + Rusher Swarm (tier 3, several fast rusher-bosses at once), and beyond.
- **Drops scale with the kill.** Trash stays stingy (~25% XP / 5% gold); elites
  (Husk, Spitter, Brute) pay out far more often; bosses always drop XP, gold, and
  a heart. So farming the dangerous things — not the swarm — is what funds you.
- **Endless.** Clearing all `waves` + the boss(es) loops back to wave 1 at the
  next **tier**, with pure multipliers scaling enemy hp/speed/damage/count — the
  enemies come back "leveled up". The run only ever ends on death; score and the
  tier reached are the record of how long you held.
- **Week-seeded.** Hash the closed week's snapshot into the run seed so spawn
  order/composition is a fingerprint of the week (geometry is not seeded).
- Entry: debit run's energy price. Loadout draws on banked Level Cap, Max
  Health, Starting Gold. All committed resources are expended by the run.

---

## 8. Challenge modes (data, not features)

Random/optional run mutators built entirely from the shared stat block +
remap tables:

- **Mirror mode**: enemy director receives the player's boosts through an
  explicit remap table (e.g. `projectiles → spawnMult`, `damage → enemyHP`).
  Self-balancing difficulty that inflates in lockstep with player power —
  never needs re-tuning. Different remap rows = different Mirror variants.
- **Mob Boss mode**: each enemy spawns holding one artifact (same attachment
  code path as the player).

A challenge mode is a modifier row with `attaches_to: run/director`.

---

## 9. Phase 2 — Modifier draft shop

Deferred until the baseline run is complete. Star content: weighted authoring
effort goes here.

- Pay Universal Modifier budget → random draft, pick 1 of 3.
- Modifiers are permanent, hook events, emit events; combos are emergent from
  composition (never hand-coded pairings).
- Purchased modifiers are real records in the modifier table from the first
  purchase — the run client consumes them with no migration.
- One modifier system, three faces: artifacts (entity-attached), shop items
  (player-attached, permanent), challenge modes (run/director-attached).

---

## 10. Build order

1. **Core**: stat block schema + scope tags, entity attachment, event bus,
   effect resolver with budget/recursion caps, modifier table. Tests first.
   No rendering.
2. **Run loop**: Compose Canvas + Choreographer frame loop; player movement,
   hybrid aim, one enemy type, XP/level-up flow, energy debit, run summary.
3. **Content as data**: sniper + gatling defs, 4 artifacts × 4 ranks, turret
   entity, 2–3 enemy types, waves, gold sinks, overflow picks.
4. **Economy wiring**: prices derived from trailing income; "next: N away"
   on resource cards; week-seeded runs.
5. **Phase 2**: draft shop, behavior modifiers, challenge modes.

## 11. Tech notes

- Module inside the existing LifeOps Android app (Kotlin/Compose). Resource
  ledger = direct Room reads/writes; a run's multi-resource debit is one
  transaction. No sync layer.
- Compose Canvas + Choreographer handles baseline entity counts. Embed libGDX
  only if counts reach thousands — not before.
- Aspect mapping keys on aspect **role** (employer/venture/family/home/
  personal), not literal aspect names, so renames don't break emissions.
- Systems come from Claude Code sessions; **feel** (hit pacing, spawn rhythm,
  weapon satisfaction) is tuned manually on-device. Expect that split.
