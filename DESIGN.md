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
- Level cap conversion: 1 banked unit per in-run level of ceiling (1:1)
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

- **Starting weapon — player-aimed.** The SAS skill layer. Three unlocks, each
  with a distinct **range** and a **magazine + reload** cadence:
  - **Sniper**: burst / precision / single-target. **Unlimited range** — its
    shots never fall short (they fly until they hit or leave the arena, and
    auto-aim locks on anywhere on the field). The **premier big-target killer**:
    the highest per-shot damage and crit multiplier in the game, so it deletes
    elites and out-damages every other gun on bosses — but its single-target
    focus and small magazine make it weak vs trash. Drafts toward swarm-clearing
    automatics to cover that gap.
  - **Gatling**: sustained stream, **medium range** (bullets expire at their
    reach). **Spins up with no ceiling**: the fire rate starts low and climbs the
    longer you hold continuous fire (1 shot/s + 2/s per second engaged), with **no
    cap** — it keeps accelerating until the magazine empties, and **resets the
    moment fire stops** — reward for sustained bursts. Its base `FIRE_RATE` is only
    the reference that fire-rate passives scale and the wind-up meter fills toward,
    not a cap. Damage is **not DPS-normalized**: extra projectiles multiply
    throughput, so projectile ranks (Splitter) are a real power spike on it. Large
    magazine.
  - ⚠️ Uncapped spin-up + full-damage projectiles make the Gatling the throughput
    platform: any future per-hit proc modifier (on-hit heal, on-hit chance) scales
    with both hit count and the sustained-fire ramp. Price Phase-2 on-hit and
    projectile modifiers with the gatling in mind.
  - **Shotgun**: a **short-range** cone of pellets — devastating up close,
    useless at distance. Each pellet hits full (not DPS-normalized). Small
    magazine, wide spread.
- **Magazine + reload.** Every starting weapon fires from a magazine sized by the
  `MAGAZINE` stat (weapon base, raised by the **Extended Mag** artifact at +20%
  per rank); one trigger-pull spends one round however many projectiles it
  throws. Emptying the magazine **auto-reloads**; the player can also **reload on
  demand**. Reload time is the weapon's base reload divided by the `RELOAD_SPEED`
  stat — the **Autoloader** artifact raises it (shorter reloads), so Autoloader
  is now a *reload* artifact, not a raw fire-rate one.
