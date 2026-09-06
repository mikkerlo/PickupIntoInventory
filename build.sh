#!/usr/bin/env bash
# Build the mod jar. Version comes from resources/mcmod.info.
#
# Dependencies are taken from a local GTNH instance by default:
#   GTNH  instance .minecraft dir (has mods/)      default ~/.minecraft
#   LIBS  launcher libraries dir                   default ~/PrismLauncher/libraries
#
# Any individual jar can be pointed elsewhere instead, which is how CI supplies them:
#   UNIMIXINS FPL MC FORGE NETTY
# ci/fetch-deps.sh downloads all five from their public homes and prints those exports.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$HERE/build"

GTNH="${GTNH:-$HOME/.minecraft}"
LIBS="${LIBS:-$HOME/PrismLauncher/libraries}"

# ':=' only runs the lookup when the variable is unset, so CI never touches GTNH/LIBS.
: "${UNIMIXINS:=$(ls "$GTNH"/mods/*unimixins-all*.jar | head -1)}"
: "${FPL:=$(ls "$GTNH"/mods/falsepatternlib-*.jar | head -1)}"
: "${MC:=$LIBS/com/mojang/minecraft/1.7.10/minecraft-1.7.10-client.jar}"
: "${FORGE:=$(ls "$LIBS"/net/minecraftforge/forge/1.7.10-*/forge-1.7.10-*-universal.jar | head -1)}"
: "${NETTY:=$(ls "$LIBS"/io/netty/netty-all/*/netty-all-*.jar | head -1)}"

VERSION="$(sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' "$HERE/resources/mcmod.info" | head -1)"
[ -n "$VERSION" ] || { echo "could not read version from resources/mcmod.info" >&2; exit 1; }

mkdir -p "$WORK/tools"
cd "$WORK/tools"
for a in asm asm-tree asm-analysis asm-commons; do
    [ -f "$a-9.7.jar" ] || curl -sSfLO "https://repo1.maven.org/maven2/org/ow2/asm/$a/9.7/$a-9.7.jar"
done
ASM="asm-9.7.jar:asm-commons-9.7.jar:asm-tree-9.7.jar:asm-analysis-9.7.jar"

# Remapping Minecraft and Forge to SRG names takes a while and the result only depends on
# those two jars, so it is cached. FORCE_REMAP=1 rebuilds it after changing Remap.java.
if [ ! -f mc-srg.jar ] || [ ! -f forge-srg.jar ] || [ -n "${FORCE_REMAP:-}" ]; then
    rm -rf mappings && mkdir mappings && (cd mappings && unzip -oq "$FPL" '*.csv')
    javac -cp "$ASM" -d remapper "$HERE/tools/Remap.java"
    java -cp "$ASM:remapper" Remap mappings "$MC"    mc-srg.jar
    java -cp "$ASM:remapper" Remap mappings "$FORGE" forge-srg.jar
else
    echo "reusing cached mc-srg.jar / forge-srg.jar (FORCE_REMAP=1 to rebuild)"
fi

cd "$HERE"
rm -rf "$WORK/classes" && mkdir -p "$WORK/classes"
# -proc:none: we hand-wrote SRG targets, so the Mixin AP must not try to build a refmap.
javac --release 8 -proc:none -nowarn \
      -cp "$WORK/tools/mc-srg.jar:$WORK/tools/forge-srg.jar:$UNIMIXINS:$NETTY" \
      -d "$WORK/classes" $(find src -name '*.java')
cp -r resources/* "$WORK/classes/"
jar cfm "$WORK/pickupintoinventory-$VERSION.jar" manifest.txt -C "$WORK/classes" .
echo "built $WORK/pickupintoinventory-$VERSION.jar"
