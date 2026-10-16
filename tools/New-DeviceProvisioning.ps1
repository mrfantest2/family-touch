#requires -Version 7
[CmdletBinding()]
param([Parameter(Mandatory)][string]$RequestFile,[Parameter(Mandatory)][string]$OutputFile,[ValidateRange(60,3600)][int]$LifetimeSeconds=600)
$ErrorActionPreference='Stop'
# Read interactively so the credential never appears in command history or process arguments.
$request=Get-Content -LiteralPath $RequestFile -Raw | ConvertFrom-Json
if($request.v -ne 1){throw 'Unsupported request'}
$public=[Convert]::FromBase64String($request.publicKey)
$device=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($public)).ToLowerInvariant()
if($device -cne $request.device){throw 'Device request fingerprint mismatch'}
$secure=Read-Host 'Single-use, non-ephemeral Tailscale auth key (never a reusable key)' -AsSecureString
$pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
 $key=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
 if($key -notmatch '^tskey-auth-' -or $key.Length -gt 150){throw 'Invalid auth key'}
 $body=@{device=$device;expires=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()+$LifetimeSeconds;key=$key}|ConvertTo-Json -Compress
 $rsa=[Security.Cryptography.RSA]::Create()
 $read=0
 $rsa.ImportSubjectPublicKeyInfo($public,[ref]$read)
 $clear=[Text.Encoding]::UTF8.GetBytes($body)
 # RSA3072 OAEP SHA1 is supported by Android Keystore API29+. Encrypted payload is <=342 bytes.
 $encrypted=$rsa.Encrypt($clear,[Security.Cryptography.RSAEncryptionPadding]::OaepSHA1)
 [IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputFile),[Convert]::ToBase64String($encrypted))
 Write-Output "Encrypted for device $device; expires in $LifetimeSeconds seconds. Deliver privately, then delete."
} finally {
 [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
 if($clear){[Array]::Clear($clear,0,$clear.Length)}
 $key=$null;$body=$null
 if($rsa){$rsa.Dispose()}
 $secure.Dispose()
}
