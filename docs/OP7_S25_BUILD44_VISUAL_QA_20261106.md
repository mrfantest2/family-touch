# Family Touch — signed build 44 visual deployment QA (November 6, 2026)

Release status: DRAFT CANDIDATE. In-place deployment to OP7 and S25 PASS. Production release feed and GitHub main unchanged. Physical end-to-end messaging and calling still pending.

## Signed candidate

- Android package: win.fantest.familytouch.embedded.
- versionCode 44; versionName 0.3.27-visual-embedded.
- Signed APK: 147116862 bytes; SHA-256 8b348d35b25bbd74ff356dfcb5f021836e2cc2b796eb1e1fc7b839f5b1d19ed5.
- APK signature verified by apksigner; signer certificate matches installed build 43: 8b028e6a167011f2d070c333000e23cda90dbc6d6948cc71d83894da4a33ee87.
- Local signed candidate and previous signed build 43 retained separately outside the repository.
- MASTER-PC Gradle 8.10.2: embedded assemble SUCCESS, embedded unit tests 70/70 across 25 suites (zero failures/skips), lintEmbedded SUCCESS.

## OP7 primary physical-device QA — deployment PASS

- Device OnePlus 7 GM1903; ADB via Agentbox loopback 127.0.0.1:15555.
- Downloaded compressed, signed candidate; verified decompressed APK size 147116862 and SHA-256 exact match.
- Android pm install -r returned Success and exit 0. Installed versionCode 44, versionName 0.3.27-visual-embedded.
- Existing firstInstallTime 2026-10-20 00:49:03 preserved after upgrade; no uninstall or data clear.
- Installed base.apk SHA-256 exact match to signed candidate.
- Family Touch MainActivity launched; process remained present; Do Not Disturb/Zen level 2 preserved.
- UI hierarchy showed Build 44, Chats, Calls, Contacts and Settings with no automatically blocking VPN setup dialog.
- Physical screenshot of Chats captured and reviewed. Navy-violet surface, purple selection, card-style chats, connection status indicator and bottom navigation are visibly rendered. Evidence stored locally outside Git in C:\Khalil\family-touch\safe-build-backups\qa-evidence\FamilyTouch-build44-op7-qa.png; screenshot not published due to private contacts.
- Device navigation smoke test touched the four bottom tabs; capture results varied, so full interactive acceptance is NOT claimed. No real message, call, sharing session or account state was changed.

## S25 Ultra physical-device QA — deployment PASS, visual acceptance partial

- Samsung SM-S938B, Android ADB at 100.99.16.99:45555, user 0.
- HTTPS download and decompression SHA verified. On-device shared and local staging APK hashes matched the signed candidate.
- Android pm install -r --user 0 returned Success; installed package versionCode 44, versionName 0.3.27-visual-embedded.
- firstInstallTime 2026-10-27 11:54:44 preserved; lastUpdateTime reported 2026-11-06 03:23:05 (device-local).
- Installed base.apk SHA-256 exact match to signed candidate.
- Family Touch MainActivity launched and process PID observed. User subsequently returned to ChatGPT; UI hierarchy inspections then captured ChatGPT rather than Family Touch, so S25 visual acceptance remains pending.
- The installed Family Touch package was not uninstalled or cleared; Android DND setting 0 was preserved.

## Remaining issues and release gates

- OP7 conversation preview can expose a raw FT_FILE serialized attachment marker. It should render a human-readable file preview without exposing protocol IDs. This was not modified in build 44; follow-up source fix and unit/physical tests required before production promotion.
- Redmi Pad 2 remains on the separately verified older build 42 unless an independent check confirms upgrade. It was not in this deployment request.
- S25 visual acceptance on its own display, authenticated device discovery, two-way chat and receipts, audio/video calls, and consent-driven live-location lifecycle have not all been proven in this run.
- App distribution feed, protected main branch and draft PR release status are unchanged. Do not promote production based solely on successful installation.
- No signing passwords, tokens, private chat contents or contact photos are included in Git.