# Family Touch groups01 candidate — 2026-10-18

Version14 / 0.2.0-family-groups01-standalone, package win.fantest.familytouch.embedded.

Download: https://fantest.win/downloads/Family-Touch-Groups01-candidate.apk

APK: 144677388 bytes. SHA256: `3bdf8503a146c64af30f5a64871a6434607bd3b4d4a7e5a23c054ebdecaa42d2`.

Certificate SHA256: `8b028e6a167011f2d070c333000e23cda90dbc6d6948cc71d83894da4a33ee87`.

Release bundle package win.fantest.familytouch, version14. AAB SHA256: `81c9c117b2513332164c3d14e32982bb951c22b6a2657e8427bbd5d841ab56e7`.

Features implemented: creator-managed group chats, four-device mesh calls, image/video viewer, fresh location preview, five-minute voice notes, all-participant consent recording, local playback/sharing/deletion, and Back navigation. Media remains on devices and transfers through authenticated peer channels. A sender's group owner must be reachable to reconcile membership before queued sends. Membership edits end an active matching group call. Interrupted MP4 without readable index is retained and explicitly not marked playable.

Verification: signed APK and AAB builds, 41 unit tests, full embedded lint; read-only review and five corrections; physical OP7 generated-audio codec/muxer instrumentation test passed. APK certificate matched the installed OP7 certificate; install-r succeeded and version14 confirmed. Full HTTPS download matched the build hash. Apache active configuration DocumentRoot C:/laragon/www confirmed before publishing. Existing Alpha05 and final alias preserved.

Not yet verified: live group calls, remote audio/video recording, consent withdrawal on actual devices, viewer/location/Back runtime regressions. S25 is not updated because ADB remains unauthorized via RP2 and direct private ADB routes time out. OP7 ADB went offline when embedded VPN replaced the old VPN; both Family Touch endpoints subsequently answered identity probes.

Play: existing latest internal artifact version2. Four requested tester addresses verified saved in selected Family Touch Internal email list. New release draft2 exists. Chrome extension blocks file upload until file-URL access is enabled or the owner selects the bundle manually. No successful bundle upload or new internal submission claimed.

Internal opt-in: https://play.google.com/apps/internaltest/4700914074189616494 (currently existing release until new submission completes).
