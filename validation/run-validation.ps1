$ErrorActionPreference = 'Stop'
$portRoot = Split-Path $PSScriptRoot -Parent
$runPath = Join-Path $PSScriptRoot 'run'
New-Item -ItemType Directory -Path $runPath -Force | Out-Null
Set-Content -LiteralPath (Join-Path $runPath 'eula.txt') -Value 'eula=true'
Set-Content -LiteralPath (Join-Path $runPath 'server.properties') -Value @('online-mode=false','server-ip=127.0.0.1','server-port=25589','view-distance=2','simulation-distance=2')
$serverResult = Join-Path $runPath 'inventory-server-result.txt'
$clientResult = Join-Path $runPath 'inventory-client-result.txt'
if (Test-Path -LiteralPath $serverResult) { Remove-Item -LiteralPath $serverResult }
& (Join-Path $portRoot 'gradlew.bat') -p $PSScriptRoot runServer --console=plain
if ($LASTEXITCODE -ne 0 -or (Get-Content -LiteralPath $serverResult -Raw) -notmatch '^PASS') { throw 'Real-menu tests failed' }
$clientWorld = Join-Path $runPath 'saves/InventoryTest'
if (-not (Test-Path -LiteralPath $clientWorld)) {
 New-Item -ItemType Directory -Path (Split-Path $clientWorld -Parent) -Force | Out-Null
 Copy-Item -LiteralPath (Join-Path $runPath 'world') -Destination $clientWorld -Recurse
}
if (Test-Path -LiteralPath $clientResult) { Remove-Item -LiteralPath $clientResult }
& (Join-Path $portRoot 'gradlew.bat') -p $PSScriptRoot runClient --console=plain
if ($LASTEXITCODE -ne 0 -or (Get-Content -LiteralPath $clientResult -Raw) -notmatch '^PASS') { throw 'Client/network tests failed' }
Write-Host 'Passed Inventory Tweaks menu and client tests.'
