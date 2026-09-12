#!/usr/bin/env bash
# No Minecraft on the classpath and nothing to configure: the model is plain Java.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${OUT:-$HERE/build}"
rm -rf "$OUT" && mkdir -p "$OUT"
javac -proc:none -nowarn -d "$OUT" "$HERE"/*.java
java -cp "$OUT" ProtocolHarness
