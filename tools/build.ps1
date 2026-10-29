# vKNX build on Windows (PowerShell). Needs JDK 21 (javac) and git on PATH.
# NOTE: written from the Linux script; NOT yet run on Windows.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$S = 'third_party/src'; $B = 'build'

# 1. fetch pinned Calimero sources
New-Item -ItemType Directory -Force $S | Out-Null
foreach ($line in Get-Content third_party/PINS.txt) {
  if ($line -match '^\s*#' -or $line.Trim() -eq '') { continue }
  $name, $url, $sha = $line -split '\s+'
  $dir = "$S/$name"
  if ((Test-Path "$dir/.git") -and ((git -C $dir rev-parse HEAD) -eq $sha)) { Write-Host "$name already at $sha"; continue }
  if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
  New-Item -ItemType Directory -Force $dir | Out-Null
  git -C $dir init -q; git -C $dir remote add origin $url
  git -C $dir fetch -q --depth 1 origin $sha; git -C $dir checkout -q FETCH_HEAD
}

# 2. compile
function Src($p) { (Get-ChildItem -Recurse -Filter *.java $p).FullName }
if (Test-Path $B) { Remove-Item -Recurse -Force $B }
New-Item -ItemType Directory -Force "$B/core","$B/device","$B/server","$B/vknx","$B/test" | Out-Null
javac -nowarn -d "$B/core" (Src "$S/calimero-core/src");                                   if ($LASTEXITCODE) { throw 'core' }
Copy-Item "$S/calimero-core/resources/properties.xml" "$B/core/"
javac -nowarn -d "$B/device" -p "$B/core" (Src "$S/calimero-device/src/main");              if ($LASTEXITCODE) { throw 'device' }
javac -nowarn -d "$B/server" -p "$B/core;$B/device" (Src "$S/calimero-server/src");         if ($LASTEXITCODE) { throw 'server' }
javac -d "$B/vknx" -p "$B/core;$B/device;$B/server" (Src 'core/src');                       if ($LASTEXITCODE) { throw 'vknx' }
Copy-Item -Recurse -Force core/resources/* "$B/vknx/"
javac -d "$B/test" -p "$B/core;$B/device;$B/server;$B/vknx" --add-modules org.vknx (Src 'core/test'); if ($LASTEXITCODE) { throw 'tests' }
Write-Host "build ok -> $B"
