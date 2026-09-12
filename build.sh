#!/usr/bin/env bash
# Build the mod jar. Version comes from resources/mcmod.info.
#
# Dependencies are taken from a local GTNH instance by default:
#   GTNH  instance .minecraft dir (has mods/)      default ~/.minecraft
#   LIBS  launcher libraries dir                   default ~/PrismLauncher/libraries
#
# Any individual jar can be pointed elsewhere instead, which is how CI supplies them:
#   UNIMIXINS FPL MC FORGE NETTY LOG4J
# ci/fetch-deps.sh downloads all six from their public homes and prints those exports.
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
: "${LOG4J:=$(ls "$LIBS"/org/apache/logging/log4j/log4j-api/*/log4j-api-*.jar | head -1)}"

# --release arrived in JDK 9, and the JDK on hand for a 1.7.10 mod is quite often 8, which
# rejects the flag outright. Nothing is lost there: on 8, -source/-target compile against that
# JDK's own Java 8 library, which is the thing --release 8 emulates on a newer one. Parsed
# rather than probed, because a probe that fails for some other reason would quietly pick the
# weaker form on a JDK that can do better. javac 8 prints its version to stderr, 9+ to stdout.
JAVAC_MAJOR="$(javac -version 2>&1 | sed -n 's/^javac \(1\.\)\?\([0-9][0-9]*\).*/\2/p')"
if [ "${JAVAC_MAJOR:-9}" -ge 9 ]; then JAVA8=(--release 8); else JAVA8=(-source 8 -target 8); fi

VERSION="$(sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' "$HERE/resources/mcmod.info" | head -1)"
[ -n "$VERSION" ] || { echo "could not read version from resources/mcmod.info" >&2; exit 1; }

# The @Mod annotation carries a version of its own, and FML prefers it in silence: bindMetadata
# reads internalVersion straight out of the annotation and only falls back to version.properties or
# mcmod.info - logging that it did - when the annotation is null or empty. A populated annotation and
# a different mcmod.info therefore coexist with no warning at all, and the two are then read by
# different callers: getVersion() and getProcessedVersion() answer the annotation, getDisplayVersion()
# answers mcmod.info. Which is how the jar came to be named 1.4.0 while the mod called itself 1.3.0.
# Checked rather than derived because the annotation has to be a compile-time constant.
ANNOTATED="$(sed -n 's/^[[:space:]]*version[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' \
    "$HERE/src/net/greatkorn/pickupintoinventory/PickupIntoInventory.java" | head -1)"
[ -n "$ANNOTATED" ] || { echo "could not read version from the @Mod annotation" >&2; exit 1; }
[ "$ANNOTATED" = "$VERSION" ] || {
    echo "version mismatch: @Mod says $ANNOTATED, resources/mcmod.info says $VERSION" >&2
    exit 1
}

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
    # javac 9+ creates the -d directory; javac 8 insists it already exist. Emptied rather
    # than created, like the mappings beside it: FORCE_REMAP is for rebuilding after a change
    # to Remap.java, and a nested class it has stopped emitting would otherwise linger here
    # and stay on the classpath of the two runs below.
    rm -rf remapper && mkdir remapper
    javac -cp "$ASM" -d remapper "$HERE/tools/Remap.java"
    java -cp "$ASM:remapper" Remap mappings "$MC"    mc-srg.jar
    java -cp "$ASM:remapper" Remap mappings "$FORGE" forge-srg.jar
else
    echo "reusing cached mc-srg.jar / forge-srg.jar (FORCE_REMAP=1 to rebuild)"
fi

cd "$HERE"
rm -rf "$WORK/classes" && mkdir -p "$WORK/classes"
# -proc:none: we hand-wrote SRG targets, so the Mixin AP must not try to build a refmap.
javac "${JAVA8[@]}" -proc:none -nowarn \
      -cp "$WORK/tools/mc-srg.jar:$WORK/tools/forge-srg.jar:$UNIMIXINS:$NETTY:$LOG4J" \
      -d "$WORK/classes" $(find src -name '*.java')
cp -r resources/* "$WORK/classes/"
jar cfm "$WORK/pickupintoinventory-$VERSION.jar" manifest.txt -C "$WORK/classes" .
echo "built $WORK/pickupintoinventory-$VERSION.jar"
