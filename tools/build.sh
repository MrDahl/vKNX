#!/usr/bin/env bash
# Builds Calimero (pinned sources) and vKNX with a plain JDK 21 - no Maven/Gradle needed.
set -euo pipefail
cd "$(dirname "$0")/.."
tools/fetch-calimero.sh
S=third_party/src; B=build
rm -rf "$B"; mkdir -p "$B"
javac -nowarn -d $B/core   $(find $S/calimero-core/src -name '*.java') 2>&1 | grep -v '^Note' || true
cp third_party/src/calimero-core/resources/properties.xml $B/core/
javac -nowarn -d $B/device -p $B/core $(find $S/calimero-device/src/main -name '*.java') 2>&1 | grep -v '^Note' || true
javac -nowarn -d $B/server -p $B/core:$B/device $(find $S/calimero-server/src -name '*.java') 2>&1 | grep -v '^Note' || true
javac -d $B/vknx -p $B/core:$B/device:$B/server $(find core/src -name '*.java')
cp -r core/resources/* $B/vknx/
mkdir -p $B/test
javac -d $B/test -p $B/core:$B/device:$B/server:$B/vknx --add-modules org.vknx $(find core/test -name '*.java')
echo "build ok -> $B"
