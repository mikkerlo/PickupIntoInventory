#!/usr/bin/env bash
# No Minecraft on the classpath and nothing to configure: the model is plain Java.
# Deliberately no --release: the sources are written to compile on any JDK from 8 up, and 8 is
# the one the mod itself is built with, so the flag would only make this fail there.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${OUT:-$HERE/build}"
rm -rf "$OUT" && mkdir -p "$OUT"
javac -proc:none -nowarn -d "$OUT" "$HERE"/*.java
java -cp "$OUT" ProtocolHarness
