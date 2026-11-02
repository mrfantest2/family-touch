# Family Touch — Background Self-Updater v0.3.18 (code 35)

## Scope
Embedded private-VPN Android application only. No Admin APK, public/no-VPN relay or KDE integration changes.

## Resilience
- Background `CoroutineWorker` scheduled with Android WorkManager unique per version, connected-network constraints and exponential retry. Execution is independent of Compose, chat, Activity, and PIN-gated screen.
- WorkManager foreground notification (`dataSync` type) shows progress. On completion it displays a notification to open Family Touch and **unlock if necessary**. Android's package installer still requires confirmation; no silent install or bypass of local PIN.
- Resumable `filesDir/updates/` partial files named by version plus SHA-256 prefix. Each 1 MiB byte range is committed to disk and the interrupted range is discarded on process death. Offline/network loss is retried when conditions allow. Service and app process restarts do not erase downloaded chunks; Android force-stop may defer jobs until app is launched again.
- SHA-256, exact byte count, per-range Content-Range, archive package, version code, and matching existing APK signing certificate are all checked before installation.

## Two HTTP paths and Drive
- Primary public HTTPS origin: `https://family-updates.fantest.win/apks/embedded.apk`.
- Beta test primary: `https://family-updates.fantest.win/apks/embedded-beta-v35.apk`.
- Google Drive-staged beta mirror: `https://family-updates.fantest.win/apks/drive/embedded-beta-v35.apk`.
- Google Drive cloud backup: `Fantest-Project-Backups/FamilyTouch/Updates/FamilyTouch-Embedded-v35-3339B1F1.apk`.
- All staged copies measured **146,853,459 bytes** and SHA-256 `3339b1f1c4de7bce8c5fc2e710848c4081c5eec23586d2753fd449402f3e6885`.
- The Drive-staged HTTPS mirror currently uses a server-side file copied from the Google Drive mounted folder. It is not a fully independent public Google Drive API transfer, and both beta HTTPS paths currently share one Cloudflare-hosted origin. A direct private Drive link is not used by the app. The `mirrorUrls` signed-manifest field is supported but should include only externally validated endpoints.
- Security: both sources are checked against the same manifest SHA-256 and installed signing certificate. The source cannot override release integrity.

## Verification and release gates
- MASTER-PC Gradle compileEmbeddedKotlin PASS, testQaUnitTest PASS, signed assembleEmbedded PASS.
- AVD `FamilyConnectVPNQA_API36`: signed QA APK code35 installed and MainActivity launched without observed crash.
- Beta routes: both public HTTPS endpoints returned 206 with expected Content-Range at tested offsets.
- S25: beta APK downloaded in full and verified SHA-256 in Download. Install intent dispatched, but S25 package-manager readback still reported code32 as of the last check. Requires Android confirmation.
- OP7: Tailscale/ADB and RDC offline at checkpoint, no version change claimed.
- Production update feed remains code33 until the user-device acceptance gate is satisfied.
- Previous origin server backed up to `C:\ProgramData\FamilyTouchUpdates\server.py.backup-beta-20261102191344`; scheduled updater service running. Admin manifest remains available.

## Implementation
`FamilyUpdates.kt`: manifest parsing, mirror allowlist, verified finished APK, WorkManager enqueue, install intent.
`FamilyUpdateWorker.kt`: persistent foreground network download and verification.
`AndroidManifest.xml`: dataSync FGS declarations and merged WorkManager SystemForegroundService.
`app/build.gradle.kts`: work-runtime-ktx 2.9.1 and code35.
`scripts/updates_server_range.py`: allowlisted range server with beta and Drive-staged mirror paths.
`BackgroundUpdateUrlPolicyTest.kt`: allowlist and version-specific job keys.

## Limitations
A persistent worker does not provide silent Android installation or permit bypassing system install prompts. Google Drive cloud storage backup has been verified; a directly public authenticated-free Google Drive binary download is not established. Device UI background/crash/reboot acceptance of Worker requires installing code35 first, then testing a newer signed build.
