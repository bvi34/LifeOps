# Native backend for Advisor's on-device model: builds libadvisor-llm.so, a thin JNI bridge over
# llama.cpp that runs Qwen3-4B (GGUF) locally. Only built when the Gradle property
# `advisor.buildNativeLlm=true` is set (see advisor/build.gradle.kts) — a plain build of the app does
# not need the NDK and simply ships without the .so, in which case the app falls back to the
# deterministic placeholder engine.

cmake_minimum_required(VERSION 3.22.1)
project(advisor_llm CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp is pinned. advisor_llm.cpp is written against this tag's API; bump them together.
set(LLAMA_CPP_TAG "b5500" CACHE STRING "llama.cpp release tag to build against")

# Keep the fetched build lean — we only need the library, not tools/tests/server/curl.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
        GIT_TAG        ${LLAMA_CPP_TAG}
        GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(advisor-llm SHARED advisor_llm.cpp)

# `android` and `log` are NDK system libraries; `llama` comes from the fetched project.
find_library(log-lib log)
find_library(android-lib android)
target_link_libraries(advisor-llm llama ${android-lib} ${log-lib})
