# Family Touch — Redmi Pad 2 ↔ OnePlus 7 QA checkpoint (2026-11-05)

Source audit: draft PR #14, branch `feature/family-touch-map-live-location-20261102`; original shared HEAD `27201968bc60760742298572cf3f99068cee1412`, clean before this checkpoint.

## Physical device and transport evidence

- Redmi Pad 2: Xiaomi model 25040RP0AG, Android 15, signed `win.fantest.familytouch.embedded` build 42 installed and MainActivity launch accepted. Installed base.apk SHA-256 `8461bdeff00f47d82d448ec0c3f0c97314832c505d949ef9eb986edb35763ad5`; identical to verified existing release candidate. No uninstall or clear-data.
- OnePlus 7: GM1903, Android 11, same build 42; authorized ADB over Agentbox and standalone Tailscale. Family Touch MainActivity launched and process retained. Contacts/Settings tabs rendered on OP7.
- Both currently route via the standalone Tailscale VPN. Direct TCP port 48321 accepts connections in both directions between Redmi and OP7 over the tailnet, and from MASTER-PC to Redmi. A TCP accept is not proof of an authenticated Family Touch pairing or message/call delivery.
- OP7's earlier embedded node is offline; OP7's current tun1 address is its standalone Tailscale identity. Do not treat an Android connected VPN interface as proof that the embedded Family Touch node is online.
- On OP7, current contact preview states were offline; no confirmed Redmi ↔ OP7 paired identity or receiving client session. No test message or call was sent.

## Silent-mode evidence

- OP7: Android Zen/DND setting 2; prior audio dump showed system, ring, music, alarm, notification streams muted at 0. Voice-call stream has a nonzero hardware minimum; no call was placed.
- Redmi: media stream 0/15 verified; ringtone and notification remained 8/15, system 8/15. The unprivileged Termux shell cannot persist changes to protected sound settings. These still require owner UI or an authorized Android debugging/control route. No claim that Redmi is fully muted.

## Source regression fix

- OP7's contact chat-list subtitle exposed the raw `FTLIVE1:` live-location wire marker instead of a readable summary. Source review found `MainActivity.kt` passing `message.text` directly to `PeerRow`.
- Added `ChatPreviewFormatter.kt` so active/stopped/expired location updates appear as human-readable statuses, with malformed protocol markers suppressed. Ordinary text remains unchanged; raw session IDs and coordinates are not shown in contact previews.
- Wired this formatter into chat-list contact rows only. Added five JUnit tests `ChatPreviewFormatterTest.kt` for plain, active, stopped, expired and malformed inputs.
- Fresh MASTER-PC Gradle 8.10.2 `:app:testEmbeddedUnitTest --offline` succeeded: **67 tests across 24 suites, 0 failures, 0 skipped** (up from 62 across 23 before the change).
- Android lint task :app:lintEmbedded also passed. Android Gradle plugin 8.7.3 gives a preexisting compatibility warning for compileSdk 36; no runtime/QA failure attributed to it.

## Remaining gates

- Redmi Android sound settings: mute ringtone and notifications (currently 8/15).
- Complete authorized Family Touch peer invitation/pairing on both devices. Do not expose, read, reuse, or bypass stored pairing secrets.
- End-to-end paired synthetic message delivery, read/delivery receipt, audio and video call setup/teardown, and mute acceptance need both apps' on-device UI/permission approvals.
- Live-location start/stop/expiry requires explicit on-device consent, and is outside this passive QA pass.
- New chat-preview fix is source-only and must not be represented as installed build 42 on either phone. A separately validated signed candidate and in-place upgrade are required for device visual acceptance.

No production merge, app reinstall, permission grant, call, message send, or location transmission was performed during this checkpoint.