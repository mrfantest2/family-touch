# Family Touch — clear current build and published update status

Version-status candidate: Embedded v0.3.21, versionCode 38 (branch `feature/family-touch-version-status-20261102`, based on v0.3.20 code37). Existing Admin, embedded VPN, enrollment, chat data and update download logic unchanged.

## User-visible behavior
- Settings displays `App version — v0.3.21 (build 38)` even when offline, alongside the full installed variant string `0.3.21-version-status-embedded`.
- Tapping `Check for updates` always displays the locally installed version, including on network errors.
- When the HTTPS manifest advertises a release newer than the installed one, the dialog displays **installed** and **available** version/build values before downloading.
- When no newer release exists, the dialog shows **installed** and **published** version/build. The status distinguishes a matching release from a client newer than the deployment feed instead of wrongly claiming everything is current.
- The update feed's metadata is still validated (package name, HTTPS source, signature on downloaded APK). Existing in-app downloads, work resumption, PIN, and installer confirmation paths are unchanged.

## Known release-feed condition
As of the audit on November 2, 2026, the production HTTPS manifest advertises Embedded v0.3.16 (build 33), whereas OP7 has v0.3.20 (build 37) prior to this feature's installation. Therefore the correct new dialog after upgrade to code38 is `Installed v0.3.21 (build 38)`, `Published v0.3.16 (build 33)`, `Installed version is newer than the published update feed`. Do not modify the feed without the normal release acceptance.

## Validation checklist
- Unit tests: short version label, equality, older-feed, newer-feed scenarios.
- Signed APK must use original Family Touch signing identity; install OP7 in place via `adb install -r`, never clear app data.
- Check Settings version label, trigger the update check, and inspect the status dialog using Android UI on OP7, if unlocked.
- S25 delivery is separate from installation; do not claim installation merely because KDE Connect transferred the APK.

## Verified release checkpoint — November 2, 2026

- MASTER-PC signed Embedded v0.3.21 / code38 and separate QA APK successfully built. All **58/58 JVM tests passed** (22 suites, 0 errors/failures/skips), including `FamilyVersionLabelsTest` comparisons for installed/published code.
- Embedded APK length **146,951,791 bytes**, SHA-256 `AAA055971C0C7489AB0438FCAA18B3E7A8D5170961757D3131273487E920B7D9`. Original release signing certificate SHA-256 `8b028e6a167011f2d070c333000e23cda90dbc6d6948cc71d83894da4a33ee87`.
- OP7 authenticated ADB deployment using `install -r --no-streaming` succeeded. Device reports `versionCode=38`, `versionName=0.3.21-version-status-embedded`; its `firstInstallTime=2026-10-20 01:49:03` remains unchanged. App launched and process observed (PID 15014). No uninstall or clear-data operation.
- OP7 was on its Android lock screen during accessibility inspection. Live visual acceptance of the Settings label/update status dialog on OP7 remains **unverified**, despite successful compilation, tests and in-place installation.
- S25 KDE Connect share succeeded for `FamilyTouch-Embedded-v0.3.21-code38.apk`. This proves send, **not installation** on S25. S25 native RDC currently offline.
- Private Google Drive desktop mount copy verified byte-for-byte by SHA-256, matching the signed APK. Google Drive index/link verification to follow.
- Live production manifest **still code33**, intentionally unchanged. New code38 UI correctly calls that feed older than the installed build, rather than declaring a new update available. Existing updater installation and background worker unchanged.
- GitHub source changes are isolated in the dedicated feature branch and do not modify production main, Admin, updater ingress, or chat data.
