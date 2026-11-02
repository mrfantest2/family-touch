# Family Touch v0.3.18 — Persistent background updater

**Scope:** Embedded Android app only; Admin app, KDE integration, VPN settings, existing chat history, PIN and account state unchanged.

## Problem
Previous Compose updater used rememberCoroutineScope(), which was tied to the chat/PIN-gated composition and could be cancelled when backgrounded or locked. An earlier 146MB self-update failed with Connection reset at 11%. The installer used a cache FileProvider path; background downloads now need durable storage.

## Implementation
- AndroidX WorkManager CoroutineWorker started by the user using unique work per release with `ExistingWorkPolicy.KEEP`; no duplicate work.
- Job runs independently of UI/PIN; Android notification reports progress and stays visible while downloading.
- WorkManager persists jobs across app process termination and normal reboots. Android may defer based on network, battery or OS quotas. Force-stop/uninstall are not promised to continue immediately.
- 1MiB HTTP range requests; validates 206 Content-Range and length; retries chunks and backs off indefinitely on repeated transient network failures.
- Partial APKs under app private **filesDir/updates** survive cache clearing and process restarts; truncates incomplete chunks on restart and persists complete chunks to disk.
- Checks final SHA-256, APK signing certificate, package name and version before marking ready.
- Notification opens app. After optional app PIN unlock, user explicitly confirms Android's installation prompt. Installation is **never silently bypassed**.
- FileProvider now includes `<files-path name="verified_updates" path="updates/" />`.
- Optional `mirrorUrls` supported by the signed manifest; downloader alternates approved HTTPS sources and still verifies the same hash. The existing origin remains the only automated mirror configured until an authorized, byte-range-capable Google Drive URL is proven.

## Google Drive
Verified a private recovery copy `FamilyTouch-Embedded-v0.3.18-code35.apk` at `https://drive.google.com/file/d/1I5WlJsrcxO-n94dDy_g9kNNtcZWL-yIq/view`. Size 146,853,487 bytes and locally calculated SHA-256 `75547F7575D47C1C4727813AA7B5BF255DA99B4488BAB964C5C493646418470F`, copied to Google Drive via desktop mount. Google Drive connector confirms the file metadata exists and is not shared. Private Drive viewer URL is **not** installed as automatic mirror.

## Verification checkpoints
- Signed Embedded code 35 APK and QA code 35 APK built successfully; QA unit tests passed.
- Signature fingerprint: `8b028e6a167011f2d070c333000e23cda90dbc6d6948cc71d83894da4a33ee87`.
- MASTER-PC emulator install PASS, app process starts. On-screen Android System UI temporarily froze; subsequent UI check passed.
- OP7 ADB available intermittently over Tailscale, but OP7 battery 9% and not charging at checkpoint; avoid large installation until charged.
- S25 Remote Desktop Commander online, ADB wireless session unavailable at checkpoint.
- **Not yet established:** on-device actual background-to-completion/lock/restart test, an automatic Google Drive fallback, and successful signed in-place physical deployment.

## Acceptance needed before production rollout
1. Install code35 in place on charged OP7, confirm package/version and no data loss; fallback S25 if unavailable.
2. Serve a newer signed test APK. Enqueue background download, lock phone, leave app, force process death (not Android force-stop), restore network, check resume/progress, verify hash/signature.
3. Complete Android installer approval and recheck package version, messages and PIN.
4. Verify Google Drive mirror only when authorized authenticated/range-capable delivery is available.
5. Keep PR draft and production feed unchanged until successful tests.

## OP7 physical QA extension — v0.3.19 (code 36), November 2

- OP7 connected through authenticated ADB over 100.85.113.35:5555; device battery reached 100% and remained charging.
- Production Embedded app in-place upgrade code35 → code36 PASS using signed `adb install -r --no-streaming`: 146,853,491 bytes, package `win.fantest.familytouch.embedded`, original firstInstallTime `2026-10-20 01:49:03` unchanged. App process start observed; no uninstall/data clear.
- Signing certificate SHA-256 `8b028e6a167011f2d070c333000e23cda90dbc6d6948cc71d83894da4a33ee87`; code36 APK SHA-256 `56E2AE08D43A0F265CA50E8EF6D65AF6DC8E21023632DA6B582EB3DF7C877452`.
- QA package `win.fantest.familytouch.qa` with instrumentation tests, not the installed Embedded production app, verified a full background transfer of code33 APK: 146,031,235 bytes, exact published SHA `a17f668c866042ae117fec5d060d7fbbfaba85a605d84689392738ab48bbcd62`.
- Process crash recovery on OP7: QA process PID 22590 induced crash with 76,546,048 bytes persisted; new PID 22853 resumed and reached 130,023,424 bytes without reopening the app; QA transfer subsequently reached full length.
- Temporary QA-only PIN lock regression PASS: work identity persisted; WorkManager state not CANCELLED; temporary QA PIN disabled in test `finally` block. User Embedded PIN unchanged.
- Deterministic wrong-signer safeguard: complete production-signed APK downloaded by QA package must be rejected. New worker returns `Result.failure`, deletes invalid partial. Instrumentation `rejectsWrongSignerPermanentlyInQa` PASS on OP7 after v36 QA install; all QA temporary partials and probe jobs cleaned.
- Kotlin QA unit tests, signed Embedded/QA APK, APK signer and instrumentation builds PASS.
- Private Google Drive desktop mount backup `FamilyTouch-Embedded-v0.3.19-code36.apk`: size 146,853,491 bytes and checksum equal source; connector-side indexing/link verification pending.
- S25 KDE Connect shared code36 APK successfully using authorized paired device `5ea10cb373be47aa94df58dc17164f3a`. S25 RDC offline and ADB port refused; code36 S25 installation **not verified**.
- Production update manifest still advertises Embedded code33. No production feed/Cloudflare routing or Admin modifications in this checkpoint. Therefore a signed code35→36 *in-app* update and Android installer confirmation remain untested.
- Reboot persistence and authenticated Google Drive automatic range mirror remain untested/unconfigured; private Drive recovery backup is not a public or automatic mirror.
- Keep PR draft and complete signed end-to-end update test before publishing production feed. KDE integration is out of scope.
