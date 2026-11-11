# Family Touch: launch checks, background Pause/Resume, and release gate

Checkpoint: 2026-11-11. Branch: `fix/family-touch-ime-composer-20261111` based on Build 47 PR #15.

## Behavior

- FamilyUpdatePrompt observes the owning Activity's ON_RESUME lifecycle, so reopening from background triggers a fresh HTTPS manifest check. The existing Settings "Check updates" action remains manual.
- A newer `versionCode` displays the update prompt; equal or older feed versions do not prompt for a downgrade.
- The WorkManager download runs independently of chat and the app's PIN. Ranged chunks remain in private app storage and resume after network interruptions.
- The dialog provides Pause download, Resume download, Download in background, or Install update as appropriate.
- Running foreground downloads include a Pause notification action; a separate paused notification includes Resume, which enqueues work without opening the app.
- The receiver is non-exported. Cached update metadata is revalidated; package name, APK SHA-256 and signing certificate are checked before installation. Android's package installer must still authorize installation.

## Verified live manifest blocker

As observed on November 11, 2026:
`https://family-updates.fantest.win/manifests/win.fantest.familytouch.embedded.json`
publishes versionCode **33**, `0.3.16-updater-verified-embedded`, while installed OP7/S25 Embedded apps reported versionCode **47**, `0.3.30-classic-green-updater-embedded`. Thus the installed app **correctly cannot offer the current feed as an upgrade**. Publishing a new release requires a **distinct, higher versionCode (at least 48)** and the original authorized signing identity, plus physical two-device acceptance. Do not change the server feed to point to a debug/QA APK.

## Local validation

- MASTER-PC Android SDK 36, Gradle 8.10.2, JDK 21.
- Combined Debug/QA unit tests, QA assemble and lint: PASS, Gradle exit 0.
- QA Android instrumentation APK compile: PASS; new `UpdaterPauseDeviceTest` is compiled but NOT device-run.
- Physical visual keyboard layout, background notification action and actual pause/resume download: NOT YET VALIDATED.
- Original production apps remain unchanged. Do not merge or publish while physical acceptance is incomplete.

## Next QA

1. Restore a reliable local/USB APK transport to OP7 or S25. Wireless tailnet ADB transferred large APKs unreliably in previous attempts.
2. Install `win.fantest.familytouch.qa` separately, without uninstalling `win.fantest.familytouch.embedded`.
3. Run the new test via instrumentation, verify compose input remains above Samsung Keyboard in both dark-green chat and group chat.
4. Test controlled range-server interruptions, pause from dialog, pause from notification, resume from notification after app process termination, and resume after reboot. Verify retained chunk SHA and final APK signer.
5. Reconcile installed Build 47 classic-green source provenance, then build/sign a newer release with incremented versionCode and test a data-preserving update before publishing the manifest.
