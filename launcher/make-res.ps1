# Compiles vknx.rc (icons, manifest, version) into vknx.res with the Windows SDK resource compiler.
# Usage: .\make-res.ps1 -Version 0.3.1
param([string]$Version = "0.0.0")
$ErrorActionPreference = 'Stop'
$rc = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin" -Recurse -Filter rc.exe -ErrorAction SilentlyContinue |
  Where-Object { $_.FullName -match '\\x64\\' } | Sort-Object FullName -Descending | Select-Object -First 1
if (-not $rc) { throw 'rc.exe (Windows SDK) not found' }
$v = ($Version -replace '^v', '').Split('.') + @('0', '0', '0')
Push-Location $PSScriptRoot
try {
  "#define VER_MAJOR $($v[0])`n#define VER_MINOR $($v[1])`n#define VER_PATCH $($v[2])`n#define VER_STRING `"$Version`"`n" | Set-Content version.h -Encoding ascii
  & $rc.FullName /nologo /fo vknx.res vknx.rc
  if ($LASTEXITCODE) { throw 'rc.exe failed' }
  Get-Item vknx.res | Select-Object Name, Length | Format-Table | Out-String | Write-Host
} finally { Pop-Location }
