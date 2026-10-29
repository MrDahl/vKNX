# Konverterer vKNX' produkt-XML til .knxprod med OpenKNXproducer (kræver installeret ETS + OpenKNXproducer).
# Brug:  .\make-knxprod.ps1                       (finder OpenKNXproducer i PATH)
#        .\make-knxprod.ps1 -Producer "C:\sti\OpenKNXproducer.exe"
# NOTE: ikke afprøvet mod ETS 6.4 endnu - send konsoludskriften tilbage hvis noget fejler.
param([string]$Producer = "")
if (-not $Producer) {
  $cands = (Get-ChildItem -Path "$env:USERPROFILE\bin" -Filter "OpenKNXproducer*.exe" -ErrorAction SilentlyContinue | % FullName) + (Get-ChildItem -Path $PSScriptRoot -Recurse -Filter "OpenKNXproducer*.exe" -ErrorAction SilentlyContinue | % FullName)
  $Producer = ($cands | Where-Object { Test-Path $_ } | Select-Object -First 1)
  if (-not $Producer -and (Get-Command OpenKNXproducer -ErrorAction SilentlyContinue)) { $Producer = "OpenKNXproducer" }
  if (-not $Producer) { Write-Host "OpenKNXproducer ikke fundet. Angiv sti med -Producer ""C:\sti\OpenKNXproducer-x64.exe""" -ForegroundColor Red; return }
}
Write-Host "Bruger: $Producer"
$dir = if (Test-Path "$PSScriptRoot\products") { "$PSScriptRoot\products" } else { "$PSScriptRoot\..\products" }
Set-Location $dir
foreach ($x in Get-ChildItem vknx-*.xml) {
  $out = [IO.Path]::ChangeExtension($x.Name, 'knxprod')
  Write-Host "=== $($x.Name) -> $out"
  & $Producer knxprod $x.Name -o $out
  Write-Host "exit code: $LASTEXITCODE"
}
Get-ChildItem *.knxprod | Format-Table Name, Length
