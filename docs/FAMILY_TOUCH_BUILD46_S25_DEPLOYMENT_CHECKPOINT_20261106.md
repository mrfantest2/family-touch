# Family Touch build 46 — November 6, 2026 deployment checkpoint

## Build provenance
- Android package: `win.fantest.familytouch.embedded`, versionCode 46, versionName `0.3.29-family-globe-icon-embedded`.
- Signed APK: 147198798 bytes; SHA-256 `c5103dc44c72beff42e2aee69e71244095d1bfb99ffa1847c60ef673f0d6fde8`.
- Signer fingerprint matched the previous installed APK. Old signed builds 44 and 45 retained as rollback artifacts outside Git.
- Build 46 compiled; lint passed; embedded unit tests 77/77 PASS across 27 suites.
- Embedded launcher icon resource override corrected to use the family conversation icon.

## Functionality and QA
- Color themes: classic green, purple, pink, blue, red, teal; dark, light, and system display.
- Contact long-press: presence ping, export via Android share chooser, delete local chat after explicit confirmation, six-digit chat lock/removal and help options.
- New globe tab with rotation and registered devices; location pins only from active incoming direct-chat live-location shares. Opening the globe never requests or broadcasts location.
- Chat preview replaces raw attachment identifiers with human-friendly labels.
- OP7 build-45 physical QA: theme changes, globe rotation, five-tab navigation and contact long-press menu verified; no real messages, calls, locations, PIN or deletions initiated. Build-46 icon itself is pending separate physical visual acceptance.

## S25 deployment staging
- S25 currently reported build 44 after Android Package Installer was opened through its authorized Termux remote shell.
- Verified old S25 build-44 APK in Downloads: SHA-256 `8b348d35b25bbd74ff356dfcb5f021836e2cc2b796eb1e1fc7b839f5b1d19ed5`.
- Generated 1,725,151-byte binary patch (SHA-256 `f947988abec3425ab4e167cd7248214ecd2d9056f218459da9835ec9c40a6e25`) on MASTER-PC; reconstructed exact signed APK and verified hash on MASTER-PC and S25.
- Verified staged APK: `/sdcard/Download/FamilyTouch-build46.apk`.
- Android interactive installer opened. S25 Android confirmation and final versionCode 46 verification remain outstanding; wireless ADB transport was unavailable.
- No uninstall, app-data reset, PIN change, location sharing, or messages were performed.

## Gates
- Confirm S25 install approval, installed version and in-place data retention, then visually inspect the updated screens.
- Complete paired chat, delivery receipts and call tests separately before production promotion.
- Protected main branch and production distribution feed remain unchanged.
- No chats, tokens, personal contacts, credentials or temporary transfer URLs are stored in this report.
