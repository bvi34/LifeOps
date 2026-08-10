# Advisor

Advisor is the suite's **private, on-device assistant** — the fourth app hosted in the Operations
Sandbox (`:advisor`, alongside LifeOps, Citation and Logistics). It answers questions **grounded in
your own data** across the whole suite, using a small **RAG** (retrieval-augmented generation)
pipeline, and it does so under an explicit **per-app permission gate**: it can read an app only after
you turn that app on.

It reasons over three kinds of its own context on top of the retrieved app data:

- **Identity** — slow-changing, identity-based data (name, pronouns, roles, values, goals, focus, …)
  stored as a portable **JSON file** (`advisor/identity.json`), given to the model as always-on
  context. JSON, not the database, because it's worth hand-editing and carrying around.
- **Standing profiles** — a small set of **named, always-on dossiers** (`user`, `llm-persona`,
  `project-a`, …) the model references by name and can **write to**, persisted as JSON files (never a
  per-question query). This is the "reference longstanding projects without querying the database"
  layer.
- **Long-term memory** — a **dedicated, heavily-tagged database** (`advisor_memory.db`) the assistant
  recalls from. Tags are normalized and indexed for real facet/recall power.
- **Logic engine** — a seam for a future component that injects derived context/logic into the model.
  It's a no-op today; the real engine lands in a later commit without touching anything else.

The language model itself is a **placeholder** today. Everything around it — the permission gate,
the retrieval, the prompt assembly, the citations — is real and working; a small local model
(≈2–4B parameters, Q4-quantised GGUF, running fully on-device) is the intended drop-in behind one
interface.

> **Nothing leaves the device.** Advisor requests no permissions of its own — no `INTERNET`. It reads
> the other apps' databases in-process and (once wired) will run the model locally.

---

## The pipeline

A question flows through these stages, in order:

```
permissions → retrieve → recall memory → logic engine → augment (assemble prompt) → generate → apply writes
                                                 ↑            ↑                                      │
                            identity + profiles ─┴────────────┘                                      │
                                    ↑                                                                │
                                    └──────────────── @remember(<profile>) directives ──────────────┘
```

1. **Permissions gate** (`logic/AdvisorPermissions`). Which apps Advisor may read. **Denied by
   default**: an app is off until you grant it, so the model can never see data you haven't opted
   in. The gate is enforced in the repository *before any source is loaded* — a denied app's database
   is never even opened.

2. **Retrieve** (`logic/Retriever`). A dependency-free lexical retriever (TF-IDF, title terms
   weighted heavier than body) ranks the granted corpus against the question and returns the top few
   `RetrievedChunk`s. No embeddings, no network — cheap, deterministic, and JVM-testable. A real
   embedding index can replace it later behind the same `retrieve(...)` shape.

3. **Recall memory** (`logic/MemoryRecall`). Ranks the long-term memory store for the question —
   lexical overlap over content + tags, an explicit tag-focus boost, and `salience`/`pinned` as
   gentle priors. Pinned memories are always eligible; unrelated ones are left out. Memory is
   Advisor's own and is **not** permission-gated.

4. **Logic engine** (`logic/LogicEngine`). The seam. Given the question, identity, retrieved chunks
   and recalled memories, it returns derived context lines. `NoOpLogicEngine` (today) returns
   nothing; a real engine drops in here without changing any other stage.

5. **Augment** (`logic/PromptAssembler`). Turns identity + standing profiles + memory + chunks +
   derived lines into an `AdvisorPrompt` with `IDENTITY`, `PROFILES`, `MEMORY`, `CONTEXT` and
   `REASONING` sections, a system instruction that demands citations, forbids ungrounded answers, and
   documents the `@remember` write convention, and the question. `AdvisorPrompt.render()` is the flat
   text a real GGUF model would be fed.

