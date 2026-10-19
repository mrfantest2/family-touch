param([string]$Root='C:\ProgramData\FamilyTouchBroker')
$ErrorActionPreference='Stop'
$migration=Join-Path $Root 'migration'
$standby=Join-Path $Root 'standby'
$sshKey=Join-Path $env:USERPROFILE '.ssh/agentbox_project_migration'
$sshArgs=@('-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10','-i',$sshKey)
& ssh @sshArgs agent@100.65.177.100 'FAMILY_TOUCH_STATE_KEY_FILE=$HOME/.local/share/family-touch/state.key FAMILY_TOUCH_TRANSFER_KEY_FILE=$HOME/.local/share/family-touch/migration/transfer.key $HOME/.local/share/family-touch/family-touch-broker -state-dir $HOME/.local/share/family-touch/state -export-transfer $HOME/.local/share/family-touch/migration/standby.transfer'
if($LASTEXITCODE -ne 0){throw 'Primary encrypted export failed; standby left unchanged'}
$transfer=Join-Path $migration 'standby.transfer'
& scp @sshArgs -q agent@100.65.177.100:.local/share/family-touch/migration/standby.transfer $transfer
if($LASTEXITCODE -ne 0){throw 'Encrypted standby transfer failed'}
$snapshot=Join-Path $standby ([DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff'))
$env:FAMILY_TOUCH_TRANSFER_KEY_FILE=Join-Path $migration 'transfer.key'
& (Join-Path $migration 'family-touch-broker.exe') -state-dir $snapshot -import-transfer $transfer
if($LASTEXITCODE -ne 0){throw 'Standby DPAPI import failed'}
[IO.File]::WriteAllText((Join-Path $Root 'standby-latest.txt'),$snapshot)
# Retain 48 protected snapshots; deletion is confined to verified numeric snapshot directories.
$resolvedRoot=[IO.Path]::GetFullPath($standby)+[IO.Path]::DirectorySeparatorChar
Get-ChildItem -LiteralPath $standby -Directory | Where-Object Name -match '^\d{17}$' |
 Sort-Object Name -Descending | Select-Object -Skip 48 | ForEach-Object {
    $target=[IO.Path]::GetFullPath($_.FullName)
    if(-not $target.StartsWith($resolvedRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe backup path'}
    Remove-Item -LiteralPath $target -Recurse -Force
 }
Write-Output 'AgentBox registry backed up as private Windows DPAPI standby state.'
