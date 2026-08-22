# Advisor native model backend (`libadvisor-llm.so`)

This is the native side of `com.advisor.app.llm.LlamaCppBackend`: a thin JNI bridge over
[llama.cpp](https://github.com/ggerganov/llama.cpp) that runs **Qwen3-4B** (GGUF) fully on-device.

- `advisor_llm.cpp` — JNI implementations of `nativeLoad` / `nativeGenerate` / `nativeFree`.
- `CMakeLists.txt` — fetches a pinned llama.cpp (`LLAMA_CPP_TAG`) and links `advisor-llm` against it.

## Building

The native build is **opt-in** so a normal build of the suite needs no NDK. Enable it with the Gradle
property (in `gradle.properties`, or on the command line):

```
./gradlew :app:assembleDebug -Padvisor.buildNativeLlm=true
```

Requirements: Android **NDK** and **CMake** (installed via the SDK Manager or Android Studio's SDK
Tools), and network access on the first configure so CMake's `FetchContent` can clone llama.cpp.
Only the `arm64-v8a` ABI is built — a 4B Q4 model isn't realistic on 32-bit or on most emulators.

### CPU instruction set (`advisor.cpuArch`) — read this before blaming the model

ggml only auto-detects CPU features when it builds *for the host*. An Android build is a cross-compile,
so ggml turns that off and — unless told otherwise — passes **no `-march` at all**, leaving the NDK's
plain `armv8-a` default. That silently drops the `dotprod` Q4_K kernels and the `i8mm` matmul that
prompt prefill is made of, and costs several times the speed: enough that a ~500-token prompt looks
like the app has hung.

The build therefore pins the ISA baseline itself, defaulting to `armv8.2-a+dotprod+i8mm+fp16`
(ARMv8.6-class cores — Snapdragon 8 Gen 1 / 8+ Gen 1 and newer). Override it for older arm64 hardware,
where an unsupported extension is a **SIGILL at the first matmul**, not a graceful fallback:

```
./gradlew :app:assembleDebug -Padvisor.buildNativeLlm=true -Padvisor.cpuArch=armv8.2-a+dotprod+fp16
```

Confirm what actually got compiled in — don't assume. `nativeLoad` logs llama.cpp's own system-info
line at startup:

```
adb logcat -s advisor-llm | grep 'ggml build'
```

`dotprod = 1` and `matmul_int8 = 1` mean the fast kernels are in. Zeros mean the build fell back to the
baseline and prefill will crawl.

### CPU affinity — which cores the workers run on

ggml synchronizes its worker threads at a barrier after **every graph node**, so the slowest worker
sets the pace of every matmul. On a big.LITTLE phone that makes core placement a first-order
performance decision: one worker scheduled onto a Cortex-A510 holds the other three at the barrier for
the whole forward pass. Asking for `n_threads` says how many workers to run, not *where*, so left
alone the kernel is free to spread them across both clusters.

`nativeLoad` therefore reads each core's ceiling from
`/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq`, keeps everything within 80% of the top clock,
and pins to that set — on a Snapdragon 8+ Gen 1 that is the three A710s plus the X2, and the four
A510s are excluded. The thread count comes from the same set, replacing a `hardware_concurrency() / 2`
that only happened to be right on an 8-core 4+4 phone. The pin is applied again around
`nativeGenerate`, because ggml may create its workers on the first decode rather than at context
creation, and they inherit the mask of whichever thread gets there first. It is scoped: the shared
Kotlin dispatcher thread that made the call gets its original mask back, while the ggml workers keep
theirs.

Two topologies deliberately get **no** pinning — a homogeneous CPU (no little cluster to stay off, so
pinning only stops the scheduler moving work off a hot core) and one whose cpufreq nodes can't be read
(nothing to go on). Both fall back to the previous thread count, so no device gets worse. What actually
happened is in the log:

```
inference cpus: 4,5,6,7 (4 of 8 online) — pinned to the fastest cluster
```

### KV cache: flash attention and Q8_0

`nativeLoad` asks for flash attention with a **Q8_0** K and V cache. At `n_ctx = 2048` Qwen3-4B's cache
is `2 x 36 layers x 8 KV heads x 128 dims x 2 bytes` = 144 KiB/token = **288 MiB** at f16, and roughly
half that at Q8_0. That memory is anonymous and non-evictable, so halving it hands ~140 MiB back to the
question that actually decides inference speed on a phone — whether the 2.3 GiB of weights stay
resident. Q8_0 is near-lossless for a cache sitting beside Q4_K_M weights.

Flash attention had been switched off while chasing a prefill stall whose real causes (unoptimized ggml
and chunked prefill) are both fixed; it is also the precondition for quantizing the V cache, which
llama.cpp refuses without it. Since FA can be unavailable on a given backend build — the Vulkan path
especially — a failed context creation retries with `AUTO` + f16 rather than leaving the model
unloadable. The load line reports which one you got:

```
Loaded Qwen3-4B GGUF; ctx=2048 ... flash_attn=enabled kv=q8_0
```

### Weight loading (`advisor.mmap`)

By default the GGUF is **read into anonymous memory**, not mmap'd. mmap'd weights are file-backed, so
they are the first thing the kernel drops under memory pressure — and once dropped, every forward pass
re-reads them from flash. That cost is invisible to compiler flags and CPU features, so it can look
exactly like slow kernels while being pure I/O. Anonymous memory goes to zram (compressed in RAM)
instead of being evicted. The trade is a slower first load and a hard RAM commit: a device that truly
cannot spare it gets the app LMK-killed rather than silently crawling.

To compare the two, rebuild with `-Padvisor.mmap=true`.

### Prompt cache reuse

`nativeGenerate` keeps the KV entries the new prompt shares with the previous one and prefills only
the remainder, reporting both counts:

```
nativeGenerate: prompt=520 tokens (320 reused from cache, 200 to prefill); one batch (n_ubatch=512)…
```

Correctness rests on one rule — everything from the first *differing* token onward is dropped, which
includes the previous reply — so if you change how the prompt is assembled, nothing here needs
updating: the match is on tokens, not on assumed structure. The one thing to preserve is that a batch
entry's `pos` indexes the **whole prompt** (`n_reused + i`), not the batch.

How much this saves is decided on the Kotlin side, not here. The prompt is
`system + history + this question`, so the history is the reusable part — and a "last N messages"
window would move every message in it on every turn, leaving only the ~450-token system preamble to
reuse. `ConversationWindow` therefore anchors the window: its start moves only in strides, and grows
between them, so most turns reuse the whole history and the full cost is paid once every few turns.
Reordering the assembler to put more stable text first increases the reuse the same way.

### Streaming the answer out

`nativeGenerate` takes an optional listener and calls its `onToken([B)V` for each piece of the answer
as it is produced, while still returning the whole text. Two things are deliberately *not* streamed the
moment they are generated:

- the last `longest_stop` bytes, which could still turn out to be the start of a stop sequence — text
  is held back rather than shown and retracted;
- a trailing incomplete UTF-8 sequence. A token boundary is not a character boundary (one emoji is
  routinely split across two tokens), so `complete_utf8_prefix` emits whole characters only.

The pieces are **bytes**, not a `jstring`, because `NewStringUTF` expects *modified* UTF-8 and the
four-byte sequences emoji are made of are not that; decoding happens on the Kotlin side. The callback
is resolved by name through JNI, so `advisor/consumer-rules.pro` keeps it from being renamed by a
shrinker — its signature there and the one looked up here have to agree.

If the listener throws, streaming stops and generation continues: a broken listener must not cost the
answer.

### Reading the speed logs

`nativeGenerate` prints its own prefill/streaming timings and then llama.cpp's
`llama_perf_context_print`, which separates **prompt eval** (prefill) from **eval** (token generation)
in ms/token. Check that split before changing code: a slow prefill and a slow decode have different
causes.

It also prints a resource line per phase, which is what tells you *why* a phase was slow — wall clock
alone can't, and guessing has cost this backend several rebuild cycles:

```
prefill: 186766 ms wall, 41000 ms cpu (0.2x busy), majflt +540112, minflt +9021, rss 900 MiB, MemAvailable 380 MiB
```

- **`Nx busy`** is CPU-time over wall-time. With `n_threads` workers actually computing it approaches
  `n_threads` (4 here). Near or below 1x means the threads are blocked, not working — so faster kernels
  cannot help.
- **`majflt`** counts 4 KiB pages fetched from flash. The weights are ~2.3 GiB ≈ 580k pages; a delta
  near that for a *single* forward pass means the model is being re-read from storage each pass, and
  the fix is to shrink the working set (smaller model or quant), not to tune the compute.
- **`rss` vs `MemAvailable`** at load says up front whether the device can hold the model at all.

`nativeGenerate` also prints the scheduling context of the thread it runs on:

```
nativeGenerate thread: tid=26858 nice=0 policy=0 affinity=[0,1,2,3,4,5,6,7] online_cpus=8 cpuset=4:cpuset:/
```

On Android a thread's cpuset follows its priority, and a background-classified thread can be pinned to
the little cores with a small share of them — throttling inference by a large factor while the compiled
kernels and the memory system are both blameless. A `cpuset` of `/background` or a `nice` of 10 is that
fault, and the fix for it is on the Kotlin side (which dispatcher/priority the call runs on), not in
this file. The `affinity` list, by contrast, is now chosen here — see below. A whole generate call is also capped by a 180 s watchdog (`GENERATE_DEADLINE_MS`) wired to
ggml's abort callback, so a pathological run fails with a logged message and falls back to the
placeholder engine instead of pinning the caller forever.

When the property is **off** (the default), no `.so` is produced; `LlamaCppBackend` reports
not-ready and Advisor answers with its deterministic placeholder engine.

### Optional: GPU offload (Vulkan)

By default the model runs on the CPU (`backend CPU`, all layers CPU-assigned). To offload the layers
to the device GPU (e.g. an Adreno), add `-Padvisor.gpu=true`:

```
./gradlew :app:assembleDebug -Padvisor.buildNativeLlm=true -Padvisor.gpu=true
```

This builds ggml's **Vulkan** backend and sets `n_gpu_layers = 99` so the transformer layers run on the
GPU. Vulkan (not the Adreno-tuned OpenCL backend) is used deliberately: OpenCL only accelerates `Q4_0`,
whereas Vulkan runs the `Q4_K_M` weights we ship as-is.

It's **off by default** because it adds *build-host* requirements the CPU build doesn't have:

- **`glslc`** — the Vulkan shader compiler. Ships with the LunarG Vulkan SDK, and with the NDK under
  `shader-tools/`. If CMake can't find it, pass `-DVulkan_GLSLC_EXECUTABLE=<path>\glslc.exe`.
- **a host C++ compiler** — llama.cpp builds its `vulkan-shaders-gen` tool for the *host* (it detects
  MSVC/clang/gcc). On Windows, having Visual Studio Build Tools or the NDK clang on PATH satisfies this.

At runtime the Android side uses the device's own Vulkan driver (the NDK provides `libvulkan`). Confirm
offload in logcat: the load line prints `n_gpu_layers=99` and ggml lists a Vulkan device instead of only
`backend CPU`. If a Vulkan build or device init fails, the Kotlin side falls back to the placeholder, so
the CPU path stays available — flip the flag back off to return to the known-good CPU build.

The CMake build produces `libadvisor-llm.so` alongside its llama.cpp dependencies (`libllama.so`,
`libggml.so`). AGP's `externalNativeBuild` packages all of them into the APK's `lib/arm64-v8a/`, and
the dynamic linker resolves the `NEEDED` dependencies automatically (minSdk 26), so the Kotlin side
only needs `System.loadLibrary("advisor-llm")`. This build has been verified for `arm64-v8a` against
the pinned tag with the NDK's CMake toolchain.

## Checking a change without a device

`hosttest/run.sh` verifies this file with no NDK, no Android SDK and no phone:

```
advisor/src/main/cpp/hosttest/run.sh
```

It syntax-checks `advisor_llm.cpp` against the headers of the **pinned** llama.cpp tag (read out of
`CMakeLists.txt`, fetched and cached), using the host JDK's real `jni.h` and a stub `<android/log.h>` —
which is what catches the API drift a tag bump causes, normally discoverable only on a machine with the
NDK. It then compiles and runs the big-core selection tests against `select_big_cores` lifted straight
out of this file, so the test can't drift from the shipping policy. Nothing in `hosttest/` is part of
the Android build: CMake compiles `advisor_llm.cpp` and nothing else.

It is a check, not a build. It links nothing and runs no inference, so it says nothing about whether a
change is *fast* — that still needs the logs above, on a device.

## Providing the model weights

The `.so` is the engine; the weights ship separately (they're ~2.5 GB and don't belong in git). The
intended way to provide them is **in-app**: the Permissions screen's model card has an **Import model
file (.gguf)…** button that copies a GGUF you picked from device storage into the app's private
`files/models/` (progress-reported, on-device, no network). Advisor loads it on the next question.

At runtime `LlamaCppBackend` loads whatever `AdvisorModelStore` reports as installed — a `qwen3-4b*.gguf`
in, in order:

1. `filesDir/models/` (internal app storage — where the in-app import lands), or
2. `getExternalFilesDir("models")` — e.g. `/sdcard/Android/data/com.operations.sandbox/files/models/`.

The second path lets you side-load with `adb` instead of the in-app import, for example:

```
adb push qwen3-4b-q4_k_m.gguf \
  /sdcard/Android/data/com.operations.sandbox/files/models/qwen3-4b-q4_k_m.gguf
```

Either way, the model card shows whether the real model or the placeholder is live.

## Optional: the embedding model (semantic retrieval)

`advisor_llm.cpp` also implements `nativeLoad`/`nativeDim`/`nativeEmbed`/`nativeFree` for
`com.advisor.app.llm.LlamaCppEmbedder` — a **second, small** model loaded in embedding mode
(mean-pooled) that turns text into a vector so `EmbeddingRetriever` can rank the user's data by
meaning. It shares this library; no separate build step is needed.

Documents are embedded in **batches**, not one at a time: `nativeEmbedAll` packs up to eight of them
(512 tokens each) into a single `llama_decode`, so indexing a corpus costs one pass over the model's
weights per batch rather than per document. That is the cold-start cost of semantic retrieval — the
vector cache is in-memory, so the corpus is re-embedded on the first question after every app start —
and the log says what it actually did:

```
embedding: 143 documents in 18 decode(s)
```

The context is created with room for that (`n_seq_max = 8`, `n_ubatch` covering the whole batch, since
a non-causal embedding model cannot have a sequence split across micro-batches). If a model won't take
that shape, `nativeLoad` retries as a single sequence and the packing degenerates to one document at a
time — batching is an optimisation, not a reason to drop back to lexical retrieval. The limits are read
back from the created context, so the packing always matches what was actually granted.

Provision it exactly like the generation weights, but with a **small sentence-embedding GGUF**
(tens to a couple hundred MB — e.g. a `bge`, `e5`, `gte`, `minilm` or `nomic-embed` GGUF). The
Permissions screen's **Semantic retrieval** card imports one in-app, or side-load it:

> **The GGUF's architecture has to be one this llama.cpp pin knows.** A file that loads fine in a
> newer llama.cpp can still fail here — e.g. Jina Embeddings v5 (`general.architecture = eurobert`)
> is rejected at `b6490` with `unknown model architecture: 'eurobert'`, and retrieval silently stays
> lexical. The failure is visible in logcat (`adb logcat -s advisor-llm | grep -i 'architecture'`);
> the model families listed above are all supported at this pin.

```
adb push advisor-embed.gguf \
  /sdcard/Android/data/com.operations.sandbox/files/models/advisor-embed.gguf
```

The embedder keys on the filename (any `.gguf` whose name contains `embed`/`bge`/`gte-`/`e5-`/
`minilm`/`nomic`), so it coexists with `qwen3-4b*.gguf` in the same directory. Without an embedding
model — or in a build without the native library — retrieval is lexical, so this is purely additive.

> Unlike the generation path, the embedding JNI functions have **not** been compiled/verified on a
> device in this repo yet (they need an embedding GGUF to exercise). They're written against the same
> `b6490` API and the stock `examples/embedding` pooling pattern; the Kotlin side is fully guarded, so
> a mismatch falls back to lexical retrieval rather than crashing. Validate them when you first import
> an embedding model, and treat them the same as the generation calls when bumping the tag.

## Bumping llama.cpp

`advisor_llm.cpp` is written against the API of the pinned tag (`LLAMA_CPP_TAG`, currently `b6490`).
llama.cpp renames symbols fairly often (model/context init, the `llama_vocab` handle for
tokenize/detokenize/eog, cache reset), so if you raise the tag, update the calls in `advisor_llm.cpp`
in the same change. Note the cache-reset call in particular: at `b6490` it's
`llama_memory_clear(llama_get_memory(ctx), true)` — the older `llama_kv_self_clear` was removed, so a
plain tag bump onto a newer `llama.cpp` may rename this again.

The key API change from `b5600` to `b6490` was replacing `llama_batch_get_one()` (deprecated and removed)
with `llama_batch_init()` for batch management. When bumping again, verify that all batch-related calls
match the new API.

Also: llama.cpp's tag numbering isn't contiguous — some build numbers (e.g. `b6489`) were never tagged
because that CI build didn't publish a release. Check `git ls-remote --tags
https://github.com/ggerganov/llama.cpp.git` before pinning a specific number, since `FetchContent`'s
git clone fails outright ("invalid reference") on a tag that doesn't exist.
