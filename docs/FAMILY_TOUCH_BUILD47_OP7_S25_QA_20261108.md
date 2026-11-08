# Family Touch build 47 — device checkpoint (2026-11-08)

Build: `0.3.30-classic-green-updater-embedded`, versionCode 47, package `win.fantest.familytouch.embedded`.

- Original signed APK: 147149642 bytes. SHA-256: `0923153e4c6ee5227ef0254b926a590dd7d0ceef175be316b070f13ad27653a0`. Original signer matched earlier embedded build. Recovery builds 45 and 46 remain backed up off-repo.
- Automated QA: 71 tests across 26 suites, zero failures, zero skipped; embedded lint PASS.
- Removed the Globe UI, projection code, and associated tests. Removed theme options. Retained classic green/dark as the fixed appearance, with no theme picker.
- Update check now lives outside the chat PIN gate on activity launch; existing HTTPS manifest, SHA-256, signing certificate and Android installer approval checks are retained. Prompt appears only when a newer signed update is offered by the feed.
- OP7: build47 installed in place via verified binary delta, package and SHA-256 verified; app started; no uninstall. Full UI automation inconclusive due intermittent wireless ADB.
- S25 SM-S938B: build47 reconstructed from hash-verified code46 and 1703638-byte delta, Android `pm install -r --user 0` returned Success. Post-install version 47; installed APK hash matches signed target; original firstInstallTime preserved (2026-10-27 11:54:44). Foreground activity and running process observed. App snapshot in Android recents shows green/dark theme; direct tab-by-tab UI inspection not complete.
- Production manifest was still code33 when checked on November 8. Do not claim that an update dialog will appear for code47; older installed builds will only see a prompt when the signed newer release is published to the update feed.
- Production feed, main branch, paired chats, chat PIN, and device identities were not modified by this checkpoint. Cross-device messaging/calling acceptance remains pending.
