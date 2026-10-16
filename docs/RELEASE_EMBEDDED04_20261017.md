# Embedded04 — first-launch nickname prompt — 2026-10-17

Package: win.fantest.familytouch.embedded
Version: 0.1.0-embedded04-standalone (9)
APK size: 144382288 bytes
SHA256: eb75ec1f4965067c6ee00cac380c8b395971f13778ef125c0e2d6ed90cc14573
Certificate SHA256: 8b028e6a167011f2d070c333000e23cda90dbc6d6948cc71d83894da4a33ee87
Download: https://fantest.win/downloads/Family-Touch-Embedded-Tailscale-embedded04-standalone.apk

Fresh installations ask whether to change their nickname in a welcome dialog. The device-based name is suggested, with Save/Skip choices. Blank names cannot be saved; length follows the existing 36-character limit. Skip keeps the suggested name. Choice and nickname persist atomically; a failed preference write keeps the prompt available. Existing identities migrate as completed, preserving names and suppressing welcome on upgrade. Background service initialization preserves the pending prompt on new installations. Notification permission is deferred until after the nickname choice. Settings remains available for later renaming.

Validation:
- testEmbeddedUnitTest, assembleEmbedded, lintEmbedded and assembleQa succeeded in 2m6s.
- Existing 22 tests pass; lint 0 errors, 9 existing warnings. No unnecessary unit tests were added for this small UI change.
- Code review found no important issues.
- Signed release APK installed fresh on verified emulator-5556. Welcome, suggested name, Save/Skip controls appeared before notification consent.
- Saved FamilyTester; notification permission appeared afterward. Restarted the process, navigated to Settings and verified the saved nickname persisted without onboarding blocking navigation.
- Updated the existing QA identity: completion marker migrated to true; fresh UI dump confirmed no onboarding question. Transient UI-dump failures were retried with a new dump filename to avoid stale evidence.
- Initial emulator port conflict was resolved by stopping the previous QA process, preserving its data. No uninstall or data clearing performed. Test app processes left stopped at Home.
- apksigner and HTTPS byte checksum verified.
- Verified S25 SM-S938B/qemu0 and matching installed/new certificates before update-in-place. Installation returned Success and device reported versionCode9/embedded04. Device Download checksum matched.
- No S25 launch command issued. Original app and existing data preserved.

The embedded03 VPN toggle and status behavior is unchanged. Automatic tailnet enrollment and authenticated two-device call/message acceptance remain outstanding; nickname onboarding does not solve or imply that work.
