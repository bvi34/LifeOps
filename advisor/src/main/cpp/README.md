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

The CMake build produces `libadvisor-llm.so` alongside its llama.cpp dependencies (`libllama.so`,
`libggml.so`). AGP's `externalNativeBuild` packages all of them into the APK's `lib/arm64-v8a/`, and
the dynamic linker resolves the `NEEDED` dependencies automatically (minSdk 26), so the Kotlin side
only needs `System.loadLibrary("advisor-llm")`. This build has been verified for `arm64-v8a` against
the pinned tag with the NDK's CMake toolchain.

## Providing the model weights

The `.so` is the engine; the weights ship separately (they're ~2.5 GB and don't belong in git). At
runtime `LlamaCppBackend` looks for a `qwen3-4b*.gguf` in, in order:

1. `filesDir/models/` (internal app storage), or
2. `getExternalFilesDir("models")` — e.g. `/sdcard/Android/data/com.operations.sandbox/files/models/`.

Push a quantized file there, for example:

```
adb push qwen3-4b-q4_k_m.gguf \
  /sdcard/Android/data/com.operations.sandbox/files/models/qwen3-4b-q4_k_m.gguf
```

The Permissions screen's model card shows whether the real model or the placeholder is live.

## Bumping llama.cpp

`advisor_llm.cpp` is written against the API of the pinned tag (`LLAMA_CPP_TAG`, currently `b4000`).
llama.cpp renames symbols fairly often (model/context init, vocab-based tokenize, batch helpers), so
if you raise the tag, update the calls in `advisor_llm.cpp` in the same change.
