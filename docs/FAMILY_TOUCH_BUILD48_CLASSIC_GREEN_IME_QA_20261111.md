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

## S25 group-management, Contacts and Settings acceptance checkpoint (2026-11-12 local)

User requests incorporated into the **existing** `win.fantest.familytouch` project on draft PR #18, without a new production package or changed visual theme.

### Source changes
- Group creator: explicit destructive **Delete group** confirmation; remove active group locally, record higher-revision owner-only tombstone, queue authenticated membership update to former members, stop active group call and remove queued group messages. Clears local chat on confirmed delete; other devices can retain historical copies.
- Group member: confirmed **Leave group** action with optional checkbox to delete **local** messages only after successfully contacting the owner and completing the leave. A disconnected owner causes an error; membership is not silently modified.
- Deleted/removed group safeguards: incoming messages, membership changes, stale outgoing queue items, and send authorization are restricted to the active-group list, not tombstones.
- Contacts: standalone refresh button removed, existing Compose `PullToRefreshBox` retained with connection/error semantics.
- Settings: existing classic-dark-green controls reorganized into **Your profile**, **Private connection**, **Privacy & security**, **Chats & recordings**, and **App & updates** sections. No new themes/globe, no security or identity reset.
- Added isolated Android instrumentation `GroupDeleteDeviceTest` and `ImeFixtureDeviceTest`. The latter creates disposable test data only inside QA; cleanup excludes all non-fixture contact/message/call records.

### Verified evidence
- MASTER-PC Gradle: `:app:testDebugUnitTest :app:testQaUnitTest :app:assembleQa :app:assembleQaAndroidTest :app:lintQa` **BUILD SUCCESSFUL** (post-safeguard) in 2m16s; **152 JUnit tests, zero failures**.
- QA APK: `win.fantest.familytouch.qa`, versionCode **48**, `0.3.31-classic-green-updater-ime-qa`; **100,866,718 bytes**, SHA-256 `5ad51c813d102d43ab06f19f5d5745cd3463358b244ebd9291a9fc570b2328cd`, debug/QA signing certificate SHA-256 `a3c5765d65e8a763a0c165c78e7a9bfad17f389275680fab086b02d3999434ca`.
- S25 downloaded a small patch through a **temporary restricted HTTPS tunnel**, validated patch/test hashes, reconstructed full QA APK locally, matched exact expected APK SHA-256 and byte count, and installed in-place over the **QA** package with Android `Success`. Production never targeted.
- S25 `GroupDeleteDeviceTest` **OK (1 test)** after final source safeguards: owner-only group deletion, tombstone revision/persistence, rejection of stale incoming and outgoing messages, fixture cleanup; no real family group targeted.
- S25 `ImeFixtureDeviceTest#removeIsolatedConversation` **OK (1 test)**. Verified **zero** persistent test-contact ID references remain, including synthetic call history. No messages sent by instrumentation tests.
- S25 hierarchy displayed four tabs Chats / Calls / Contacts / Settings. Settings card labels matched the requested five sections. Contacts hierarchy showed **no standalone Refresh button**; pull-down gesture executed and UI remained responsive. Directory network request itself is not accepted: QA reports **family-registration conflict** when coexisting with production.
- The original S25 foreground app (`com.brave.browser`) was unchanged after device tests. No device screenshots, chats, secrets, or pairing keys were added to GitHub.
- S25 production `win.fantest.familytouch.embedded` remains versionCode **47**, `0.3.30-classic-green-updater-embedded`, unchanged `lastUpdateTime=2026-11-08 02:38:52`.
- Release-signing metadata audit: GitHub Actions has four secret *names*, but the existing workflow only consumes signing secrets on `main`. The secret certificate **has not been checked against** original Embedded signer `8b028e6a167011f2d070c333000e23cda90dbc6d6948cc71d83894da4a33ee87`. No new release key generated.
- 16 KB remains **BLOCKED**: prebuilt upstream pinned `app/libs/libtailscale.aar` contains `libgojni.so` with `p_align=0x1000` on ARM64. APK ZIP alignment passes; 16 KB runtime/emulator/VPN tests have **not** passed. See issue #19.

### Explicitly unverified / hold
- The end-to-end Samsung Keyboard composer test (multiline input, scroll, relaunch, keyboard insets) remains **PARTIAL**, not accepted solely from `imePadding()` or the synthetic fixture. Prior user video demonstrated keyboard-composer obscuration on the original Embedded build.
- Real QA-signed higher-version APK download, pause/resume from notification, WorkManager restart, signature rejection, and installer confirmation remain **UNVERIFIED**; small fixture-based updater unit/instrumentation passed earlier, not a substitute.
- Real multi-device group leave/delete propagation and cleanup are **UNVERIFIED**; only the isolated on-device group-owner deletion logic passed.
- Original production signing material compatibility **UNVERIFIED**. **Never** use the QA/debug certificate over Embedded Build47, uninstall it, clear data, publish the updater feed, or merge draft PR #18 without final approval.

**Production status: HOLD pending original matching release signer, full Samsung Keyboard and real updater physical acceptance, real group propagation QA, and 16 KB native compatibility.**
