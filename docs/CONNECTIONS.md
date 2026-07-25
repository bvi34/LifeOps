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

## The five segments

| Segment | Example | Meaning |
|---|---|---|
| `version` | `v1` | Address-contract version. Bumped only on a breaking shape change. |
| `application` | `LifeOps` | The owning app. Reserved so a future multi-app surface can address peers unambiguously. |
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
  routes and the UI (`ThisWeekViewModel`) call it, so every path behaves identically.
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

All under the `local` connection today (`/v1/LifeOps/local/…`):

| Resource | Actions | Service | Notes |
|---|---|---|---|
| `task` | `create`, `update`, `complete`, `delete` | `TaskService` | Reference implementation. |
| `week` | `current`, `close` | `WeekService` | `close` mints the next week, snapshots the closing one, seeds recurring series. |
| `project` | `create`, `update`, `complete`, `reopen` | `ProjectService` | `complete`/`reopen` flip status. |
| `counter` | `create`, `log`, `archive`, `update` | `CounterService` | `log` ticks a counter/habit; `occurredAt` (epoch millis) backdates. |
| `note` | `add`, `delete` | `NoteService` | Task notes. |
| `aspect` | `create`, `rename`, `archive` | `AspectService` | `create` is find-or-create. |
| `category` | `create`, `archive` | `AspectService` | `create` needs `aspectId`; find-or-create. |
| `person` | `create`, `rename`, `archive`, `delete`, `addNote`, `attach`, `detach` | `PersonService` | `attach`/`detach` link a person to a task. |
| `busyBlock` | `create`, `delete` | `BusyBlockService` | `daysMask` bitmask for weekly recurrence; `specificDate` for one-off. |
| `timeEntry` | `log` | `TimeEntryService` | Logs minutes against a task. |
| `book` | `create`, `update`, `setStatus`, `delete`, `addNote`, `logTime` | `BookService` | `setStatus`: `to_read`/`reading`/`done`. |
| `food` | `createCustom`, `log`, `logAdHoc`, `confirm`, `adjust`, `promote` | `FoodService` | `unit`: `gram`/`serving`; macros are `Double`. |
| `recipe` | `create`, `delete`, `addIngredient`, `removeIngredient` | `RecipeService` | — |
| `futureProject` | `create`, `addNote`, `archive`, `delete` | `FutureProjectService` | The "someday" backlog. |
| `costResource` | `create`, `archive` | `CostService` | Budgets/quotas. |
| `cost` | `log`, `delete` | `CostService` | Per-task cost entries. |
| `activity` | `create`, `delete` | `ActivityService` | Saved outdoor activities. |
| `runbook` | `create`, `delete`, `stamp` | `RunbookService` | `stamp` writes a runbook's steps as subtasks on a task. |
| `subtask` | `check`, `delete` | `RunbookService` | — |
| `wellness` | `checkin`, `sleep` | `WellnessService` | Daytime check-in and morning sleep report. |

Missing/unknown ids return `NOT_FOUND`; the route still exists, the entity does not.

**Deliberately not routed:** task image attachments (creation is bound to an Android photo-picker
`Uri`/`Context`, not a serialisable payload), and read-only/reporting/infra surfaces (search,
growth rings, weather cache, notifications, backup, preferences) — these aren't command-shaped.

## Reference implementation

`local/task` is the worked example end-to-end: `LocalTaskConnection` → `TaskService` →
`TaskRepository`, with `week`/`project`/`counter` following the same shape. New resources should
mirror it. The routing core (`ConnectionAddress`, `ConnectionParams`, `ConnectionRegistry`,
`ConnectionDispatcher`) is pure JVM and unit-tested under
`app/src/test/java/com/lifeops/app/connection/`.
