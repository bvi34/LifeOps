#!/usr/bin/env bash
# Runs `./gradlew` with the arguments given, and retries once — and only — when the build failed
# because Robolectric could not download an Android runtime.
#
# Robolectric fetches the framework jar for an SDK level from Maven Central in the middle of the
# test run and gives up on the first refused connection, so a dropped TCP handshake reads as a
# failing test: "FinanceSecretsVaultTest ... FAILED, java.lang.AssertionError at
# MavenArtifactFetcher.java:129, Caused by: java.net.ConnectException". The restored cache means
# this is rare, but the first run after a Robolectric or SDK-level change still has to go out to the
# network, and a release should not die on one packet.
#
# The retry is deliberately keyed to that one message: a failing assertion does not print it, so a
# genuinely broken test still fails the build the first time, exactly as it should. Gradle re-runs
# only the task that failed — everything else is up to date — so the retry costs seconds.
set -uo pipefail

log="$(mktemp)"
trap 'rm -f "$log"' EXIT

if ./gradlew "$@" 2>&1 | tee "$log"; then
  exit 0
fi

if grep -q 'Failed to fetch maven artifact' "$log"; then
  echo "::warning::Robolectric could not download an Android runtime from Maven Central. Retrying the tests once — nothing about the code is being re-judged, only the download."
  ./gradlew "$@"
  exit $?
fi

exit 1
