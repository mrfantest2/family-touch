# Family Touch v0.3.24 (code 41) — Map pin and timed live location

## Source and branch
- Base commit: `4e8bca4`, signed production candidate build 40.
- Feature branch: `feature/family-touch-map-live-location-20261102`.
- Applies to embedded private-VPN Android app; other packages are not deployed as production.
- Existing conversations, app-wide PIN, individual chat PINs, voice composer, self-updater, contacts and media preserved.

## Implementation
- Replaced schematic shared-location grid with an online OpenStreetMap embedded preview for existing one-time map URL messages. Tap **Open in Maps** to launch installed map app.
- When user explicitly selects Location from paperclip, request fresh Android coarse/fine location. Map sheet allows selecting and moving a pin before choosing **Send selected pin**; no transmission until Send.
- The sheet provides **Live location** for an individual, paired recipient only. Exact durations: **15 min, 1 hour, 8 hours**. Live mode uses actual device location, not a user-selected map pin. Group-wide live sharing deliberately disabled.
- Separate Android location foreground service, declared with `FOREGROUND_SERVICE_LOCATION`. Requires Android location permission, visible Family Touch app when starting, and notification permission on Android 13+. Started only by an explicit in-app user action.
- During sharing, show ongoing notification and in-chat indicator with **Stop**. Service stops at expiry, device restart or app crash; it does not auto-resume unattended. Stops also remove GPS/network subscriptions.
- Direct peer messaging via authenticated existing Tailnet transport, new `live_location` type, UUID session identity, immutable expiry, bounded geographic coordinates and update periods. Incoming sender identity is the already authenticated pairing identity. Message updates replace a single session bubble; wrong-peer changes and revival of ended sessions are rejected.
- Local/remote maps show a street map; online map tiles may contact OpenStreetMap and CDN hosts. OpenStreetMap attribution is displayed in picker. No bulk map prefetch or offline tile download.
- Keyboard now uses Android `adjustResize`, intended to reduce bottom composer/keyboard overlap seen in supplied video.

## Privacy, limitations
- Family member must proactively select a recipient and start sharing. Sending a chat message cannot activate location collection.
- Live-location updates are best effort while private network is connected; latest fix may be missing offline. Recipients calculate expiry locally if the final Stop packet cannot be delivered.
- The sender can immediately stop via notification or app. A client/app crash may end collection without delivering a final Stop packet. There is intentionally no background permission or crash restart.
- Coordinate source authenticity depends on Android LocationManager and paired device, not on cryptographic proof that a device is physically located there.
- Online map preview may disclose the area viewed to OpenStreetMap, which is a third-party map provider.
- Live updates are supported for direct chats between **both** upgraded protocol-compatible Family Touch clients; older code40 clients do not understand new real-time location messages. The UI must not promise seamless backward compatibility for live sharing.
- Full KDE Connect remote control, file browsing and remote app install remain separate, require device-owner approval and are not part of this change.

## QA acceptance checklist
- Gradle Kotlin compile, 4 location policy JVM tests, existing JVM regression suite.
- Signed APK with same release certificate, in-place QA package and OP7 installation with no clear-data.
- On real unlocked phone: open chat, tap Location, map load/tile credit, move pin, Cancel, select Send and confirm correct map bubble. Keyboard layout checked with the IME open.
- Live 15-min, 1-hour and 8-hour sessions: verify Android permission prompt, active notification, local Stop, expiry, background (Home) behavior, incoming peer card, and connectivity dropout. **Do not initiate a session without the device owner's on-screen consent.**
- Verify PIN-locked chat does not show location content in notifications.
- S25 installation and two-device live delivery are separate gates. Passing compile alone does not prove live location functions on S25.

## Verified build 42 checkpoint

- Final signed build: **Family Touch v0.3.25-map-live-privacy-embedded, versionCode 42**; original release signing identity retained.
- Embedded APK size: **147,100,486 bytes**. SHA-256: **8461BDEFF00F47D82D448EC0C3F0C97314832C505D949EF9EB986EDB35763AD5**.
- Release-signing certificate SHA256: **8b028e6a167011f2d070c333000e23cda90dbc6d6948cc71d83894da4a33ee87**.
- MASTER-PC Kotlin compilation, Android package/lint and Gradle QA test suite: **BUILD SUCCESSFUL**. **62/62 JVM QA tests PASS**, 0 failure, 0 skipped.
- Emulator: separate `win.fantest.familytouch.qa` v0.3.25 APK in-place installation SUCCESS, app launch PID 12412. Previous embedded v0.3.24 QA instrumentation for local PIN **PASS**. Emulator registration currently rate limited; this does not prove real map sharing.
- OP7: signed Embedded app `adb install -r --no-streaming` from code41 to **code42 SUCCESS**. Android reports `versionCode=42`, `versionName=0.3.25-map-live-privacy-embedded`, original `firstInstallTime=2026-10-20 01:49:03` unchanged, app process PID 27835. No uninstall or clear-data was performed.
- Privacy hotfix in code42: active live-location notification uses `VISIBILITY_PRIVATE` rather than exposing the recipient's identity on the Android lock screen. Online map previews permit required embedded map subframe navigation but block top-level WebView navigation.
- S25 was **not updated** to code42. Earlier user workflow requires OP7 complete physical QA before S25 acceptance. Previous production server manifest still advertises code33 until an approved release process.
- Not verified: real street tiles from OP7 WebView, the draggable map pin on an unlocked OP7, live Android location service start/stop and 15-min expiry, actual peer delivery and offline termination behavior, 1h/8h sessions, S25 user installation. **Do not claim these end-to-end behaviors passed**.
