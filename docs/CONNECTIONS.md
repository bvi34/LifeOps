# Connection functions

LifeOps exposes internal actions through a single, uniformly-addressed dispatch layer. Every
callable action has one stable name of the form:

```
/v1/{application}/{connection}/{resource}/{action}
```

For example:

```
/v1/LifeOps/local/task/create
/v1/LifeOps/local/task/update
/v1/LifeOps/local/task/complete
/v1/LifeOps/local/task/delete
```

This is an **in-process** routing convention, not an HTTP API — the app is offline and
single-user, so there is no server behind these addresses. The scheme exists so that internal app
comms and (later) external integrations share one addressing model and one call path.

**Two applications serve routes.** LifeOps was the first and is the reference; **Project** is the
second, and its arrival is what the `application` segment was reserved for. Each owns a
`ConnectionDispatcher` built from the same machinery (`ConnectionAddress`, `ConnectionRegistry`,
`ConnectionParams`, `ConnectionResult`) and answers only for its own segment — a `/v1/Project/…`
address sent to LifeOps' dispatcher fails with `UNKNOWN_APPLICATION`, and the reverse likewise. One
convention across the suite; one dispatcher per app that owns the data.

## The five segments

| Segment | Example | Meaning |
|---|---|---|
| `version` | `v1` | Address-contract version. Bumped only on a breaking shape change. |
| `application` | `LifeOps`, `Project` | The owning app. Two apps serve routes today; each has its own dispatcher and answers only for its own segment. |
| `connection` | `local` | The namespace / transport. `local` is internal app comms; a named connection (an API or integration name) is reserved for future external integrations. |
| `resource` | `task` | The noun being acted on. |
| `action` | `create` | The verb. |

## Layers

```
caller ──▶ ConnectionDispatcher ──▶ RouteHandler ──▶ Service (use-case) ──▶ Repository ──▶ Room
             (parse + validate)      (adapt payload)   (lifecycle policy)     (persistence)
```

- **`ConnectionDispatcher`** parses the address string, validates the version/application/connection,
  finds the handler, and converts any failure into a typed `ConnectionResult.Failure` — callers
  never catch exceptions.
- **`RouteHandler`** is a thin adapter: it reads and coerces the `ConnectionParams` payload, then
  delegates to a service. It never contains business logic.
- **Service layer** (e.g. `TaskService`) owns the *policy* of a resource's lifecycle
  (current-week resolution, de-duplication, scoring, notification scheduling). Both the connection
  routes and the screen ViewModels call it, so every path behaves identically. Each ViewModel
  constructs the service(s) it needs inline from the repositories it already holds; reads and
  genuinely cross-domain steps (task↔operation promotion, clearing a category off tasks) stay on the
  repositories.
- **Repositories** remain the single source of truth for persistence.

## Calling a route

```kotlin
val result = app.connectionDispatcher.dispatch(
    "/v1/LifeOps/local/task/create",
    ConnectionParams.of(
        "title" to "Ship the thing",
        "priority" to "high",
        "dueDate" to "2026-07-31"
    )
)
when (result) {
    is ConnectionResult.Success -> result["id"]           // new task id
    is ConnectionResult.Failure -> result.error           // typed ConnectionError
}
```

### `ConnectionError` values

`MALFORMED_ADDRESS`, `UNSUPPORTED_VERSION`, `UNKNOWN_APPLICATION`, `UNKNOWN_CONNECTION`,
`ROUTE_NOT_FOUND`, `NOT_FOUND` (route ran, entity absent), `INVALID_PARAMS`, `HANDLER_ERROR`.

## Adding a route

1. Add the use-case method to the relevant service (or create a new `*Service` in
   `connection/service/`).
2. Register the route in the connection's registrar (e.g. `local/LocalTaskConnection.kt`):
   ```kotlin
   registry.register("local", "week", "close") { request ->
       val id = request.params.requireString("weekId")
       weekService.close(id)
       ConnectionResult.ok("weekId" to id)
   }
   ```
3. Wire the registrar into `Connections.buildDispatcher(...)`.

`requireString` (and peers) throw on missing input; the dispatcher turns that into
`INVALID_PARAMS`, so handlers stay boilerplate-free.

## Registered routes