- **Artifact weapons — automatic.** The VS build layer. Run themselves; player
  attention stays on one aim stick. The baseline example is the **turret**: it
  is the [`Turret` combat-equipment artifact](#5-artifacts-baseline-pool-2-faces),
  auto-deployed near the player on a cooldown with a limited TTL, then expiring.

Input: hybrid — auto-fire at nearest target with a manual aim-override stick
and strong aim magnetism. One-handed playable; twin-stick feel when wanted.
Decide feel details on-device; do not bury aiming under build complexity.

---

## 5. Artifacts (baseline pool: 2 faces)

Rolled as choices on level-up. Every artifact carries a **category** so the
draft (and the codex) reads as two faces:

- **Stat support** — pure stat math on the weapon/entity you already carry.
  Five of them (Overclock damage, Autoloader reload speed, Splitter projectiles,
  Adrenaline move speed, Extended Mag magazine size), each with **4 pure-additive
  stacking ranks** (e.g. +10% → +20% → +30% → +40%).
- **Combat equipment** — deploys an **automatic weapon**. The baseline example
  is the **Turret** (4 ranks): rank 1 deploys one auto-turret (`TURRET_COUNT`,
  AUTO scope), kept near the player with a limited TTL; ranks 2–4 each roll a
  **random** turret upgrade from the AUTO-scope pool (damage / fire rate /
  projectiles / range / an extra turret), so the equipment grows a different way
  each run. The random rolls are the engine's job — the artifact row itself only
  carries the rank-1 turret grant, keeping "artifacts are data" intact.

Rolling a duplicate upgrades its rank — no dead offers.

Balance warnings (treat knowingly, don't balance as peers of flat %):
- **+projectile count** is secretly multiplicative (1→2 is +100%, and each
  projectile multiplies with damage/crit/on-hit).
- **+1 concurrent turret** is the same stat in disguise — hence the combat-
  equipment turret's smaller rank cap.

Baseline artifacts do **stat math only** — including the turret, whose "behaviour"
is just the engine reading `TURRET_COUNT`. Real behaviour (event hooks,
conditionals, multiplicative weirdness) is Phase-2 shop territory. Keep the
vanilla game legible so shop modifiers land as transformative.

Store ranks as data rows now: `(artifact_id, rank, stat, scope, value)` —
this table is the seed of the whole modifier system.

---

## 6. Leveling & max-level overflow

- In-run XP levels the player up to the run's Level Cap (funded from bank).
- Every level-up offers a choice (artifact roll or small stat picks). Choice
  cadence is the run's pulse; it must never stop.
- **At cap, the XP bar keeps filling.** Each overflow fill pauses for a
  **player-chosen** micro-pick of three instant effects — **bonus gold**,
  **healing**, or a **short temporary stat boost** (a timed surge that folds
  into the stat block and cleanly falls off when it expires) — **and** adds
  score. Overflow rewards are strictly in-run — overflow gold dies with the run
  (protects non-fungibility).
- Score is the arcade layer on top; week-seeded runs make the scoreboard a
  record of which weeks were legendary. The **Scoreboard** (Game hub) logs every
  finished run — Week seeded, points invested in the loadout, Score, and the
  Set·Wave reached — best score first.
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
- **Placed defenses (YAZD).** The player spends gold to build two static defenses
  on the grid, snapped to cells — permanent for the run and destructible: the
  **barricade** (a blocking wall) and the **Sentry** (a static gold turret that
  stays put and fires on its own fixed stats). Select the tool from the palette,
  tap the arena to place; drag still moves. The **auto-deployed Turret** (the
  combat-equipment artifact, §4/§5) is the mobile counterpart — it redeploys near
  the player and expires on a TTL — so gold buys *static* emplacements while the
  draft grants *following* fire support.
- **Blocking stops movement *and* fire.** A blocking structure (barricade or
  Sentry) both halts enemies at its cell and **stops enemy shots** — a Spitter
  can't fire through a wall. Friendly shots pass over your own defenses, so a
  Sentry never blocks its own or the player's fire.
- **Everything is hits, not HP bars.** The player has a small pool of **hearts**
  (baseline 3, buyable via the Max-Health loadout) with brief i-frames, so one
  contact costs one heart. A normal enemy hits for 1, a boss for 3 (one-shots a
  base player). Turrets fall to 1 hit, barricades to 3. Baseline trash dies to a
  single shot; elites/bosses take more.
- **Waves + a cumulative boss roster.** SAS-style waves, heavier than a classic
  holdout, ending in the finale. Enemy archetypes are **unlocked by tier** — each
  loop reveals a new trash type (Spitter at tier 2, then Rusher, then the **Nest**
  at tier 4 — a stationary sac that sits where it lands and hatches Shamblers on a
  slow cadence, so it must be prioritised or it buries you), chosen from the
  unlocked pool by weight; the ranged Spitter fires in **bursts**. The **boss
  roster is cumulative**: every boss unlocked so far spawns together on the final
  wave — Abomination (tier 1), + Spitter Boss (tier 2, fires without pause), +
  Rusher Swarm (tier 3, several fast rusher-bosses at once), + the **Mother** (tier
  4, a boss that **flees** the player and **births a random enemy** on a fast
  cadence — and every minion of hers that dies **resets her timer**, so thinning
  the brood only speeds her up; corner her against the edge to end it), and beyond.
- **Drops scale with the kill.** Trash stays stingy (~25% XP / 5% gold); elites
  (Husk, Spitter, Nest) pay out far more often; bosses always drop XP, gold, and
  a heart. So farming the dangerous things — not the swarm — is what funds you.
- **Endless.** Clearing all `waves` + the boss(es) loops back to wave 1 at the
  next **tier**, with pure multipliers scaling enemy hp/speed/damage/count — the
  enemies come back "leveled up". The run only ever ends on death; score and the
  tier reached are the record of how long you held.
- **Weekly dev run.** A once-a-week sandbox for trying builds without touching the
  economy: a fully-funded loadout (ceiling level cap, max hearts, a starting purse)
  with **no banked spend**, a **free** between-set store, and free revives. It is the
  same endless engine with two knobs — `maxSets` auto-ends it in **victory** after a
  fixed number of sets (4) instead of looping forever, and `devRun` marks it a
  sandbox. Nothing earned in it is permanent: store picks vanish with the run, no
  unlock is recorded, and it is never logged to the scoreboard. The once-a-week gate
  is a stored `lastDevRunWeek` marker that clears when the week rolls over.
- **Week-seeded.** Hash the closed week's snapshot into the run seed so spawn
  order/composition is a fingerprint of the week (geometry is not seeded).
- Entry: debit run's energy price. Loadout draws on banked Level Cap, Max
  Health, Starting Gold. All committed resources are expended by the run.
- **Revive.** On death you can buy back into the same run for **2× the entry
  price** (energy), restoring full hearts, granting a grace window of
  invulnerability, and clearing the swarm around you so the revive isn't
  instantly undone (bosses stay). There's no per-run count cap — banked energy,
  the scarce resource, is the only gate, so it self-limits. The run is scored
  once, at its true end, with the final (higher) score.

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

**Implemented — the between-set store.** After each set's boon/bane draft the run
pauses on a **store** (`RunStatus.STORE`) that offers **4 random** unowned picks
from a data-authored catalog (`content/StoreCatalog.kt`), **pick one or skip**.
It is paid in banked **Modifier Budget** — the Personal-aspect resource
(`Loadout.Role.MODIFIER_BUDGET`, the 5th "Spirit" slot); the spend + the permanent
unlock record are brokered by the ViewModel against the bank, never the pure engine
(the same contract as revive). Four tiers, steep fixed prices: **Modifiers 25**
(run-wide mutators / "skulls"), **Passives 50** (stat-support artifacts),
**Equipment 100** (turret-family), **Guns 150** (new aimed weapons). A purchase is
granted to the current run immediately — a passive/equipment joins the build (and
the level-up draft), a gun swaps the aimed weapon, a mutator applies its run-scoped
effect — and is unlocked **permanently** (`game_unlocks`), so future runs get it
back: passives/equipment rejoin the level-up draft pool, guns rejoin the loadout
roster, mutators become loadout opt-ins. Adding stat-only content is appending a
`StoreCatalog.Item`, not engine code.

**What each tier is _for_** (the design intent that keeps the four faces distinct
— author new content to its tier's promise, not just its price):

- **Modifiers** change the *run itself* — they affect both the player and the
  enemies (a boon riding a bane, a "skull"). They reshape the whole fight, up or
  down, for everyone on the field.
- **Passives (artifacts)** boost the *player and their weapon*. Their job is to
  make climbing **possible** — the raw stat/behaviour headroom a build needs to
  survive higher sets.
- **Equipment** gives the player a real **edge or support** on the battlefield
  (something fighting *alongside* you, not just better numbers). Its job is to
  make climbing **easier**.
- **Guns** are *player identity*. Each one must be worth building an entire run
  around — a distinct playstyle, not a stat swap. If a new gun wouldn't change how
  you'd draft and position for the whole run, it isn't a gun, it's a passive.

**On-hit behaviour passives.** Pierce, Ricochet and Explosive Rounds change how a
shot *behaves* for any gun. They follow the same "stat, not code" rule as the
turret: each adds a stat (`PIERCE` / `RICOCHET` / `EXPLOSION_RADIUS`) resolved
once at fire time and stamped onto every pellet, and the projectile-resolution
step reads those fields — a shot survives a hit while it has pierce budget (goes
straight through), then bounces to a fresh target while it has ricochet budget,
and splashes `EXPLOSION_DAMAGE_FRAC` of its damage in-radius when explosive. New
on-hit behaviours are a new stat + a few lines in the collision step.

**Equipment upgrade pools.** Every combat-equipment artifact rolls a *variable*
upgrade on each rank past the first (the turret's original trick, generalised).
`content/EquipmentUpgrades` is one registry of per-equipment pools —
`poolFor(id)` maps an equipment's modifier id to its list of authored upgrade
rows — and the level-up roll draws from the pool for *that* equipment. Turret
upgrades are AUTO-scope weapon stats; **Mines** upgrades are dedicated `MINE_*`
stats (count / damage / blast radius / trigger range) so the two never bleed into
each other. Mines are the second equipment: rank 1 sows proximity mines that the
engine keeps stocked near the player and detonates for an area blast on contact;
ranks 2+ roll from `EquipmentUpgrades.MINES`. A new equipment is a deploy/behaviour
in the engine reading its own stat + a pool in the registry.

**Enemy targeting + the Decoy.** Enemies used to hardcode "beeline the player";
now each enemy resolves a target each frame (`targetStructureFor`). **Rushers**
are base-breakers — they make for the nearest structure (turret / sentry /
barricade / decoy) before the player, so defences actually draw them. The
**Decoy** is the third equipment: a non-blocking `StructureType.DECOY` the engine
plants near the player (build-scaled `DECOY_HP`, replanted as it's torn down) that
**hijacks aggro** — any *non-rusher* enemy that has strayed beyond `DECOY_RANGE`
of the player peels off to attack the nearest decoy, so close pressure still lands
on you while the outer swarm is pulled off. Its pool (`EquipmentUpgrades.DECOY`)
rolls +count / +durability / +lure range / detonate-on-death. A targeted
non-blocking structure is attacked on reach (it can't be walked-through-and-hit
like a wall), so a decoy only takes damage from enemies that *chose* it.

**The Outpost.** The fourth equipment plants a *permanent* strongpoint rather than
mobile support, so it complements the always-drafted **Turret** instead of
duplicating it. Rank 1 grants `OUTPOST_COUNT`; the engine keeps that many
emplacements planted near the player, each a static `StructureType.SENTRY` with a
`StructureType.BARRICADE` walled in one cell beyond it (barricade | sentry |
player), and — unlike the auto-turret — they never expire. Its pool
(`EquipmentUpgrades.OUTPOST`) is `GLOBAL`-scoped because it buffs the whole
defensive line, not just its own emplacements: +1 outpost, `SENTRY_DAMAGE` /
`SENTRY_FIRE_RATE` multipliers read at fire time by *every* static Sentry, and
`BARRICADE_THORNS` flat retaliation every Barricade deals to whatever strikes it
(gold-built Sentries and Barricades included). The engine tallies only its own
emplacements (`fromOutpost`), so buying gold defences never suppresses deployment.

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
