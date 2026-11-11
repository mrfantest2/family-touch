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

## S25 physical deployment and updater regression (2026-11-11)

- S25 (SM-S938B) remote agent and ADB reconnected. The S25 already held the exact original Build47 QA APK (SHA-256 `98f81beb63dabcb94aa32b86c66045ce8090f0fd703cd39c20a07e89171277c9`).
- Reconstructed Build48 QA on the S25 from verified original using a checksum-selected normalization overlay (~4.8 MB) and Zstandard source delta (~328 KB). Final local S25 APK SHA-256 matched the MASTER-PC artifact: `5eccab566fd3350b914d3064339d0e65c7e42f935716a254ee33674859c720ee`, 98,988,128 bytes.
- S25 local ADB installation: `Success` (`adb install -r -t`) for separate package `win.fantest.familytouch.qa`.
- Postinstall `dumpsys package`: QA versionCode **48**, versionName `0.3.31-classic-green-updater-ime-qa`.
- Cold launch `Status: ok`; 483 ms initial, 418 ms after instrumentation. Foreground QA MainActivity and running process confirmed. No recent AndroidRuntime fatal exception found.
- The original Embedded package remains versionCode **47**, `0.3.30-classic-green-updater-embedded`, with unchanged lastUpdateTime (`2026-11-08 02:38:52`); neither its application data nor install state was reset.
- Installed current `app-qa-androidTest.apk` (SHA-256 `630efbf9992cda9bb252575b899f8cf5f9ca2c876d8e4c43c0d58cd53e34118e`), then ran `win.fantest.familytouch.UpdaterPauseDeviceTest` on S25. **OK (1 test)**; verifies pause persistence, 32-byte fixture retention, single-job cancel and cleanup in isolated QA app.
- Code audit confirms absence of the Globe source, theme picker and alternate appearance settings. **S25 screen/UI hierarchy verified the classic-green Build48 app and exactly four bottom tabs: Chats, Calls, Contacts, Settings; no Globe tab.** Chat-composer-on-Samsung-Keyboard testing remains **unverified** because QA has no conversation setup. A real signed newer update feed and actual long-running pause/resume download were not exercised.
- Source checkpoint: GitHub draft PR #18 (`integration/family-touch-classic-green-ime-updater-20261111`) against the Build47 reliability feature branch. No merge or production updater publication performed.

- QA screenshot initially displayed an Android App Compatibility warning. Direct ELF segment audit of QA48 and signed Embedded47: `libgojni.so` PT_LOAD `p_align=0x1000` (4 KB, **not 16 KB compatible**); `libandroidx.graphics.path.so` and `libjingle_peerconnection_so.so` show `0x4000` (16 KB, aligned). Both builds contain the same pre-existing native dependency issue. S25 reports `getconf PAGESIZE=4096`, so **this warning does not demonstrate a runtime failure on the current device**. Tracked separately in [GitHub issue #19](https://github.com/mrfantest2/family-touch/issues/19). Do not conceal the warning or mark 16 KB compatibility passed.

**Production status: HOLD pending matching release signer, Samsung Keyboard test, real updater lifecycle acceptance, and native 16 KB page-size remediation.**
