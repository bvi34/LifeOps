# Native backend for Advisor's on-device model: builds libadvisor-llm.so, a thin JNI bridge over
# llama.cpp that runs Qwen3-4B (GGUF) locally. Only built when the Gradle property
# `advisor.buildNativeLlm=true` is set (see advisor/build.gradle.kts) — a plain build of the app does
# not need the NDK and simply ships without the .so, in which case the app falls back to the
# deterministic placeholder engine.

cmake_minimum_required(VERSION 3.22.1)
project(advisor_llm CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# CRITICAL for speed: AGP's *debug* app variant configures this native build as CMAKE_BUILD_TYPE=Debug,
# which compiles the fetched ggml/llama.cpp at -O0. Unoptimized ggml is ~15-30x slower — enough that a
# ~500-token prompt takes minutes to prefill on-device.
#
# Fixing that by overriding CMAKE_<LANG>_FLAGS_DEBUG (what this file used to do) is a bet, not a
# guarantee: those variables are emitted EARLY on the compile command, so any -O0 that the NDK
# toolchain file or AGP appends afterwards silently wins, and nothing in the build output says so.
# add_compile_options lands in the directory's COMPILE_OPTIONS property, which clang sees LAST — so
# this -O2 beats any -O0 upstream of it no matter who injected it. It must come before FetchContent
# adds llama.cpp, since only subdirectories added afterwards inherit the property.
#
# Language matters as much as position. AGP's `cppFlags` covers C++ ONLY, and ggml's hot compute —
# ggml.c, ggml-cpu.c, quants.c, arch/arm/quants.c — is C. Building the C++ half optimized and the C
# half at -O0 produces exactly the symptom this backend spent a dozen PRs on: model loading and
# tokenization (C++) are fast, so the build looks healthy, while prefill burns ~1.2 GFLOP/s per core
# — roughly 30x off — and does not respond to -march or ISA flags, because unoptimized intrinsics
# spill to the stack whether or not the CPU has dotprod and i8mm. So set this for both languages.
#
# -O2, not -O3: at -O3 the NDK clang miscompiled a ggml kernel and prefill SIGSEGV'd on the first
# decode. -O2 gives essentially all of ggml's speed — its hot paths are hand-written SIMD, largely
# unaffected by O2-vs-O3 — without the aggressive transforms. NDEBUG disables the plain asserts in
# ggml's inner loops; GGML_ASSERT is unconditional in ggml.h, so a real out-of-bounds still aborts
# with a message through our log bridge.
add_compile_options(-O2)
add_compile_definitions(NDEBUG)

# Belt and braces: also fix the per-config flags, so the compile line is optimized from both ends.
set(CMAKE_C_FLAGS_DEBUG   "-O2" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG "-O2" CACHE STRING "" FORCE)

# CRITICAL for speed #2: give ggml the ARM ISA extensions this device actually has.
#
# ggml only probes the CPU when it builds *for the machine it is running on* (GGML_NATIVE). An Android
# build is a cross-compile, so ggml sets GGML_NATIVE_DEFAULT=OFF, and with neither GGML_CPU_ARM_ARCH nor
# GGML_CPU_ALL_VARIANTS set it appends **no `-march` at all** (see ggml/src/ggml-cpu/CMakeLists.txt, the
# `if (GGML_NATIVE) ... else()` branch). The NDK's default for arm64-v8a is plain `armv8-a`, so
# `__ARM_FEATURE_DOTPROD` and `__ARM_FEATURE_MATMUL_INT8` are undefined and ggml compiles the *baseline*
# NEON kernels: no `vdotq_s32` in the Q4_K dot product, and no i8mm (`vmmlaq_s32`) in the batched GEMM
# that prefill is made of. On a modern phone that is several times slower than the same source built
# with the extensions — the difference between a prompt prefilling in seconds and one that looks hung.
#
# `armv8.2-a+dotprod+fp16` is safe on essentially every arm64 Android phone since ~2018 (Cortex-A75/A55
# and newer). `+i8mm` is ARMv8.6 (Cortex-X2/A710/A510 and newer, i.e. Snapdragon 8 Gen 1 / 8+ Gen 1 and
# up) and is the single biggest prefill lever, so it is in the default. If the APK has to run on an
# older arm64 device, drop it — an unsupported extension is a SIGILL at the first matmul, not a
# graceful fallback:
#
#     ./gradlew :app:assembleDebug -Padvisor.buildNativeLlm=true -Padvisor.cpuArch=armv8.2-a+dotprod+fp16
#
# nativeLoad logs llama_print_system_info() at startup; check `dotprod = 1` / `matmul_int8 = 1` there to
# confirm what actually got compiled in rather than assuming.
if (NOT ADVISOR_CPU_ARCH)
    set(ADVISOR_CPU_ARCH "armv8.2-a+dotprod+i8mm+fp16")
endif()
set(GGML_CPU_ARM_ARCH "${ADVISOR_CPU_ARCH}" CACHE STRING "" FORCE)
message(STATUS "advisor-llm: ggml ARM arch = ${GGML_CPU_ARM_ARCH}")

# llama.cpp is pinned. advisor_llm.cpp is written against this tag's API; bump them together.
# NOT a CACHE var on purpose: a cached value persists in .cxx/CMakeCache.txt across builds, so a plain
# `set(... CACHE ...)` would let a stale tag from an earlier configure silently win over a bump here
# (and FetchContent would then reuse the old clone). A normal set is re-evaluated every configure.
# Updated from b5600 to b6490 to use the modern llama_batch_init API (llama_batch_get_one was removed).
# NOTE: b6489 (the immediately preceding build number) is not a real tag — CI skipped it, so
# FetchContent's git clone fails with "invalid reference: b6489" if you try to pin it.
set(LLAMA_CPP_TAG "b6490")

# Keep the fetched build lean — we only need the library, not tools/tests/server/curl.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)

# Optional GPU offload via ggml's Vulkan backend (advisor.gpu=true → -DADVISOR_GPU=ON from Gradle).
# Chosen over OpenCL because it accelerates the Q4_K_M model as-is (llama.cpp's OpenCL backend only
# handles Q4_0). It needs, on the *build host*: the Vulkan glslc shader compiler and a host C++ compiler
# (llama.cpp builds its vulkan-shaders-gen tool for the host). At runtime it uses the device's own
# Vulkan driver (the NDK provides libvulkan for the Android side). If glslc isn't found, point CMake at
# it with -DVulkan_GLSLC_EXECUTABLE=<path-to-glslc> (the LunarG Vulkan SDK or the NDK ships one).
option(ADVISOR_GPU "Build the Vulkan GPU backend and offload model layers to the device GPU" OFF)
if (ADVISOR_GPU)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    message(STATUS "advisor-llm: GPU offload ON — building ggml Vulkan backend")
endif()

include(FetchContent)
FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
        GIT_TAG        ${LLAMA_CPP_TAG}
        GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(advisor-llm SHARED advisor_llm.cpp)

# Tell the C++ whether a GPU backend is in the build, so it offloads layers only when one exists.
if (ADVISOR_GPU)
    target_compile_definitions(advisor-llm PRIVATE ADVISOR_GPU_OFFLOAD=1)
endif()

# Weight loading strategy: OFF (the default) reads the GGUF into anonymous memory, ON mmaps the file.
# See the comment in nativeLoad — on a memory-tight phone, file-backed weights get evicted and re-read
# from flash on every forward pass, which no kernel-level speedup can compensate for.
option(ADVISOR_MMAP "Load the model with mmap instead of reading it into anonymous memory" OFF)
if (ADVISOR_MMAP)
    target_compile_definitions(advisor-llm PRIVATE ADVISOR_USE_MMAP=1)
endif()

# Print what the compiler is actually being handed. Whether ggml ends up optimized decided ~30x of
# inference speed here and was invisible on-device (the ISA feature line reports what the preprocessor
# saw, not what the optimizer did), so it belongs in the build log rather than in an inference:
#   ./gradlew :app:assembleDebug -Padvisor.buildNativeLlm=true --info | grep 'advisor-llm: flags'
get_directory_property(ADVISOR_EFFECTIVE_OPTIONS COMPILE_OPTIONS)
message(STATUS "advisor-llm: flags build_type=${CMAKE_BUILD_TYPE} compile_options=${ADVISOR_EFFECTIVE_OPTIONS}")
message(STATUS "advisor-llm: flags C   ='${CMAKE_C_FLAGS}' + debug='${CMAKE_C_FLAGS_DEBUG}'")
message(STATUS "advisor-llm: flags CXX ='${CMAKE_CXX_FLAGS}' + debug='${CMAKE_CXX_FLAGS_DEBUG}'")

# `android` and `log` are NDK system libraries; `llama` comes from the fetched project.
find_library(log-lib log)
find_library(android-lib android)
target_link_libraries(advisor-llm llama ${android-lib} ${log-lib})
