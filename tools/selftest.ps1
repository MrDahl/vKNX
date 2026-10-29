# Backend self-test (mini-ETS). NOT an ETS compatibility test.  Usage: tools\selftest.ps1 [SelfTest|TunnelTest|CrcCrossCheck]
Set-Location (Split-Path $PSScriptRoot -Parent)
$B = 'build'; $T = if ($args.Count) { $args[0] } else { 'SelfTest' }
java -p "$B/core;$B/device;$B/server;$B/vknx" --patch-module "org.vknx=$B/test" --add-modules org.vknx --add-reads org.vknx=ALL-UNNAMED -m "org.vknx/org.vknx.$T"
