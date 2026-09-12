#!/usr/bin/env bash
# Exercises the live src/.../PIIState.java - not a pinned copy of it.
#
# PIIState has no Minecraft imports beyond FMLLog, so it compiles and runs outside the game. Both
# harnesses are plain main() programs with no test framework: they print one line per assertion and
# exit non-zero on the first failure, which is all CI needs from them.
#
# Set LIBS to a launcher libraries dir (the same one build.sh uses) or FORGE/LOG4J directly.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="${OUT:-$HERE/build}"

LIBS="${LIBS:-$HOME/PrismLauncher/libraries}"
FORGE="${FORGE:-$(ls "$LIBS"/net/minecraftforge/forge/1.7.10-*/forge-1.7.10-*-universal.jar | head -1)}"
LOG4J="${LOG4J:-$(ls "$LIBS"/org/apache/logging/log4j/log4j-api/*/log4j-api-*.jar | head -1)}"

rm -rf "$OUT" && mkdir -p "$OUT"
javac -proc:none -nowarn -cp "$FORGE:$LOG4J" -d "$OUT" \
    "$ROOT/src/net/greatkorn/pickupintoinventory/PIIState.java" \
    "$HERE/PersistHarness.java" "$HERE/ConcurrencyHarness.java"

java -cp "$OUT:$FORGE:$LOG4J" PersistHarness
java -cp "$OUT:$FORGE:$LOG4J" ConcurrencyHarness
