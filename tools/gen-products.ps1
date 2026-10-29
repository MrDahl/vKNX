# Generates the ETS product XML files into products\ (then convert to .knxprod with ETS tools, see docs\ets-test-guide.md)
Set-Location (Split-Path $PSScriptRoot -Parent)
$B = 'build'
java -p "$B/core;$B/device;$B/server;$B/vknx" -m org.vknx/org.vknx.tools.ProductXmlGenerator products
