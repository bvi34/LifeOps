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
- **Unifying engine (C3A)** — the coordinator. It assembles the above into one working set and decides
  whether to **answer**, **ask for clarification**, or flag that **external investigation** is needed —
  under one rule: *"Not knowing is acceptable. Being wrong without asking clarification is not."*

The language model is a local **Qwen3-4B** (Q4_K_M GGUF), run fully on-device via **llama.cpp** behind
one interface (`logic/LocalLlmEngine`). Everything around it — the permission gate, the retrieval, the
prompt assembly, the citations — is real and working. Until the multi-gigabyte weights are provisioned
on the device, the engine transparently falls back to a deterministic, grounded **placeholder**, so
Advisor answers either way and gains real reasoning the moment the model file is present — no code
change.

> **Nothing leaves the device.** Advisor requests no permissions of its own — no `INTERNET`. It reads
> the other apps' databases in-process and (once wired) will run the model locally.

---

## The pipeline

A question flows through these stages, in order:

```
                    ┌── task command? ──→ create it in the owning app → confirm ───────┐
                    │── write command? ─→ apply writes (profiles + memory) → confirm ──┤
                    │── function match? ─→ run capability (count / tally / lookup) ────┤
                    │                                                                   │
permissions → load corpus → retrieve → recall → logic engine → augment → generate → apply writes
                                            ↑         ↑                                     │
                       identity + profiles ─┴─────────┘                                     │
                                    ↑                                                       │
                                    └──── @remember(<profile>) / @memorize directives ──────┘
```

0. **Task commands** (`logic/TaskIntent`, `logic/TaskWriter`, in the repository, *first*). "Add a task
   to Life Ops called X" is an instruction to change **another app**, and the one thing that must never
   be improvised: the model cannot write to LifeOps, so left to itself it simply *says* it added the
   task (and, being told it may write to memory, leaves a `@memorize` note behind as the only trace).
   `TaskIntent` parses the command — the act (add/create/make/…), the noun (task/to-do), the title
   ("titled X", `"X"`, `task: X`, or the words trailing the noun), the target the user named ("to life
   ops"), and any stated priority — and the repository performs it through `TaskWriter`, then confirms
   **from the returned result**: created (and where), skipped as a same-week duplicate, or not written
   and why. It is as conservative as `WriteIntent`: a question about tasks ("what tasks did I add?") is
   never a command, though a polite request ("can you add a task…?") is one; and a command with no
   title asks for the title rather than inventing one. See *Writing back* below for the write side.

0a. **Write commands** (`logic/WriteIntent`, in the repository, *before* the gate). An explicit
   instruction to persist — "remember that …", "add to LLM persona that …", "save … to memory" — is a
   command, not a question, so it's performed directly and confirmed, bypassing retrieval and the C3A
   gate that would otherwise ask for clarification about a fact it has no grounding for. This is what
   makes the write capability real *today*, on the deterministic placeholder, without waiting on the
   model to emit a directive. `WriteIntent` is deliberately conservative — it ignores anything phrased
   as a question — so ordinary recall ("do you remember what I said?") falls through untouched.

0b. **Small talk** (`logic/SmallTalk`, in the repository, right after write commands). A purely
   conversational or meta turn — "hi", "thanks", "bye", "ok", "what can you do?", "who are you?" — is
   not a data question, so it would otherwise dead-end in the C3A gate's *"I don't have anything…"* or
   the placeholder's *"I couldn't find anything…"*. Instead `SmallTalk` recognises greetings, thanks,
   farewells, acknowledgements and capability/identity questions and replies warmly and immediately,
   **personalised from what the turn already holds** — the user's name (from identity), the assistant's
   own name (from a persona profile), and which apps are enabled (the capability reply lists them, with
   examples, and points at Permissions when none are). Like `WriteIntent` it's the deterministic social
   counterpart that works *today* on the placeholder, and it's deliberately conservative: it fires only
   when the **whole** message is social/meta (every word a social word or harmless filler, or a known
   meta phrase), so a grounded question — even one that opens with "hi, …" — carries real content and
   falls straight through to the pipeline. Pure and JVM-tested (`SmallTalkTest`).

0c. **Function dispatch** (`logic/FunctionRouter`, `logic/AdvisorFunction`, in the repository, after the
   corpus is loaded but before retrieval). Some questions are **computations**, not lookups — "how many
   times have I said X", "count my word usage in my tasks" — and extractive RAG can only *surface* rows,
   so it answers them badly (it matched "count" to the word "Count" in pantry labels). The router hands
   the question to the first registered `AdvisorFunction` that handles it; the function *computes* the
   answer over the user's own (permission-filtered) data and returns it, skipping the RAG path. New
   capabilities plug in by being added to the router — this is the "distribute various functions" seam.
   Ships with four, most-specific first:
   - **`CalculatorFunction`** — arithmetic ("what is 15% of 200", "12.5 * 3 + 2", "3 to the power of 2"),
     evaluated by a small recursive-descent parser (no `eval`). A trigger guard keeps date/id-like text
     ("what did I do on 2026-08-10") from being read as subtraction.
   - **`WordUsageFunction`** — exact whole-word counts ("how many times have I said X") and most-used-word
     summaries, scoped to the conversation, tasks, notes, memory, ….
   - **`InventoryFunction`** — the Logistics pantry/grocery: what's running low, a specific item's stock,
     a category-broken-down pantry summary, and the still-needed grocery list.
   - **`AggregateFunction`** — counts and numeric roll-ups over what you collect: "how many tasks / books
     / memories", "how many tasks are done", "how much time do my tasks take" (sum/avg/min/max of task
     estimate minutes), "how many points have I earned" (milestone points).

   The structured numbers behind inventory and aggregate come from `logic/DocumentFacts`, which recovers
   the typed fields (stock, status, estimate minutes, points, …) from the knowledge sources' generated
   prose in one tested place. App-backed scopes honour the permission gate — a disabled app is asked to
   be enabled rather than counted as zero.

