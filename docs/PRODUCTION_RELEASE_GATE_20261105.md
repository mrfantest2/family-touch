# Family Touch — production release gate (2026-11-05)

**Status: PRODUCTION HOLD.** The verified signed build 43 is a release candidate. Neither Redmi nor OP7 has a verified build-43 installation. No production feed or protected main branch release was performed.

## Proven source and binary integrity

- Private repository: mrfantest2/family-touch, draft PR #14.
- Main baseline SHA: 956727b04c12740a39a7063b06dae8d3212c7b7c.
- Feature branch: feature/family-touch-map-live-location-20261102; signed build version source committed at b360d682040d3ec1adb5fbd83a490e84faa0d856; prior QA checkpoint ca6106cbc1600c8ce492e790865337aeb6539d0d.
- The main baseline is an ancestor of the feature branch (0 behind, 29 ahead in the audit). PR #14 currently targets an intermediate feature branch rather than main; verify the intended merge chain before promotion.
- Candidate: win.fantest.familytouch.embedded, versionCode 43, versionName 0.3.26-route-preview-embedded, 147100478 bytes, SHA-256 9bb79ef9727fc302308d6c1972c59608ae8919abac07bbfc2db3ab685e8cc010.
- APK signature verified with apksigner; certificate SHA-256 8b028e6a167011f2d070c333000e23cda90dbc6d6948cc71d83894da4a33ee87, identical to the installed build-42 signing identity.
- SHA-verified signed APKs for builds 42 and 43 are preserved outside the Git checkout on MASTER-PC. No signing secrets committed or printed.
- Embedded unit tests: 70/70 across 25 suites, zero failures/skips. Android lint: PASS.

## Emulator acceptance

- Android 16 emulator-5554: separate QA package win.fantest.familytouch.qa, versionCode 43, installed successfully with matching instrumentation APK.
- First instrumentation run: 5 passed, 3 failed because tests shared one WorkManager job that the cleanup test cancelled, and the VPN test asserted an obsolete external-mode selection. No production-code change was made in response.
- Tests revised to isolate WorkManager jobs, avoid download activity to the live updater, restore QA PIN state, and check the actual built-in-only mode contract. Revised run: 8/8 PASS.
- Added independent wrong-signer negative test: a disposable valid QA APK with the same package and version but a different signing certificate was staged only inside the emulator QA package, rejected by FamilyUpdates.verifyPackageAndSigner, then deleted. Temporary test signing key and source artifact also removed.
- Final instrumentation run: **9/9 PASS**, zero failures. Raw log saved outside Git in C:\Khalilamily-touch\safe-build-backups\qa-evidenceamilytouch-build43-instrumentation-foreign-signer.txt.
- The wrong-signer test requires a separately pre-staged fixture; a CI run without that fixture may skip that one test. CI should generate a disposable test fixture before counting it as covered.

## Physical device acceptance still missing

| Gate | Latest verified status | Release condition |
| --- | --- | --- |
| OnePlus 7 | Build 42; ADB route through Agentbox; latest battery check 3%, unplugged. Streamed update failed and nonstreaming transfer stalled; no reinstall or data loss. | Recharge and complete signed in-place build-43 install, verify package and retained firstInstallTime |
| Redmi Pad 2 | Build 42; complete signed build-43 APK staged in Termux and checksum matched. Installer opened but Android Update/Install not approved. | Owner confirms update; verify code 43 and installed APK digest |
| Redmi silent mode | Media 0/15, ringtone 8/15, notifications 8/15. Unprivileged terminal cannot change protected audio controls. | Owner selects Silent and sets ringtone/notification sliders to zero |
| OP7 silent mode | Do Not Disturb 2; earlier audio streams at zero, not freshly rechecked because of low battery | Verify after charging |
| Family directory | Old build-42 UI showed unavailable; new route fix is code-level tested only | Both updated phones successfully register/discover over active VPN |
| Authenticated messaging | TCP port 48321 reachable; no actual paired message/receipt proven | Explicit pairing; synthetic messages/receipts both ways |
| Calls | Call controls displayed, but two-device call lifecycle not verified | Consented audio/video ring, connect, mute and hangup |
| Opt-in location | Protocol and UI policy unit coverage only; no real location transmitted | Explicit consent: map tiles, pin drag, 15m/1h/8h start/stop/expiry and reconnect behavior |
| Production feed | Unchanged by this QA continuation | Promote only after physical acceptance and staged release checks |

## Deployment and recovery controls

1. Leave production feed, main branch and PR draft status unchanged until physical acceptance evidence exists.
2. Recheck source SHA, APK hash, installed signer/version and preserved app data; never uninstall to bypass a signing or installer failure.
3. Review PR base ancestry and the intended protected-branch promotion; rerun CI before release.
4. Publish a signed manifest only after staged verification, monitor device installs and delivery regressions.
5. Android normally rejects an in-place versionCode downgrade from 43 to 42. A rollback should restore known-good code in a newly signed APK with a *higher* versionCode, then be separately validated before distribution.

**No production readiness claim is made from emulator-only acceptance.**
