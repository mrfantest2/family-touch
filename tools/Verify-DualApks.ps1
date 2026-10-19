param([Parameter(Mandatory=$true)][string]$Sdk, [string]$BuildTools='36.0.0')
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root=Split-Path $PSScriptRoot -Parent
$certificate='8b028e6a167011f2d070c333000e23cda90dbc6d6948cc71d83894da4a33ee87'
foreach($variant in @('embedded','external')) {
    $apk=Join-Path $root "app/build/outputs/apk/$variant/app-$variant.apk"
    if(-not (Test-Path -LiteralPath $apk)) { throw "Missing $variant APK" }
    $signer=& (Join-Path $Sdk "build-tools/$BuildTools/apksigner.bat") verify --print-certs $apk
    if($LASTEXITCODE -ne 0 -or ($signer -join "`n") -notmatch $certificate) { throw "Invalid $variant signature" }
    $manifest=& (Join-Path $Sdk "build-tools/$BuildTools/aapt.exe") dump xmltree $apk AndroidManifest.xml
    if($LASTEXITCODE -ne 0) { throw "Cannot inspect $variant manifest" }
    $archive=[IO.Compression.ZipFile]::OpenRead($apk)
    try { $go=@($archive.Entries | Where-Object FullName -match '^lib/[^/]+/libgojni\.so$').Count -gt 0 }
    finally { $archive.Dispose() }
    $service=($manifest -join "`n") -match 'win\.fantest\.familytouch\.FamilyVpnService'
    $vpnPermission=($manifest -join "`n") -match 'BIND_VPN_SERVICE|FOREGROUND_SERVICE_SYSTEM_EXEMPTED'
    if($variant -eq 'external' -and ($go -or $service -or $vpnPermission)) { throw 'External APK contains embedded VPN components' }
    if($variant -eq 'embedded' -and (-not $go -or -not $service)) { throw 'Embedded APK is missing its engine/service' }
    $badging=& (Join-Path $Sdk "build-tools/$BuildTools/aapt.exe") dump badging $apk
    if($LASTEXITCODE -ne 0 -or $badging[0] -notmatch "name='win.fantest.familytouch.$variant'") { throw "Unexpected $variant package" }
    [pscustomobject]@{Variant=$variant; Bytes=(Get-Item $apk).Length; SHA256=(Get-FileHash $apk -Algorithm SHA256).Hash; EmbeddedEngine=$go; VpnService=$service}
}
