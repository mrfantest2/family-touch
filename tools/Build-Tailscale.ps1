[CmdletBinding()]
param([Parameter(Mandatory)][string]$UpstreamPath,[string]$Targets='android/arm64,android/amd64')
$ErrorActionPreference='Stop'
$expected='264102cc702fbd844e900227ee02e5cbb4d7f801'
$actual=git -C $UpstreamPath rev-parse HEAD
if($actual -ne $expected){throw "Expected upstream $expected"}
if(git -C $UpstreamPath status --porcelain){throw 'Upstream checkout must be clean'}
Push-Location $UpstreamPath
try {
 $env:GOTOOLCHAIN='local'
 gomobile bind -target $Targets -androidapi 29 -tags ts_omit_cachenetmap -ldflags '-s -w' -o android/libs/libtailscale.aar ./libtailscale
 if($LASTEXITCODE -ne 0){throw 'gomobile failed'}
 Copy-Item android/libs/libtailscale.aar (Join-Path $PSScriptRoot '../app/libs/libtailscale.aar')
} finally {Pop-Location}
