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
# ~500-token prompt takes minutes to prefill on-device. Our global -O3 (CMAKE_CXX_FLAGS) is overridden
# by the Debug per-config flags (-O0 comes later on the command line and wins), so force the Debug-config
# C/C++ flags to be optimized too. This must be set before FetchContent builds llama.cpp so its targets
# inherit it. (Release/RelWithDebInfo variants are already optimized.)
set(CMAKE_C_FLAGS_DEBUG   "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG" CACHE STRING "" FORCE)

# llama.cpp is pinned. advisor_llm.cpp is written against this tag's API; bump them together.
# NOT a CACHE var on purpose: a cached value persists in .cxx/CMakeCache.txt across builds, so a plain
# `set(... CACHE ...)` would let a stale tag from an earlier configure silently win over a bump here
# (and FetchContent would then reuse the old clone). A normal set is re-evaluated every configure.
set(LLAMA_CPP_TAG "b5600")

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

# `android` and `log` are NDK system libraries; `llama` comes from the fetched project.
find_library(log-lib log)
find_library(android-lib android)
target_link_libraries(advisor-llm llama ${android-lib} ${log-lib})
