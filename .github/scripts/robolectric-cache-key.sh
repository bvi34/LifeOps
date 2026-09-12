#!/usr/bin/env bash
# Prints the cache key for the Android runtimes Robolectric fetches at test time.
#
# Robolectric does not ship the Android framework. The first test that needs an SDK level downloads
# a ~100MB `android-all-instrumented` jar from Maven Central into ~/.m2/repository, in the middle of
# the test run, with no retry: one refused connection throws out of MavenArtifactFetcher and fails
# the build — which is how a green release once turned red on a test that never ran. Caching that
# directory means only the first run after one of the inputs below goes anywhere near the network.
#
# The key covers everything that decides which jars are fetched: the Robolectric version, the SDK
# levels tests pin with @Config, and the compile/min/target SDKs the unpinned ones fall back to. A
# miss is not a failure — Robolectric downloads what is missing, exactly as it does today, and the
# run saves the result for the next one.
set -euo pipefail
cd "$(dirname "$0")/../.."
{
  grep -rho 'org\.robolectric:robolectric:[0-9.]*' --include=build.gradle.kts .
  grep -rho '@Config(sdk = \[[0-9, ]*\]' --include='*.kt' .
  grep -rhoE '(compileSdk|minSdk|targetSdk) = [0-9]+' --include=build.gradle.kts .
} | sort -u | sha256sum | cut -c1-16
