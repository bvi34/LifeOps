# Advisor

Advisor is the suite's **private, on-device assistant** — the fourth app hosted in the Operations
Sandbox (`:advisor`, alongside LifeOps, Citation and Logistics). It answers questions **grounded in
your own data** across the whole suite, using a small **RAG** (retrieval-augmented generation)
pipeline, and it does so under an explicit **per-app permission gate**: it can read an app only after
you turn that app on.

The language model itself is a **placeholder** today. Everything around it — the permission gate,
the retrieval, the prompt assembly, the citations — is real and working; a small local model
(≈2–4B parameters, Q4-quantised GGUF, running fully on-device) is the intended drop-in behind one
interface.

> **Nothing leaves the device.** Advisor requests no permissions of its own — no `INTERNET`. It reads
> the other apps' databases in-process and (once wired) will run the model locally.

---

## The pipeline

A question flows through four stages, in order:

```
permissions → retrieve → augment (assemble prompt) → generate
```

1. **Permissions gate** (`logic/AdvisorPermissions`). Which apps Advisor may read. **Denied by
   default**: an app is off until you grant it, so the model can never see data you haven't opted
   in. The gate is enforced in the repository *before any source is loaded* — a denied app's database
   is never even opened.

2. **Retrieve** (`logic/Retriever`). A dependency-free lexical retriever (TF-IDF, title terms
   weighted heavier than body) ranks the granted corpus against the question and returns the top few
   `RetrievedChunk`s. No embeddings, no network — cheap, deterministic, and JVM-testable. A real
   embedding index can replace it later behind the same `retrieve(...)` shape.

3. **Augment** (`logic/PromptAssembler`). Turns the question + chunks into an `AdvisorPrompt`: a
   system instruction that demands citations and forbids ungrounded answers, the numbered context
   blocks, and the question. `AdvisorPrompt.render()` is the flat text a real GGUF model would be fed.

4. **Generate** (`logic/LocalLlm`). The `LocalLlmEngine` interface. Today it's `PlaceholderLlmEngine`,
   which composes a **grounded, extractive** answer directly from the retrieved context (with `[n]`
   citations) rather than inventing language — so the end-to-end path is demonstrably correct while
   the weights are chosen. When nothing is granted or nothing matches, it says so and points at the
   permission gate; "no data" is the honest answer, not a hallucinated one.

All four stages are **framework-free** and live under `advisor/logic/`, unit-tested on the JVM
(`RetrieverTest`, `PromptAssemblerTest`, `AdvisorPermissionsTest`, `PlaceholderLlmEngineTest`) — the
same discipline as `:backupkit` and Citation's `:core`.

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

Advisor's own database (`advisor.db`) holds only what Advisor owns: the granted per-app permissions
and the saved conversation. The knowledge it reasons over is **not** duplicated here. Like every
hosted app it ships an `AdvisorBackupContributor` (`AppId.ADVISOR`) that copies the whole `advisor.db`
into the sandbox archive, so "back up everything" stays complete by construction. Restore is a
whole-file swap, so an Advisor restart is expected afterwards (the sandbox surfaces that).

---

## The UI (`:advisor`)

`MainActivity` is a two-tab shell over the one `AdvisorApp` runtime:

- **Advisor** — the chat. Ask in plain language; each answer carries the sources retrieval cited. The
  empty state and a footer are honest that the model is a placeholder and name what's running.
- **Permissions** — one switch per app (denied by default), plus the model card describing the
  intended local 2–4B model and a "clear conversation" action.

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

The Android glue (Room store, knowledge sources, UI, the module wiring into `:app`) is verified by
building and running the container app.
