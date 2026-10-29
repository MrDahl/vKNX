# Starts vKNX on Windows. Extra args are passed on, e.g.  tools\start.ps1 --port 3671 --web 8080
Set-Location (Split-Path $PSScriptRoot -Parent)
$B = 'build'
java -p "$B/core;$B/device;$B/server;$B/vknx" -m org.vknx/org.vknx.Main @args