1. **Permissions gate** (`logic/AdvisorPermissions`). Which apps Advisor may read. **Denied by
   default**: an app is off until you grant it, so the model can never see data you haven't opted
   in. The gate is enforced in the repository *before any source is loaded* — a denied app's database
   is never even opened.

1a. **Conversation context** (`logic/Conversation`, loaded in the repository). The recent chat turns
   (the last handful of messages, oldest-first) are loaded per question and threaded through the rest
   of the pipeline, so Advisor reasons over the *conversation* rather than only the latest message. A
   terse follow-up ("who's its author?", "what about the second one?") folds the prior turns into the
   retrieval/recall query so its subject is found, the C3A gate treats an in-progress chat as somewhere
   for a reference to bind (so it stops re-clarifying what was just said), and the turns are rendered
   into the prompt's `CONVERSATION` section for the model to resolve against.

2. **Retrieve** (`logic/HybridRetriever`). Ranks the granted corpus against the question (with recent
   conversation folded in) and returns the top few `RetrievedChunk`s, one of two ways behind a single
   shape:
   - **Lexical** (`logic/Retriever`) — the default and fallback: a dependency-free TF-IDF retriever
     (title terms weighted heavier than body). No embeddings, no network — cheap, deterministic, and
     JVM-testable.
   - **Semantic** (`logic/EmbeddingRetriever`) — active once an embedding model is imported: it ranks
     by *meaning* (cosine over on-device sentence embeddings), so a question finds relevant records
     even when they share no literal words. This is what lets "Who am I?" reach a `Name:` fact without
     the engine hand-coding that bridge. See [Semantic retrieval](#semantic-retrieval).

   `HybridRetriever` picks semantic when the embedder is ready and falls back to lexical otherwise —
   the same "better path activates when its model file is present" pattern as generation. The corpus
   is passed in per question (already permission-filtered), so revocation is honoured on the next call.

3. **Recall memory** (`logic/MemoryRecall`). Ranks the long-term memory store for the question —
   lexical overlap over content + tags, an explicit tag-focus boost, and `salience`/`pinned` as
   gentle priors. Pinned memories are always eligible; unrelated ones are left out. Memory is
   Advisor's own and is **not** permission-gated.

4. **Unifying engine — C3A** (`logic/C3AEngine`). The coordinator. Given the question, identity,
   profiles, retrieved chunks, recalled memories, and which apps are granted/denied, it runs a
   reasoning workflow and returns a **decision**: `ANSWER` (with derived context), `CLARIFY` (ask the
   user), or `INVESTIGATE` (a needed source isn't enabled). **If the decision isn't `ANSWER`, the
   model is never invoked** — the assistant asks instead of resolving the uncertainty internally. See
   [The C3A engine](#the-c3a-engine).

5. **Augment** (`logic/PromptAssembler`). Turns identity + standing profiles + memory + chunks +
   derived lines into an `AdvisorPrompt` with `IDENTITY`, `PROFILES`, `MEMORY`, `CONTEXT` and
   `REASONING` sections, a system instruction that demands citations, forbids ungrounded answers, and
   documents the `@remember` (profile) and `@memorize` (memory) write conventions, and the question. `AdvisorPrompt.render()` is the flat
   text a real GGUF model would be fed.

6. **Generate** (`logic/LocalLlm`, `logic/Qwen3LlmEngine`). The `LocalLlmEngine` interface. The default
   is `Qwen3LlmEngine`: it formats the assembled prompt with Qwen3's ChatML template
   (`logic/Qwen3ChatFormat`, thinking disabled for a grounded assistant), runs it through an
   `LlmBackend` (the native llama.cpp seam), and cleans the completion back into an answer. If the
   backend isn't ready — the weights aren't on the device — or a generation is empty/throws, it falls
   back to `PlaceholderLlmEngine`, which composes a **grounded, extractive** answer directly from the
   context and recalled memory (with `[n]` / `[Mn]` citations) rather than inventing language. When
   nothing is granted, recalled or matched, it says so and points at the permission gate; "no data" is
   the honest answer, not a hallucinated one. `ModelSpec` reports which of the two actually answered, so
   the UI stays honest.

7. **Apply writes** (`logic/ProfileDirectives`, `logic/MemoryDirectives`, in the repository). Any
   `@remember(<profile>): <fact>` lines the model emitted are parsed and appended to the named profiles
   (creating one on a new key), and any `@memorize: <fact> #tags` lines are saved to long-term memory
   (tags become recall tags); both are then stripped from the answer shown to the user. This is how the
   model *adds to* the standing profiles **and its own memory**, not just reads them. Assistant-written
   memories carry `source = "advisor"`, mirroring the `advisor`-authored profile entries.

All the reasoning stages are **framework-free** and live under `advisor/logic/`, unit-tested on the
JVM (`RetrieverTest`, `PromptAssemblerTest`, `AdvisorPermissionsTest`, `PlaceholderLlmEngineTest`,
`Qwen3ChatFormatTest`, `Qwen3LlmEngineTest`, `IdentityTest`, `MemoryRecallTest`, `LogicEngineTest`,
`ProfileTest`, `ProfileDirectivesTest`, `MemoryDirectivesTest`, `WriteIntentTest`, `SmallTalkTest`, `ConversationTest`,
`WordUsageFunctionTest`, `CalculatorFunctionTest`, `InventoryFunctionTest`, `AggregateFunctionTest`,
`DocumentFactsTest`, `FunctionRouterTest`, `C3AEngineTest`) — the same discipline as `:backupkit` and
Citation's `:core`. Only the native `LlmBackend` (`llm/LlamaCppBackend`) touches Android/JNI.

## The C3A engine

C3A is the **unifying engine** (`logic/C3AEngine`, implementing `LogicEngine`). It exists to enforce
one rule:

> **"Not knowing is acceptable. Being wrong without asking clarification is not."**

So instead of always answering, it runs a deterministic workflow over the assembled context and
returns an `EngineDecision`:

1. **Contradiction detection.** It scans recalled memory and profile entries for conflicting facts
   (high token overlap, opposite polarity — e.g. "prefers oat milk" vs. "does *not* prefer oat milk").
   A conflict always yields `CLARIFY` — it asks which is correct rather than picking one. This check
   fires even right after a previous clarification, because answering from conflicting data is exactly
   the "being wrong" the rule forbids.
2. **Ambiguity.** An unresolved reference ("it", "that", "the project") with nothing to bind it to
   yields `CLARIFY` — it asks what you mean rather than guessing the subject. *Once the chat is under
   way, the recent conversation is somewhere to bind*, so a follow-up proceeds instead of re-asking
   (the same concession as `justAsked`), and prior turns count as grounding evidence.
3. **Grounding / uncertainty.** It measures how many of the question's content terms appear anywhere
   in identity, profiles, memory, or granted app data. Zero overlap and it won't let the model
   improvise: if the topic maps to a **denied app** it returns `INVESTIGATE` (asking you to enable
   that source), otherwise `CLARIFY`.
4. **Answer.** Only with real grounding does it return `ANSWER`, attaching a short note of what it's
   grounded in (which flows into the prompt's `REASONING` section).

The repository honors the decision: on `CLARIFY`/`INVESTIGATE` **the model is not invoked at all** —
the assistant's turn is the question, stored as a `clarification`-kind message (styled distinctly in
chat). To avoid nagging, `LogicInput.justAsked` (set when the previous assistant turn was a
clarification) lets thin-grounding/ambiguity cases proceed on the next turn — it asked once, so it
answers now. Contradictions are the deliberate exception and still stop it.

The reasoning is heuristic and pure today (a stand-in for what a real model could do more richly), but
the **policy is the real contract** and doesn't change when the model does — a wired-in model still
answers only when C3A says `ANSWER`.

## Identity, memory and profiles

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
  memories. Memory is **written** two ways beyond the Memory tab: the model can emit a
  `@memorize: <fact> #tags` directive (`logic/MemoryDirectives`), and a user command ("remember
  that …") is captured by `logic/WriteIntent` and saved directly — the memory counterpart to the
  `@remember(<profile>)` profile-write convention.

- **Unifying engine** (`logic/LogicEngine`, `logic/C3AEngine`). `LogicEngine.process(LogicInput) →
  LogicOutput`, wired into the repository between recall and assembly — see
  [The C3A engine](#the-c3a-engine). `C3AEngine` is the default (`AdvisorApp.logicEngine`);
  `NoOpLogicEngine` (always answer, contribute nothing) remains for callers that want the old
  pass-through behavior.

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
per app (`data/source/`). Each reads its app's rows (read-only — the sources never write; the single
write path is *Writing back*, below) and flattens
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

## Writing back — the one thing Advisor changes

Advisor writes into exactly one place outside its own stores: a **task the user explicitly asked it to
create**. `logic/TaskWriter` is the seam (framework-free, so the pipeline stays JVM-testable) and
`data/action/LifeOpsTaskWriter` implements it for LifeOps.

It does not touch LifeOps' tables. It resolves the target the user named — against active projects
first, then aspects, then categories, matching on the name ignoring case, spacing and punctuation, and
resolving an ambiguous partial match to *nothing* rather than guessing — and then calls LifeOps' own
`/v1/LifeOps/local/task/create` connection route (see `docs/CONNECTIONS.md`). So the task is created by
the same code path LifeOps' own UI uses, with the same policy: current-week resolution, same-week
duplicate-title protection, resource-value scoring, queued-vs-pending placement and reminder
scheduling. A name that matched nothing is reported in the reply, not silently dropped — and when the
name looks like it was part of the title all along ("… to the website"), the title the user typed is
kept instead.

The permission gate covers writing exactly as it covers reading: an app the user has not granted is not
written to, and the reply says so. The Permissions screen states this on the LifeOps card.

The model is told, in the system prompt, that it cannot create, change or delete anything in the other
apps and must never claim it did — a task command is carried out before the model is ever asked, so a
creation request that reaches it is one that wasn't understood, and the honest reply is to ask for it
plainly.

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

- **Advisor** — the chat. Ask in plain language; each answer carries the sources retrieval cited, and
  when C3A asks instead of answering, that turn is styled as a "Needs your input" clarification. A
  **clear-conversation** action in the top bar wipes the chat history (with a confirm dialog; memory,
  profiles and identity are untouched). The empty state and a footer are honest that the model is a
  placeholder and name what's running.
- **Memory** — the long-term memory manager: add memories with free-form tags, filter by tag facet,
  set salience, and pin the ones that should always be reachable.
- **Profiles** — the standing-profiles manager: create project profiles, add entries to any profile,
  and see entries the assistant wrote back (tagged "advisor" vs "you").
- **Permissions** — the identity summary (JSON-backed), one switch per app (denied by default), the
  model cards, and a "clear conversation" action. The model card names the running model (Qwen3-4B, or
  the placeholder while the weights aren't on the device) and lets you **import a Qwen3-4B GGUF** from
  device storage — a progress-reported, on-device copy with no network — or remove it. A second,
  optional **semantic-retrieval** card imports a small embedding GGUF the same way; with it installed,
  retrieval ranks your data by meaning instead of by keyword.

`AdvisorApp` is the tiny runtime holder (mirroring `LifeOpsApp`/`LogisticsApp`): it owns the
database, the read-only knowledge sources, the engine, and the repository. Everything is lazy — no
other app's database is touched until a question is asked, and only for granted apps.

---

## The model — Qwen3-4B on-device

`AdvisorApp.engine` is `Qwen3LlmEngine(LlamaCppBackend(app))`. Three small pieces make it work, and the
first two are pure and JVM-tested:

- **`logic/Qwen3ChatFormat`** — Qwen3's ChatML prompt format and the inverse clean-up of its output.
  It wraps the assembler's `system` instruction as the system turn and the rest of `prompt.render()` as
  the user turn, and disables Qwen3's "thinking" mode (Advisor cites its sources; it doesn't need a
  visible chain-of-thought) by pre-seeding an empty `<think></think>` block — the same trick the
  official template uses for `enable_thinking=false`. `cleanOutput` strips any think block and ChatML
  control tokens back off the completion.
- **`logic/Qwen3LlmEngine`** — implements `LocalLlmEngine`. Formats the prompt, runs it through the
  backend, cleans the result. If the backend isn't ready, or a generation is blank or throws, it falls
  back to `PlaceholderLlmEngine` so the pipeline always yields a grounded, cited answer. `spec` reports
  the placeholder vs. the real Qwen3 weights so the UI's model card is truthful.
- **`llm/LlamaCppBackend`** — the native seam (`LlmBackend`). It loads whatever
  **`llm/AdvisorModelStore`** reports as the installed `qwen3-4b*.gguf` (internal `files/models`, or an
  `adb push`ed copy under external files), once, through the `advisor-llm` native library over JNI, and
  generates on-device. Every native call is guarded: no library or no file ⇒ `isReady = false` ⇒ the
  placeholder answers. The store is re-checked until a model loads, so a freshly imported file is picked
  up on the next question — no restart, no code change.
- **`llm/AdvisorModelStore`** — provisions the weights **in-app**: it imports a GGUF the user picked
  from device storage (Storage Access Framework) into `files/models`, streaming the copy with progress,
  and reports / removes the installed file. The import is a *copy in* from a chosen document, so Advisor
  still requests no `INTERNET` and nothing leaves the device.

The native library is built from `advisor/src/main/cpp/` (`advisor_llm.cpp` + `CMakeLists.txt`, which
fetches a pinned llama.cpp). It is **opt-in**: a plain build ships no `.so` and uses the placeholder,
so no NDK is needed for day-to-day work. Compile the real backend with
`-Padvisor.buildNativeLlm=true` (needs the NDK + CMake; arm64-v8a only). See
[`advisor/src/main/cpp/README.md`](../advisor/src/main/cpp/README.md) for building and for pushing the
weights onto a device.

Nothing upstream changes: permissions, retrieval, prompt assembly, citations, and the C3A decision gate
are already model-agnostic — the wired model still only runs when C3A returns `ANSWER`, and generation
runs off the UI thread (`Dispatchers.Default`) since a real 4B model takes seconds.

### Fitting the prompt to the context window

Every token of prompt is a token to prefill, at the few tens of tokens a second a 4B model manages on
a phone — so prompt size is latency, and what is *reusable* between turns is latency saved.

- **`PromptBudget`** sizes the prompt against the model's real context window (`LlmBackend.contextTokens`,
  read from the loaded model rather than assumed) minus the reply and a safety margin. Its estimates
  run deliberately high: the job is keeping a prompt under a hard limit. What overflows is history,
  dropped here on purpose — the native backend's own overflow handling truncates from the *head*,
  which is where the system instruction lives, so a long conversation would otherwise cost the model
  its grounding contract exactly when it needed it.
- **`ConversationWindow`** decides which prior turns go in. Not "the last N": the KV cache keeps
  whatever a prompt shares with the previous one, and the history is exactly the shareable part, so a
  sliding window would move every message every turn and re-prefill the lot. The window is anchored to
  the conversation's length instead — its start moves only in strides and grows between them, so the
  cost is paid once every few turns rather than continuously. It is a pure function of that length, so
  nothing has to be remembered between questions for two prompts to agree.
- **`PromptAssembler.CONTEXT_BUDGET`** shares one character budget across the retrieved rows rather
  than giving each the per-row cap. CONTEXT is the volatile half of the prompt — it changes with every
  question, so none of it is ever reused — and its cost otherwise scaled with however many rows
  retrieval happened to return.

### The answer arrives as it is written

A 4B model on a phone produces a few tokens a second, so a reply is tens of seconds of work. It is
streamed rather than waited for: `LlmBackend` and `LocalLlmEngine` each have a streaming overload,
`AdvisorRepository.ask` takes an optional `onPartial`, and the chat shows the reply forming.

Two decisions are load-bearing:

- **Each update is the whole answer so far, not the newest piece.** Cleaning can *retract* text — a
  control token turns out to be one, a reasoning block closes and is dropped — so a caller appending
  deltas would have to undo them. Handed the current state instead, the UI just assigns it.
- **Nothing is shown before it is settled.** The native side holds back a tail that could still become
  a stop sequence, and any bytes that don't yet complete a UTF-8 character (`advisor_llm.cpp`);
  `Qwen3ChatFormat.cleanPartial` holds back a marker that has begun but not finished (`<|im_`) and an
  unclosed `<think>` block; `AnswerText.inProgress` hides a directive still being typed. The rule
  throughout is that text may be added or retracted as a whole, but never shown wrong and corrected.

A turn is only written to the database once its answer exists, so the question being answered is
carried in the ViewModel (`pendingQuestion`) and shown until the stored turn replaces it — otherwise
the question would appear *after* its own answer.

---

## Semantic retrieval

Lexical retrieval matches on shared surface words, which forces the engine to hand-code bridges for
every vocabulary gap — "Who am I?" shares no literal terms with a `Name: …` fact, so C3A carries an
explicit identity carve-out, a per-app keyword map, and greeting/deixis lists just to compensate.
Semantic retrieval closes those gaps at the source: it ranks by meaning, so the relevant record
surfaces on its own and the special cases stop multiplying.

It is **optional** and slots in behind the same `retrieve(...)` shape:

- **`logic/Embedder`** — the seam an embedding model runs behind (the vector analogue of `LlmBackend`).
  Guarded: when no embedding model is provisioned, `isReady` is false and `HybridRetriever` uses the
  lexical retriever. Nothing requires an embedder to be present.
- **`logic/EmbeddingRetriever`** — embeds the query and each document and ranks by cosine
  (`logic/EmbeddingMath`), dropping anything below a similarity floor so an unrelated question still
  returns nothing (the honest "no match" C3A turns into a clarification). Document vectors are memoized
  in a **`logic/VectorCache`** keyed by `(documentId, contentHash, embedderId)`: the corpus is embedded
  once per process, an edited row re-embeds (its hash changes), and swapping the model invalidates
  everything (the id changes).
- **`llm/LlamaCppEmbedder` + `llm/EmbeddingModelStore`** — the native seam and in-app provisioning for a
  **small, dedicated** sentence-embedding GGUF (tens to a couple hundred MB), separate from the 4B so
  embeddings stay fast and good. It's imported exactly like the generation model (copied in from a
  document the user picked; no `INTERNET`, nothing leaves the device) and loaded through the same
  `advisor-llm` native library via a new `nativeEmbed` entry point.

The cache is **in-memory only, by design**: Advisor keeps no copy of the other apps' data at rest, so
there's nothing new to back up and a revoked app leaks nothing. The only cost is re-embedding once per
process after a cold start; a persistent cache can drop in behind `VectorCache` later if that ever
matters. Because the corpus handed to the retriever is already permission-filtered, a revoked app's
vectors are simply never consulted — revocation stays instant.

> The native embedding path is written against the pinned llama.cpp (`b4000`) but, like the weights
> themselves, is provisioned separately and **not yet compiled/verified on-device** in this repo. The
> Kotlin side is fully guarded, so until an embedding model is present (or if the native call fails)
> retrieval is lexical — exactly as before.

---

## Testing

`advisor/src/test/` runs on the JVM (via the module's unit tests, no emulator for the logic):

- `RetrieverTest` — ranking, no-overlap/stop-word empties, title-over-body, top-K, recency tie-break.
- `EmbeddingMathTest` — cosine (identical/orthogonal/opposite/scale-invariant), safe zero/mismatched
  vectors, unit-length normalization.
- `VectorCacheTest` — store/miss, content-hash sensitivity to title/body, LRU eviction past capacity.
- `EmbeddingRetrieverTest` — semantic match with no shared words (vs. lexical miss), similarity-floor
  empties, top-K + recency tie-break, embed-once-then-cache, re-embed on edit, invalidate on model swap.
- `HybridRetrieverTest` — lexical fallback with no embedder, semantic path when ready, per-call corpus
  (revocation) behaviour.
- `EmbeddingModelStoreTest` — the embedding-GGUF filename contract (canonical + common model names,
  reject the generation model / non-GGUF), case-insensitivity.
- `PromptAssemblerTest` — block numbering, excerpt trimming, and the rendered prompt's contents.
- `AdvisorPermissionsTest` — deny-by-default, immutable grant/revoke, and the filter gate.
- `PlaceholderLlmEngineTest` — grounded citations, deterministic output, and the empty-context path.
- `Qwen3ChatFormatTest` — ChatML turn order, thinking disabled by the empty-block seed, system kept out
  of the user turn, and control-token/think-block clean-up of completions.
- `Qwen3LlmEngineTest` — a ready backend answers and advertises Qwen3, and the unready / blank /
  throwing paths all fall back to the grounded placeholder.
- `AdvisorModelStoreTest` — the `qwen3-4b*.gguf` filename contract the backend keys on, and the
  byte-size formatting shown on the model card.
- `IdentityTest` — context-line rendering of only filled fields.
- `MemoryRecallTest` — relevance vs. exclusion, always-on pinning, tag-focus boost, salience ties, limit.
- `LogicEngineTest` — the no-op default and derived lines reaching the prompt's REASONING section.
- `C3AEngineTest` — answer-when-grounded, clarify-when-empty, contradiction → clarify, ambiguity →
  clarify, denied-app → investigate, no-double-asking, greeting handling, and *ambiguity resolved once
  the conversation gives context*.
- `ConversationTest` — the recent-turns retrieval query (folded-in subject, last-turns-only cap) and
  the "chat is under way" signal.
- `WordUsageFunctionTest` — specific whole-word counts (subject, word boundaries, zero matches), the
  most-used-words summary, scope parsing / citations, and the disabled-app gate.
- `CalculatorFunctionTest` — precedence, percent/power/word operators, integer formatting, division by
  zero, and the non-math / date-hijack guard.
- `InventoryFunctionTest` — low stock, item lookup, on-the-list fallback, category summary, needed-only
  groceries, and the Logistics permission gate.
- `AggregateFunctionTest` — counts (with a status filter), task-time sum/average, milestone-point total,
  cross-app counts (books, memories), and the LifeOps gate.
- `DocumentFactsTest` — recovering stock/unit/category/low, grocery quantity/needed, task status/
  priority/estimate, and milestone points from the sources' prose.
- `FunctionRouterTest` — routing each question to the right capability, falling through for others, and
  first-match-wins ordering.
- `ProfileTest` — append immutability, recent-entry cap, header/key rendering, context lines.
- `ProfileDirectivesTest` — `@remember` parsing, key slugging, and directive stripping.
- `MemoryDirectivesTest` — `@memorize` parsing, `#tag` extraction (lower-cased/de-duped), and stripping.
- `TaskIntentTest` — "add a task to life ops titled X" → a task command with title, target and
  priority; week scope trimmed off the title; questions about tasks, and the profile/memory write
  commands, left to their own paths; a nameless command asking for the title.
- `WriteIntentTest` — command detection: "add to <profile> …" → profile write, "remember that … #tag"
  → memory write, question phrasings and ordinary sentences left as normal Q&A.
- `IdentityJsonTest` / `ProfileJsonTest` — the identity and profile JSON round-trips.

The Android glue (both Room stores, the identity + profile file stores, knowledge sources, UI, the
module wiring into `:app`) is verified by building and running the container app.
