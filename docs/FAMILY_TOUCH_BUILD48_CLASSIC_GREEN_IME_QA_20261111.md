# Family Touch — classic-green Build 48 QA integration (2026-11-11)

## User-approved visual baseline

Production S25 baseline: `win.fantest.familytouch.embedded` versionCode **47**, versionName `0.3.30-classic-green-updater-embedded`.
The new build preserves the approved fixed dark-green design. **No theme picker, multicolor/light-mode choices, Globe tab, or Globe source.**

## Source

- Branch: `integration/family-touch-classic-green-ime-updater-20261111`.
- Common ancestor: `57df266`.
- Reliable calls and pairing baseline: `3a4084e`.
- Compose IME and resumable updater: `ae60647`.
- Optional ARM64 QA APK: `3a80923`.
- Classic-green/no-globe and launch prompt: cherry-picked `d105cde`, now `ca8e8f6`.
- VersionCode 48, `0.3.31-classic-green-updater-ime` (+ `-qa` for the separate QA package).
- Compose `MainActivity.kt`: root uses `navigationBarsPadding().imePadding()`; `FamilyUpdatePrompt()` is outside `FamilyPinGate`.
- Updater supports launch/resume checks, pause/resume downloads, notification actions and WorkManager persistence.

## Local automated verification

MASTER-PC `D:\Projects\family-touch-pr15-audit`, Android SDK36, Gradle8.10.2, JDK21:

```
-PfamilyTouchQaArm64=true :app:testDebugUnitTest :app:testQaUnitTest :app:assembleQa :app:assembleQaAndroidTest :app:lintQa
```

Result: **BUILD SUCCESSFUL**; 104 tasks, 41 executed, 63 up-to-date.
JUnit: **150 tests / 54 suites, 0 failures, 0 errors, 0 skips**.
Android Lint QA: PASS, existing warnings.
Validation log: `D:\Projects\family-touch-classic-green-build48-validation-20261111.log`.

Build48 QA artifact: `D:\Projects\family-touch-pr15-audit\app\build\outputs\apk\qa\app-qa.apk`
- Package: `win.fantest.familytouch.qa`
- Bytes: **98988128** (ARM64-only).
- SHA-256: `5ECCAB566FD3350B914D3064339D0E65C7E42F935716A254EE33674859C720EE`.
- QA signing certificate SHA-256: `a3c5765d65e8a763a0c165c78e7a9bfad17f389275680fab086b02d3999434ca`.

## Release protection

Installed original Embedded Build 47 signer SHA-256: `8b028e6a167011f2d070c333000e23cda90dbc6d6948cc71d83894da4a33ee87`. The debug QA signer is different. Do **not** try to install a QA APK over `win.fantest.familytouch.embedded`, uninstall the production app, overwrite the signed updater feed, or claim Build48 is production-ready.

The original release keystore/password has not been located in the current build environment. Before an in-place Embedded Build48 upgrade, obtain the **matching original signing identity**, use a separate full release APK, validate data-preserving upgrade, and only then consider publishing a new signed feed manifest.

## Physical QA gates

Use the separate `win.fantest.familytouch.qa` Build48 APK on S25. Verify:
- package/version/installation and clean launch
- dark-green theme, only Chats / Calls / Contacts / Settings; no Globe or theme picker
- chat composer stays visible above Samsung Keyboard
- signed updater check on launch; no dialog when feed versionCode is not newer
- paused download persists and resumes after foreground/background transitions without losing verified chunks
- production Embedded version and data unchanged
- long-duration cross-device messaging/calling remain outside this build's sign-off

**Production status: HOLD pending signer and physical acceptance.**
