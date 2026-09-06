#!/usr/bin/env bash
# Download the five jars build.sh needs, from their public homes, and verify them by SHA-1.
# Writes them into a deps dir and drops an env.sh next to them with the exports build.sh reads.
#
#   ci/fetch-deps.sh [dest-dir]        # default: build/deps
#   source build/deps/env.sh && ./build.sh
#
# The pinned hashes are the jars a GTNH 2.9.0-beta-2 / daily-2026-09-01 instance actually ships,
# so a CI build compiles against byte-identical inputs to a local one.
#
# Minecraft's URL is content-addressed and comes from Mojang's version manifest; re-derive with:
#   curl -s https://launchermeta.mojang.com/mc/game/version_manifest_v2.json \
#     | jq -r '.versions[]|select(.id=="1.7.10").url' | xargs curl -s | jq '.downloads.client'
#
# FalsePatternLib and UniMixins are taken from their GitHub releases rather than
# mvn.falsepattern.com, which stalls mid-transfer often enough to break a build.
set -euo pipefail

DEST="${1:-$(cd "$(dirname "$0")/.." && pwd)/build/deps}"
mkdir -p "$DEST"

fetch() { # name url sha1
    local name=$1 url=$2 want=$3 out="$DEST/$1" got
    if [ -f "$out" ]; then
        got=$(sha1sum "$out" | cut -d' ' -f1)
        if [ "$got" = "$want" ]; then echo "cached $name"; return; fi
        echo "stale  $name (sha1 $got), refetching"
    fi
    echo "fetch  $name"
    curl -sSfL --retry 3 --retry-delay 2 --max-time 300 -o "$out" "$url"
    got=$(sha1sum "$out" | cut -d' ' -f1)
    if [ "$got" != "$want" ]; then
        echo "sha1 mismatch for $name: expected $want, got $got" >&2
        rm -f "$out"
        exit 1
    fi
}

fetch minecraft-1.7.10-client.jar \
    "https://launcher.mojang.com/v1/objects/e80d9b3bf5085002218d4be59e668bac718abbc6/client.jar" \
    e80d9b3bf5085002218d4be59e668bac718abbc6

fetch forge-1.7.10-10.13.4.1614-universal.jar \
    "https://maven.minecraftforge.net/net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/forge-1.7.10-10.13.4.1614-1.7.10-universal.jar" \
    25fd97f72beca728112256938e03e8105b1b78cc

fetch unimixins-all-1.7.10-0.3.1.jar \
    "https://github.com/LegacyModdingMC/UniMixins/releases/download/0.3.1/%2Bunimixins-all-1.7.10-0.3.1.jar" \
    75f9251b1ac4eeacdce0b86a1a68bd8ff4fbf604

fetch falsepatternlib-mc1.7.10-1.12.2.jar \
    "https://github.com/FalsePattern/FalsePatternLib/releases/download/1.12.2/falsepatternlib-mc1.7.10-1.12.2.jar" \
    9ec3ae13ef669b55173739eb49178050356cfbc7

fetch netty-all-4.0.10.Final.jar \
    "https://repo1.maven.org/maven2/io/netty/netty-all/4.0.10.Final/netty-all-4.0.10.Final.jar" \
    9e50bd52ffe257a0e2cd8d971688d6ce7d174325

cat > "$DEST/env.sh" <<ENV
export MC="$DEST/minecraft-1.7.10-client.jar"
export FORGE="$DEST/forge-1.7.10-10.13.4.1614-universal.jar"
export UNIMIXINS="$DEST/unimixins-all-1.7.10-0.3.1.jar"
export FPL="$DEST/falsepatternlib-mc1.7.10-1.12.2.jar"
export NETTY="$DEST/netty-all-4.0.10.Final.jar"
ENV
echo "wrote $DEST/env.sh"
