# Converts the vKNX product XML files to .knxprod with OpenKNXproducer (needs ETS and OpenKNXproducer installed).
# Usage: .\make-knxprod.ps1                       (finds OpenKNXproducer in %USERPROFILE%\bin, next to the script or in PATH)
#        .\make-knxprod.ps1 -Producer "C:\path\OpenKNXproducer.exe"
# Works with ETS 6.4 (Build 8718) for the products tried so far - send the console output back if something fails.
param([string]$Producer = "")
if (-not $Producer) {
  $cands = (Get-ChildItem -Path "$env:USERPROFILE\bin" -Filter "OpenKNXproducer*.exe" -ErrorAction SilentlyContinue | % FullName) + (Get-ChildItem -Path $PSScriptRoot -Recurse -Filter "OpenKNXproducer*.exe" -ErrorAction SilentlyContinue | % FullName)
  $Producer = ($cands | Where-Object { Test-Path $_ } | Select-Object -First 1)
  if (-not $Producer -and (Get-Command OpenKNXproducer -ErrorAction SilentlyContinue)) { $Producer = "OpenKNXproducer" }
  if (-not $Producer) { Write-Host "OpenKNXproducer not found. Pass the path with -Producer ""C:\path\OpenKNXproducer-x64.exe""" -ForegroundColor Red; return }
}
Write-Host "Using: $Producer"
$dir = if (Test-Path "$PSScriptRoot\products") { "$PSScriptRoot\products" } else { "$PSScriptRoot\..\products" }
Set-Location $dir
foreach ($x in Get-ChildItem vknx-*.xml) {
  $out = [IO.Path]::ChangeExtension($x.Name, 'knxprod')
  Write-Host "=== $($x.Name) -> $out"
  & $Producer knxprod $x.Name -o $out
  Write-Host "exit code: $LASTEXITCODE"
}
Get-ChildItem *.knxprod | Format-Table Name, Length
