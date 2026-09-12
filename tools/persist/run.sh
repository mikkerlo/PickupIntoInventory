#!/usr/bin/env bash
# Exercises the live src/.../PIIState.java - not a pinned copy of it.
#
# PIIState has no Minecraft imports beyond FMLLog, so it compiles and runs outside the game. Both
# harnesses are plain main() programs with no test framework: they print one line per assertion and
# exit non-zero on the first failure.
#
# CI runs this after ci/fetch-deps.sh, so `source build/deps/env.sh` already exports FORGE and
# LOG4J and nothing below has to guess. Set LIBS to a launcher libraries dir (the same one build.sh
# uses) or FORGE/LOG4J directly to run it against something else.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="${OUT:-$HERE/build}"

if [ -z "${FORGE:-}" ] || [ -z "${LOG4J:-}" ]; then
    if [ -f "$ROOT/build/deps/env.sh" ]; then
        # shellcheck disable=SC1091
        . "$ROOT/build/deps/env.sh"
    else
        LIBS="${LIBS:-$HOME/PrismLauncher/libraries}"
        FORGE="${FORGE:-$(ls "$LIBS"/net/minecraftforge/forge/1.7.10-*/forge-1.7.10-*-universal.jar | head -1)}"
        LOG4J="${LOG4J:-$(ls "$LIBS"/org/apache/logging/log4j/log4j-api/*/log4j-api-*.jar | head -1)}"
    fi
fi

# The same choice build.sh makes, and for the same reason: --release 8 arrived in JDK 9 and is the
# only one of the two that rejects a Java 9+ API, so it is used wherever it exists; on a JDK 8
# toolchain -source/-target is all there is, and there the platform library is already the right
# one. javac 8 prints its version to stderr, 9+ to stdout, so both are read.
JAVAC_MAJOR="$(javac -version 2>&1 | sed -n 's/^javac \(1\.\)\?\([0-9][0-9]*\).*/\2/p')"
if [ "${JAVAC_MAJOR:-9}" -ge 9 ]; then JAVA8=(--release 8); else JAVA8=(-source 8 -target 8); fi

rm -rf "$OUT" && mkdir -p "$OUT"
javac "${JAVA8[@]}" -proc:none -nowarn -cp "$FORGE:$LOG4J" -d "$OUT" \
    "$ROOT/src/net/greatkorn/pickupintoinventory/PIIState.java" \
    "$HERE/PersistHarness.java" "$HERE/ConcurrencyHarness.java"

java -cp "$OUT:$FORGE:$LOG4J" PersistHarness
java -cp "$OUT:$FORGE:$LOG4J" ConcurrencyHarness
