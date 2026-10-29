#!/usr/bin/env bash
# Starts vKNX (KNXnet/IP server + virtual devices + control panel). Extra arguments are passed to org.vknx.Main.
cd "$(dirname "$0")/.."
B=build
exec java -p $B/core:$B/device:$B/server:$B/vknx -m org.vknx/org.vknx.Main "$@"