All under the `local` connection today (`/v1/LifeOps/local/…`). Project's own are listed
[below](#projects-routes).

| Resource | Actions | Service | Notes |
|---|---|---|---|
| `task` | `create`, `update`, `complete`, `delete` | `TaskService` | Reference implementation. `create` is also how Advisor adds a task you asked it for (`data/action/LifeOpsTaskWriter`). It skips a title already used in the current week — the incumbent survives — unless the caller passes `allowDuplicateTitle`, which is for an app that owns its task by *id* and dedups on that (`TaskService.findOpen` is how such a caller adopts one you wrote by hand instead). See [MAINTENANCE.md](MAINTENANCE.md#the-lifeops-week). |
| `week` | `current`, `close` | `WeekService` | `close` mints the next week, snapshots the closing one, seeds recurring series; optional `selfRating`/`selfRatingNote`, `mentalReset` (bool) and `exhaustion` (1–10) are sealed into the snapshot. |
| `operation` | `create`, `update`, `complete`, `reopen` | `OperationService` | `complete`/`reopen` flip status. |
| `counter` | `create`, `log`, `archive`, `update` | `CounterService` | `log` ticks a counter/habit; `occurredAt` (epoch millis) backdates. |
| `note` | `add`, `delete` | `NoteService` | Task notes. |
| `aspect` | `create`, `rename`, `archive` | `AspectService` | `create` is find-or-create. |
| `category` | `create`, `archive` | `AspectService` | `create` needs `aspectId`; find-or-create. |
| `person` | `create`, `rename`, `archive`, `delete`, `addNote`, `attach`, `detach` | `PersonService` | `attach`/`detach` link a person to a task. Every write here also stamps the row for the **People sync seam**, so a person created through a route reaches People on the next round exactly like one typed into a screen — see [PEOPLE.md](PEOPLE.md). `delete` additionally records a tombstone, since a deleted row has nothing left to publish. |
| `busyBlock` | `create`, `delete` | `BusyBlockService` | `daysMask` bitmask for weekly recurrence; `specificDate` for one-off. |
| `timeEntry` | `log` | `TimeEntryService` | Logs minutes against a task. |
| `book` | `create`, `update`, `setStatus`, `delete`, `addNote`, `logTime` | `BookService` | `setStatus`: `to_read`/`reading`/`done`. |
| `food` | `createCustom`, `log`, `logAdHoc`, `confirm`, `adjust`, `promote` | `FoodService` | `unit`: `gram`/`serving`; macros are `Double`. |
| `recipe` | `create`, `update`, `delete`, `addIngredient`, `removeIngredient` | `RecipeService` | `create`/`update` carry `instructions` (the method, one step per line) and `sourceUrl`. On `update` an omitted field is left alone; a blank one clears it. |
| `menu` | `plan`, `unplan` | `MealPlanService` | `plan` commits a recipe (or a freeform `mealName`) to a `date`; a recipe-backed plan also writes that day's *planned* diary entry. `unplan` drops the meal and its still-unconfirmed entry — a confirmed one is kept. |
| `futureOperation` | `create`, `addNote`, `archive`, `delete` | `FutureOperationService` | The "someday" backlog. |
| `costResource` | `create`, `archive` | `CostService` | Budgets/quotas. |
| `cost` | `log`, `delete` | `CostService` | Per-task cost entries. |
| `activity` | `create`, `delete` | `ActivityService` | Saved outdoor activities. |
| `runbook` | `create`, `delete`, `stamp` | `RunbookService` | `stamp` writes a runbook's steps as subtasks on a task. |
| `subtask` | `check`, `delete` | `RunbookService` | — |
| `wellness` | `checkin`, `sleep` | `WellnessService` | `checkin` takes `trend` (`BETTER`/`SAME`/`WORSE`) + `initiative` (`YES`/`NEUTRAL`/`NO`), plus an optional `sensoryTrend` (`BETTER`/`NEUTRAL`/`WORSE`); `energy`/`sensory` are optional exact 1–10 ratings, each derived from its trend when omitted. |

Missing/unknown ids return `NOT_FOUND`; the route still exists, the entity does not.

**Deliberately not routed:** task image attachments (creation is bound to an Android photo-picker
`Uri`/`Context`, not a serialisable payload), and read-only/reporting/infra surfaces (search,
growth rings, weather cache, notifications, backup, preferences) — these aren't command-shaped.

## Project's routes

All under `/v1/Project/local/…`, served by `ProjectConnections.buildDispatcher` and reached through
`ProjectApp.connectionDispatcher`.

There is **no service layer** between these handlers and `ProjectRepository`, and that is deliberate
rather than a shortcut. LifeOps needs one because its task lifecycle carries policy no repository
owns — week resolution, scoring, reminder scheduling. In Project every structural edit is already a
pure function in `logic/` returning the rows that changed, applied by one repository; that repository
*is* the use-case layer, and a second one would be a second place for the rules to live.

| Resource | Actions | Notes |
|---|---|---|
| `project` | `create`, `archive` | `create` takes `name` (required), `kind`, `summary`; an unknown kind reads as General, because kind is vocabulary and refusing to make a project because somebody said "novel" is pedantry. `archive` takes `project` and an optional `archived` (default true). |
| `outline` | `add`, `setStatus` | `add` takes `project`, `title`, optional `parentId`. Deliberately **no delete**: deleting an outline row takes its whole subtree, which is a confirmation dialog's job and not a sentence's. |
| `doc` | `create` | `project`, `title`, optional `outlineNodeId`. Creates the same one-empty-paragraph document the screen does. |
| `lore` | `create` | `project`, `name`, optional `category`, `summary`, `body`. A body is allowed here because the row is new — nothing existing is overwritten. |
| `timeline` | `add` | `project`, `title`, optional `when`, `era`, `detail`, `outlineNodeId`. `when` stays free text, so "the spring after the fire" is not refused. |
| `card` | `create`, `move`, `complete`, `delete` | `create` takes `project`, `title`, optional `column` (defaults to the first unfinished one), `notes`, `dueOn` (ISO date), `outlineNodeId`, `docId`. The rest take a card `id`. |

### The line these routes sit on

**They can add and organise. They cannot rewrite a word of what is already written.** There is no
route that appends to a document, replaces one from Markdown, edits a block, restores a version,
deletes an outline subtree or deletes a project. Every one of those is a way for a caller working
from a misheard sentence to destroy writing that may have no second copy anywhere. Creating an empty
document is additive and undone by deleting it; rewriting one is neither. `ProjectConnectionsTest`
asserts each of those addresses is `ROUTE_NOT_FOUND`, so the line is a test rather than an
intention.

### Naming the target

A screen hands back the id of the row somebody tapped; a route is handed a *name*, because the
caller is a sentence. `logic/ProjectLookup` resolves it: **id first**, then an exact name match that
ignores case and surrounding space — never a prefix and never a substring, because "Kes" finding
"The Kestrel" is a guess, and a guess writes into the wrong project the first time two of them start
alike.

**An ambiguous name resolves to nothing**, the same rule Lore uses for `[[double brackets]]`. Two
projects called "Draft" produce an `INVALID_PARAMS` naming both and asking for an id, rather than a
write into whichever row came back first. A caller that is told "which one?" can ask again; one that
is told nothing writes into somebody's work and never finds out.

### What a route does not touch

`card/complete` moves the card into the board's finished column and deliberately leaves its
published LifeOps task alone — the hand-off round sees a finished card and retires the task, which
is the one place that decision is made. Clearing the link here would strand the task on somebody's
week with nothing pointing at it.

## The one thing that points outward

Everything above points *inwards*: an address, a payload, a use-case invoked on LifeOps. There is
exactly one seam going the other way — `connection/TaskCompletionBus`.

It exists because a task can be **owned by another hosted app**. Maintenance publishes its upkeep
onto a future week (`Truck: Oil change`, dated the day it falls due) and has to know the moment that
line is ticked, so the service can be logged and the next occurrence scheduled. Polling would answer
that question a foreground later.

```kotlin
TaskCompletionBus.register { completion ->
    // completion.taskId / .title / .completedAtMillis
}
```

Three rules keep it from becoming a back door into the task lifecycle:

- **It announces facts, not requests.** A listener is told a task was completed. Nothing waits for
  it, reads its answer, or lets it veto anything — LifeOps behaves identically whether or not
  anybody is listening.
- **A listener cannot break a tick.** Each is called inside `runCatching`; a hosted app whose
  database is mid-restore must not turn "mark done" into a crash here. `TaskCompletionBusTest` holds
  that line.
- **It fires after the transaction commits**, and only when the tick actually happened — a task that
  was already complete announces nothing.

There is deliberately no un-completion event: un-ticking is a correction to *this* week, and what
another app wrote down in response to the tick is its record to correct. Listeners are expected to
**reconcile rather than depend on the announcement** — Maintenance's round reaches the same answer
from a foreground or an edit, so a missed announcement costs latency, never correctness. See
[MAINTENANCE.md](MAINTENANCE.md#the-lifeops-week).

## Reference implementation

`local/task` is the worked example end-to-end: `LocalTaskConnection` → `TaskService` →
`TaskRepository`, with `week`/`operation`/`counter` following the same shape. New resources should
mirror it. The routing core (`ConnectionAddress`, `ConnectionParams`, `ConnectionRegistry`,
`ConnectionDispatcher`) is pure JVM and unit-tested under
`app/src/test/java/com/lifeops/app/connection/`.
