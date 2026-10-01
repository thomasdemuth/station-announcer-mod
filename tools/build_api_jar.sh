#!/bin/zsh
# Builds the pure-Java wayfinding API jar WITHOUT Gradle (safe while the dev rig runs).
# The API package has no dependencies beyond the JDK, so plain javac is enough.
# Usage: tools/build_api_jar.sh [output-dir]   -> station-announcer-api-<VERSION>.jar
set -e
cd "$(dirname "$0")/.."
: ${JAVA_HOME:="$HOME/Library/Application Support/minecraft/runtime/java-runtime-gamma/mac-os-arm64/java-runtime-gamma/jre.bundle/Contents/Home"}
OUT=${1:-build/libs}
VERSION=$(grep -o 'VERSION = [0-9]*' src/main/java/com/stationannouncer/api/WayfindingApi.java | grep -o '[0-9]*')
TMP=$(mktemp -d)
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -d "$TMP/classes" src/main/java/com/stationannouncer/api/*.java
mkdir -p "$TMP/classes/com/stationannouncer/api" "$OUT"
cp src/main/java/com/stationannouncer/api/*.java "$TMP/classes/com/stationannouncer/api/"
"$JAVA_HOME/bin/jar" --create --file "$OUT/station-announcer-api-$VERSION.jar" -C "$TMP/classes" .
rm -rf "$TMP"
echo "$OUT/station-announcer-api-$VERSION.jar"
