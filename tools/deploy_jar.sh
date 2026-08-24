#!/bin/sh
# Deploys the CURRENT-version built jar to Thomas's play profiles, safely.
#
# Guards against the two ways deployment has broken the game before:
#  1. THE STALE-JAR TRAP — always reads mod_version from gradle.properties
#     and byte-verifies the copy (same filename can hold different bytes).
#  2. THE JAR-SWAP CRASH (2x on 2026-08-18) — REFUSES to run while Minecraft
#     is open: the JVM keeps the zip central directory from launch, so
#     replacing the file corrupts every not-yet-loaded class and the next
#     lazy classload dies with "ZipException: invalid LOC header"
#     (TurnstileBlock$Crossing at 21:31, PlatformPicker at 23:09).
set -e
cd "$(dirname "$0")/.."

VER=$(grep '^mod_version=' gradle.properties | cut -d= -f2)
JAR="build/libs/station-announcer-$VER.jar"
[ -f "$JAR" ] || { echo "ERROR: $JAR not built — run ./gradlew build first"; exit 1; }

if pgrep -f "1.20.4 Fabric Essential" >/dev/null 2>&1; then
    echo "REFUSING TO DEPLOY: Minecraft is running."
    echo "Swapping the jar under a live JVM crashes the game on the next"
    echo "lazily-loaded class. Ask Thomas to close the game, then rerun."
    exit 1
fi

for MODS in \
    "$HOME/Library/Application Support/minecraft/installations/1.20.4 Fabric Essential/mods" \
    "$HOME/Library/Application Support/ModrinthApp/profiles/Baker City 1.20.4/mods"; do
    [ -d "$MODS" ] || continue
    # a second station-announcer jar of another version = duplicate mod id = boot failure
    find "$MODS" -name 'station-announcer-*.jar' ! -name "station-announcer-$VER.jar" -delete
    cp "$JAR" "$MODS/station-announcer-$VER.jar"
    if cmp -s "$JAR" "$MODS/station-announcer-$VER.jar"; then
        echo "deployed $VER -> $MODS"
    else
        echo "ERROR: byte mismatch after copy in $MODS"; exit 1
    fi
done
