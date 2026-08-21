#!/bin/sh
# Verify the native backend without a device, an NDK or an Android SDK.
#
#   ./run.sh          # both checks
#
# 1. syntax-check advisor_llm.cpp against the *pinned* llama.cpp headers (LLAMA_CPP_TAG in
#    ../CMakeLists.txt) using the host JDK's real jni.h and a stub <android/log.h>. This catches the
#    API drift that llama.cpp tag bumps cause, which otherwise only shows up as a failed Gradle build
#    on a machine that has the NDK.
# 2. build and run the big-core selection tests against the shipping select_big_cores.
#
# Needs: a host C++17 compiler, a JDK, and network access on first run (to fetch the headers, which
# are cached in .cache/ afterwards). It is NOT a device build and links nothing — the Android build
# compiles advisor_llm.cpp only, so nothing in this directory reaches the APK.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../advisor_llm.cpp"
CACHE="$HERE/.cache"
TAG="$(sed -n 's/^set(LLAMA_CPP_TAG "\(.*\)")$/\1/p' "$HERE/../CMakeLists.txt")"
[ -n "$TAG" ] || { echo "could not read LLAMA_CPP_TAG from ../CMakeLists.txt" >&2; exit 1; }

JDK="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
[ -f "$JDK/include/jni.h" ] || { echo "no jni.h under $JDK — set JAVA_HOME" >&2; exit 1; }

if [ ! -f "$CACHE/$TAG/llama.h" ]; then
    echo "fetching llama.cpp $TAG headers…"
    mkdir -p "$CACHE/$TAG"
    BASE="https://raw.githubusercontent.com/ggerganov/llama.cpp/$TAG"
    for f in include/llama.h ggml/include/ggml.h ggml/include/ggml-cpu.h \
             ggml/include/ggml-backend.h ggml/include/ggml-alloc.h ggml/include/ggml-opt.h; do
        curl -fsSL -o "$CACHE/$TAG/$(basename "$f")" "$BASE/$f"
    done
fi

mkdir -p "$CACHE/stub/android"
cat > "$CACHE/stub/android/log.h" <<'STUB'
// Host stub of the NDK's <android/log.h>. Declarations only; nothing here is linked.
#pragma once
#include <stdarg.h>
enum android_LogPriority {
    ANDROID_LOG_UNKNOWN = 0, ANDROID_LOG_DEFAULT, ANDROID_LOG_VERBOSE, ANDROID_LOG_DEBUG,
    ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR, ANDROID_LOG_FATAL, ANDROID_LOG_SILENT,
};
#ifdef __cplusplus
extern "C" {
#endif
int __android_log_print(int prio, const char* tag, const char* fmt, ...)
    __attribute__((format(printf, 3, 4)));
#ifdef __cplusplus
}
#endif
STUB

echo "== syntax-checking advisor_llm.cpp against llama.cpp $TAG =="
${CXX:-g++} -fsyntax-only -std=c++17 -Wall -Wextra -Wno-unused-parameter \
    -I"$CACHE/$TAG" -I"$CACHE/stub" -I"$JDK/include" -I"$JDK/include/linux" "$SRC"
echo "advisor_llm.cpp: OK"

echo
echo "== big-core selection tests =="
# Lift select_big_cores straight out of the shipping file so the test can never drift from it.
awk '/^BigCores select_big_cores/,/^}$/' "$SRC" > "$CACHE/select_big_cores.inc"
[ -s "$CACHE/select_big_cores.inc" ] || { echo "could not extract select_big_cores" >&2; exit 1; }
${CXX:-g++} -std=c++17 -Wall -Wextra -I"$CACHE" -o "$CACHE/cpu_topology_test" \
    "$HERE/cpu_topology_test.cpp"
"$CACHE/cpu_topology_test"
