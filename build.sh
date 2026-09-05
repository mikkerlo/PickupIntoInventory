#!/usr/bin/env bash
# Rebuild pickupintoinventory-1.3.0.jar. Set GTNH and LIBS for your machine.
set -euo pipefail

GTNH="${GTNH:-$HOME/.minecraft}"                       # instance .minecraft dir (has mods/)
LIBS="${LIBS:-$HOME/PrismLauncher/libraries}"          # launcher libraries dir
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$HERE/build"

UNIMIXINS="$(ls "$GTNH"/mods/*unimixins-all*.jar | head -1)"
FPL="$(ls "$GTNH"/mods/falsepatternlib-*.jar | head -1)"
MC="$LIBS/com/mojang/minecraft/1.7.10/minecraft-1.7.10-client.jar"
FORGE="$(ls "$LIBS"/net/minecraftforge/forge/1.7.10-*/forge-1.7.10-*-universal.jar | head -1)"
NETTY="$(ls "$LIBS"/io/netty/netty-all/*/netty-all-*.jar | head -1)"

mkdir -p "$WORK/tools"
cd "$WORK/tools"
for a in asm asm-tree asm-analysis asm-commons; do
    [ -f "$a-9.7.jar" ] || curl -sSfLO "https://repo1.maven.org/maven2/org/ow2/asm/$a/9.7/$a-9.7.jar"
done
ASM="asm-9.7.jar:asm-commons-9.7.jar:asm-tree-9.7.jar:asm-analysis-9.7.jar"

rm -rf mappings && mkdir mappings && (cd mappings && unzip -oq "$FPL" '*.csv')
javac -cp "$ASM" -d remapper "$HERE/tools/Remap.java"
java -cp "$ASM:remapper" Remap mappings "$MC"    mc-srg.jar
java -cp "$ASM:remapper" Remap mappings "$FORGE" forge-srg.jar

cd "$HERE"
rm -rf "$WORK/classes" && mkdir -p "$WORK/classes"
# -proc:none: we hand-wrote SRG targets, so the Mixin AP must not try to build a refmap.
javac --release 8 -proc:none -nowarn \
      -cp "$WORK/tools/mc-srg.jar:$WORK/tools/forge-srg.jar:$UNIMIXINS:$NETTY" \
      -d "$WORK/classes" $(find src -name '*.java')
cp -r resources/* "$WORK/classes/"
jar cfm "$WORK/pickupintoinventory-1.3.0.jar" manifest.txt -C "$WORK/classes" .
echo "built $WORK/pickupintoinventory-1.3.0.jar"
