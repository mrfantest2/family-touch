# Family Touch — Redmi Pad 2 ↔ OnePlus 7 QA checkpoint (2026-11-05)

Source audit: draft PR #14, branch `feature/family-touch-map-live-location-20261102`; original shared HEAD `27201968bc60760742298572cf3f99068cee1412`, clean before this checkpoint.

## Physical device and transport evidence

- Redmi Pad 2: Xiaomi model 25040RP0AG, Android 15, signed `win.fantest.familytouch.embedded` build 42 installed and MainActivity launch accepted. Installed base.apk SHA-256 `8461bdeff00f47d82d448ec0c3f0c97314832c505d949ef9eb986edb35763ad5`; identical to verified existing release candidate. No uninstall or clear-data.
- OnePlus 7: GM1903, Android 11, same build 42; authorized ADB over Agentbox and standalone Tailscale. Family Touch MainActivity launched and process retained. Contacts/Settings tabs rendered on OP7.
- Both currently route via the standalone Tailscale VPN. Direct TCP port 48321 accepts connections in both directions between Redmi and OP7 over the tailnet, and from MASTER-PC to Redmi. A TCP accept is not proof of an authenticated Family Touch pairing or message/call delivery.
- OP7's earlier embedded node is offline; OP7's current tun1 address is its standalone Tailscale identity. Do not treat an Android connected VPN interface as proof that the embedded Family Touch node is online.
- On OP7, current contact preview states were offline; no confirmed Redmi ↔ OP7 paired identity or receiving client session. No test message or call was sent.

## Silent-mode evidence

- OP7: Android Zen/DND setting 2; prior audio dump showed system, ring, music, alarm, notification streams muted at 0. Voice-call stream has a nonzero hardware minimum; no call was placed.
- Redmi: media stream 0/15 verified; ringtone and notification remained 8/15, system 8/15. The unprivileged Termux shell cannot persist changes to protected sound settings. These still require owner UI or an authorized Android debugging/control route. No claim that Redmi is fully muted.

## Source regression fix

- OP7's contact chat-list subtitle exposed the raw `FTLIVE1:` live-location wire marker instead of a readable summary. Source review found `MainActivity.kt` passing `message.text` directly to `PeerRow`.
- Added `ChatPreviewFormatter.kt` so active/stopped/expired location updates appear as human-readable statuses, with malformed protocol markers suppressed. Ordinary text remains unchanged; raw session IDs and coordinates are not shown in contact previews.
- Wired this formatter into chat-list contact rows only. Added five JUnit tests `ChatPreviewFormatterTest.kt` for plain, active, stopped, expired and malformed inputs.
- Fresh MASTER-PC Gradle 8.10.2 `:app:testEmbeddedUnitTest --offline` succeeded: **67 tests across 24 suites, 0 failures, 0 skipped** (up from 62 across 23 before the change).
- Android lint task :app:lintEmbedded also passed. Android Gradle plugin 8.7.3 gives a preexisting compatibility warning for compileSdk 36; no runtime/QA failure attributed to it.

## Remaining gates

- Redmi Android sound settings: mute ringtone and notifications (currently 8/15).
- Complete authorized Family Touch peer invitation/pairing on both devices. Do not expose, read, reuse, or bypass stored pairing secrets.
- End-to-end paired synthetic message delivery, read/delivery receipt, audio and video call setup/teardown, and mute acceptance need both apps' on-device UI/permission approvals.
- Live-location start/stop/expiry requires explicit on-device consent, and is outside this passive QA pass.
- New chat-preview fix is source-only and must not be represented as installed build 42 on either phone. A separately validated signed candidate and in-place upgrade are required for device visual acceptance.

No production merge, app reinstall, permission grant, call, message send, or location transmission was performed during this checkpoint.
## Directory bootstrap failure investigation and source fix

- OP7 Contacts reported Family directory unavailable, retrying automatically. Android logcat reported `FamilyEnrollment: Failed at request: SocketException` (safe class-only log, no credentials).
- MASTER-PC Family Touch broker was running on its expected local listening port; `family-api.fantest.win` was reachable. A deliberately empty unsigned POST to `/api/v1/sync` returned HTTP 400 from both MASTER-PC and OP7, confirming public DNS/TLS/route and server response without using real credentials.
- Android's active VPN interface on OP7 belonged to standalone Tailscale, rather than Family Touch's older embedded node. The current embedded app may reuse an active external tailnet when its own VPN service is inactive.
- Source previously bound **every** public directory HTTPS request to `selectedPhysicalNetwork`, even in that external-VPN fallback state. This is a plausible source of the OP7 socket failure; a device-level resolution has **not** been verified.
- `FamilyDirectory.kt` now routes HTTPS via `URL.openConnection()` when the embedded VPN service is inactive, and continues using `Network.openConnection()` on the physical underlay while the embedded service is active or stopping. Standard TLS hostname/certificate verification remains intact.
- Added pure `DirectoryBootstrapRoute.kt` plus three tests covering external fallback, active embedded service, and stopping embedded service.
- Combined fresh `:app:testEmbeddedUnitTest :app:lintEmbedded --offline` run PASS: **70 tests across 25 suites, 0 failures, 0 skipped; lint BUILD SUCCESSFUL**.
- No signed APK was built or deployed from this remote session because the release-signing environment variables were unavailable. Existing device installations and stored identities remain untouched; do not claim the directory bug is fixed on Redmi or OP7 until a correctly signed APK has been deployed in place and the API sync verified on both devices.
## Signed build 43 candidate and deployment continuation — November 5, 2026

