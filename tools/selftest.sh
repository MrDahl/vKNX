#!/usr/bin/env bash
# Runs the backend self-test (mini-ETS, in-process). NOT an ETS compatibility test.
# Usage: tools/selftest.sh [SelfTest|TunnelTest|TunnelLineTest|ProductsTest|TopologyTest|CouplerTest|AppTest|CrcCrossCheck]
set -euo pipefail
cd "$(dirname "$0")/.."
B=build
T=${1:-SelfTest}
java -p $B/core:$B/device:$B/server:$B/vknx --patch-module org.vknx=$B/test \
  --add-modules org.vknx --add-reads org.vknx=ALL-UNNAMED -m org.vknx/org.vknx.$T