6. **Generate** (`logic/LocalLlm`). The `LocalLlmEngine` interface. Today it's `PlaceholderLlmEngine`,
   which composes a **grounded, extractive** answer directly from the context and recalled memory
   (with `[n]` / `[Mn]` citations) rather than inventing language — so the end-to-end path is
   demonstrably correct while the weights are chosen. When nothing is granted, recalled or matched, it
   says so and points at the permission gate; "no data" is the honest answer, not a hallucinated one.

7. **Apply writes** (`logic/ProfileDirectives`, in the repository). Any `@remember(<profile>): <fact>`
   lines the model emitted are parsed and appended to the named profiles (creating one on a new key),
   then stripped from the answer shown to the user. This is how the model *adds to* the standing
   profiles, not just reads them.

All the reasoning stages are **framework-free** and live under `advisor/logic/`, unit-tested on the
JVM (`RetrieverTest`, `PromptAssemblerTest`, `AdvisorPermissionsTest`, `PlaceholderLlmEngineTest`,
`IdentityTest`, `MemoryRecallTest`, `LogicEngineTest`, `ProfileTest`, `ProfileDirectivesTest`) — the
same discipline as `:backupkit` and Citation's `:core`.

## Identity, memory and the logic engine

- **Identity** (`logic/Identity`, `data/identity/IdentityStore`). A plain data class of identity-based
  fields plus a free-form `traits` map, persisted as pretty-printed JSON at
  `filesDir/advisor/identity.json`. The store seeds the empty schema on first read (so the shape is
  self-documenting and there's always a file to edit/back up) and fails soft to `Identity.EMPTY` on a
  corrupt file. `Identity.toContextLines()` renders only the fields you filled in, and those become
  the prompt's `IDENTITY` section. The JSON round-trip is pinned by `IdentityJsonTest`.

- **Long-term memory** (`data/memory/`). A **dedicated** Room database, `advisor_memory.db`, separate
  from the operational `advisor.db` so it can grow and be queried hard on its own. Schema:
  `advisor_memories` (content, kind, `salience`, `pinned`, `archived`, and the recall-loop columns
  `lastRecalledAt`/`recallCount`) and a **normalized** `advisor_memory_tags` (one indexed row per
  tag, cascading on delete). That normalization is the "tons of tagging potential": *memories for tag
  X*, *all tags with counts*, and multi-tag recall are cheap indexed queries. `MemoryRepository`
  normalizes tags (trim/lower/dedupe) and closes the recall loop by bumping stats on surfaced
  memories.

- **Logic engine** (`logic/LogicEngine`). `LogicEngine.process(LogicInput) → LogicOutput`, wired into
  the repository between recall and assembly. `NoOpLogicEngine` is the default; swap
  `AdvisorApp.logicEngine` for the real implementation when it ships.

- **Standing profiles** (`logic/Profile`, `logic/ProfileDirectives`, `data/profile/ProfileStore`).
  Named dossiers, each a `Profile` (key, name, kind, summary, and an append-only list of
  `ProfileEntry`s) stored as `filesDir/advisor/profiles/<key>.json`. Two are seeded on first use —
  `user` and `llm-persona` — and projects are created by the user or **by the model**: to write, the
  model emits a `@remember(<key>): <fact>` line (the system prompt tells it how), which
  `ProfileDirectives.parse` extracts and the repository applies via `ProfileStore.append` (creating
  the profile if the key is new), then `ProfileDirectives.strip` removes the directive from the shown
  answer. Every profile with `alwaysInclude` is injected into the prompt's `PROFILES` section on every
  question — no retrieval, addressable by name. A future function-calling model can bypass the text
  convention and emit `ProfileAppend`s directly.

---

## Knowledge sources — reading across the suite

The corpus is assembled live at query time from the other apps' own databases, via a `KnowledgeSource`
per app (`data/source/`). Each reads its app's rows (read-only — Advisor never writes) and flattens
them into `KnowledgeDocument`s with a traceable id (`"<app>:<kind>:<rowId>"`). This mirrors how
Logistics' `LifeOpsCatalog` reads LifeOps' catalog in the same process.

| Source | Reads |
|---|---|
| **LifeOps** | tasks (title, status, priority, aspect, due/estimate/completion), aspects, projects, milestones |
| **Citation** | library books (title, author, reading state) and reading notes |
| **Logistics** | pantry stock (with low-stock flags) and the grocery list |

Because the corpus is read fresh each time and never cached in Advisor's own store, revoking an app
takes effect on the very next question — there is no stale copy to leak.

---

## What Advisor stores (and backup)

Advisor owns four things and nothing else: `advisor.db` (granted per-app permissions + saved
conversation), `advisor_memory.db` (the tagged long-term memory), `identity.json` (identity-based
data), and `profiles/*.json` (the standing named profiles). The knowledge it reasons over is **not**
duplicated — that lives in the other apps and is backed up by their contributors.
`AdvisorBackupContributor` (`AppId.ADVISOR`, data version **3**) captures all of those archive
entries, so "back up everything" stays complete by construction. Restore swaps the two database files
and rewrites the identity + profile files, so an Advisor restart is expected afterwards (the sandbox
surfaces that).

---

## The UI (`:advisor`)

`MainActivity` is a three-tab shell over the one `AdvisorApp` runtime:

- **Advisor** — the chat. Ask in plain language; each answer carries the sources retrieval cited. The
  empty state and a footer are honest that the model is a placeholder and name what's running.
- **Memory** — the long-term memory manager: add memories with free-form tags, filter by tag facet,
  set salience, and pin the ones that should always be reachable.
- **Profiles** — the standing-profiles manager: create project profiles, add entries to any profile,
  and see entries the assistant wrote back (tagged "advisor" vs "you").
- **Permissions** — the identity summary (JSON-backed), one switch per app (denied by default), the
  model card describing the intended local 2–4B model, and a "clear conversation" action.

`AdvisorApp` is the tiny runtime holder (mirroring `LifeOpsApp`/`LogisticsApp`): it owns the
database, the read-only knowledge sources, the engine, and the repository. Everything is lazy — no
other app's database is touched until a question is asked, and only for granted apps.

---

## Wiring a real model

The whole remaining job is replacing `PlaceholderLlmEngine` with an implementation of
`LocalLlmEngine.generate(prompt)` that loads a GGUF model and runs `prompt.render()` on-device
(llama.cpp / MediaPipe LLM Inference / ONNX Runtime — TBD). Nothing upstream changes: permissions,
retrieval, prompt assembly and citations are already model-agnostic. Update `AdvisorApp.engine` to
construct the real engine and the `ModelSpec` to describe the actual weights.

---

## Testing

`advisor/src/test/` runs on the JVM (via the module's unit tests, no emulator for the logic):

- `RetrieverTest` — ranking, no-overlap/stop-word empties, title-over-body, top-K, recency tie-break.
- `PromptAssemblerTest` — block numbering, excerpt trimming, and the rendered prompt's contents.
- `AdvisorPermissionsTest` — deny-by-default, immutable grant/revoke, and the filter gate.
- `PlaceholderLlmEngineTest` — grounded citations, deterministic output, and the empty-context path.
- `IdentityTest` — context-line rendering of only filled fields.
- `MemoryRecallTest` — relevance vs. exclusion, always-on pinning, tag-focus boost, salience ties, limit.
- `LogicEngineTest` — the no-op default and derived lines reaching the prompt's REASONING section.
- `ProfileTest` — append immutability, recent-entry cap, header/key rendering, context lines.
- `ProfileDirectivesTest` — `@remember` parsing, key slugging, and directive stripping.
- `IdentityJsonTest` / `ProfileJsonTest` — the identity and profile JSON round-trips.

The Android glue (both Room stores, the identity + profile file stores, knowledge sources, UI, the
module wiring into `:app`) is verified by building and running the container app.