- Development branch commit `b360d682040d3ec1adb5fbd83a490e84faa0d856` bumped versionCode to 43 and versionName to `0.3.26-route-preview` (installed APK suffix `-embedded`).
- Recovered the existing local authorized Family Touch PKCS#12 signer. Its public certificate SHA-256 `8b028e6a167011f2d070c333000e23cda90dbc6d6948cc71d83894da4a33ee87` exactly matches the existing build-42 signing certificate. Signing passwords were loaded only in transient process environment and were not logged or committed.
- First Gradle `:app:packageEmbedded` attempt failed to delete the old APK because a separate SHA-verified v42 backup was reading that file concurrently. After the backup completed, incremental `:app:assembleEmbedded` **BUILD SUCCESSFUL**.
- New signed APK: package `win.fantest.familytouch.embedded`, versionCode 43, versionName `0.3.26-route-preview-embedded`, 147100478 bytes, SHA-256 `9bb79ef9727fc302308d6c1972c59608ae8919abac07bbfc2db3ab685e8cc010`. `apksigner verify --print-certs` exit 0; signing certificate matches the already-installed APK. `aapt2 dump badging` confirmed code/name.
- Re-ran `:app:testEmbeddedUnitTest` and `:app:lintEmbedded` after version bump: BUILD SUCCESSFUL; 25 JUnit suites / 70 tests / 0 failed / 0 skipped, Android lint passed.
- Stored separate SHA-verified build-42 original APK and build-43 signed candidate in MASTER-PC's protected local build-backup directory, outside the Git repository. Neither keystore nor passwords were copied into version control.
- OP7: preflight on OnePlus 7 GM1903 confirmed authenticated ADB, 202GB free storage, build 42 installed and DND enabled. First `adb install -r` streamed attempt failed with no specific installer code. Non-streaming attempt stalled; it was stopped to conserve power when OP7 battery had declined from 35% to about 26%, unplugged. Agentbox read-back confirmed OP7 still on build 42 with DND enabled. No uninstall, data clear, app downgrade or successful build-43 install occurred.
- Redmi Pad 2: connected over Wi-Fi and Tailscale; transferred the signed build-43 APK in resumable compressed 2MiB chunks through a temporary token-protected HTTPS tunnel. Final on-Redmi file size 147100478 and SHA-256 exact match. Android Package Installer intent launched (`termux-open`, exit 0). Subsequent Android package-manager read-back showed versionCode **42**, with the original base APK unchanged; user-visible Update/Install confirmation is still required.
- Removed the temporary transfer tunnel and server after successful checksum verification. Production Family Touch updater and public release channel were not changed.
- Redmi audio read-back: media volume 0/15; ring and notification 8/15. Android's unprivileged Termux connection cannot change those protected streams; tap Silent plus set both volume sliders to zero on the tablet. Alarm has an Android minimum greater than zero and was not modified.
- No test chat messages, calls, location sharing, pairing-secret extraction, new privileged access, or forced installation were performed.

### Unresolved acceptance requirements

- On Redmi, approve the in-place Android **Update / Install** prompt. Verify installed package versionCode 43 and installed base.apk hash after confirmation.
- Recharge OP7 before another large APK transfer; recover the stable ADB transport and perform one in-place build-43 install with SHA and preserved firstInstallTime verification.
- On both devices, verify the family directory API refresh now succeeds under externally owned Tailscale VPN. Then verify authenticated pairing and non-sensitive synthetic peer messaging / call lifecycle, with user-consented actions as applicable.
- Confirm visual chat preview hides raw `FTLIVE1:` payloads on updated devices. Maintain draft PR and production HOLD until actual physical acceptance passes.
## Android 16 isolated emulator production-gate continuation

- QA package build 43 and instrumentation package installed only on emulator-5554.
- Initial 8-test run had three failures caused by shared WorkManager test-job cleanup and obsolete VPN expectations. Corrected tests isolate delayed QA-only work, clean up per test and assert the intended built-in VPN mode. Production application code was unchanged.
- Revised emulator run: 8/8 PASS. Final run with deliberately wrong-signed same-package/version QA fixture: **9/9 PASS**; no test failures. Test signing key and fixture removed afterward.
- Raw 9-test emulator report preserved on MASTER-PC outside the repository. See PRODUCTION_RELEASE_GATE_20261105.md for physical acceptance blockers and release policy.
