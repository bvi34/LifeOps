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

Provision it exactly like the generation weights, but with a **small sentence-embedding GGUF**
(tens to a couple hundred MB — e.g. a `bge`, `e5`, `gte`, `minilm` or `nomic-embed` GGUF). The
Permissions screen's **Semantic retrieval** card imports one in-app, or side-load it:

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
