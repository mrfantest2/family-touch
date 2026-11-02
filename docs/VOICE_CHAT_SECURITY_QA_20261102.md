# Family Touch v0.3.23 — inline voice composer, per-chat PIN, contact help and private notifications

Commit branch: feature/family-touch-live-media-security-20261102
Baseline: v0.3.21 code 38. Initial candidate: v0.3.22 code 39; final privacy-patched build: v0.3.23 code 40.
**Scope is intentionally narrower than the full user roadmap.** The real-time map pin picker, 15-minute / 1-hour / 8-hour live location sessions and built-in KDE remote control are NOT shipped as working features in this build. Preserve existing one-time location URL handling.

## Implemented
- Replaced the voice-note modal entry point in direct/group conversation composer with an inline recorder.
- Inline controls: automatic microphone capture after user taps record and grants permission, Pause/Resume, Stop/Review, media playback preview, slider seeking, Cancel/discard and Send. Transfer uses the existing attachment send path and supports retry on errors. Lifecycle ON_STOP cancels active microphone recording (privacy).
- MediaRecorder supports pause/resume while tracking accumulated recorded duration. Existing 5-minute max retained. No automatic recording without a foreground tap.
- Per-conversation local six-digit PIN, separate from the whole-app PIN; Android Keystore-backed encrypted preferences, per-peer salted PBKDF2-HMAC-SHA256, constant-time equality and 30-second cooldown after five wrong entries. Chat is locked after activity background or after leaving the conversation. PIN is not synchronized to other family devices. Conversation header shows 'Lock this conversation'.
- Long-press Contacts: Open conversation, Ping paired device (presence query), SOS family chat message with explicit confirmation, or Request technical support. SOS does NOT contact emergency services. Technical support requests are chat messages; they do NOT grant access or execute remote commands.
- KDE family assistance architecture documented in KDE_FAMILY_ASSISTANCE_DESIGN.md: a future feature needs locally approved time-bounded support, per-action permissions, persistent indicator, revocation and audit; no hidden Accessibility/ADB access.

## QA evidence
- Signed Embedded/QA APKs built successfully by MASTER-PC. Embedded APK length **147,033,723 bytes**.
- SHA-256 **455CE31B3506896F080F9125AB11D935F65E3B68BE6F07A398BDB660EFA40D4D**; original signed certificate SHA-256 **8b028e6a167011f2d070c333000e23cda90dbc6d6948cc71d83894da4a33ee87**.
- All **58 JVM tests passed** (no failures/errors).
- Android instrumentation `ChatSecurityDeviceTest.independentChatPinRejectsWrongEntryAndCanBeRemoved`: **PASS** on OP7 under isolated `win.fantest.familytouch.qa` package. Synthetic randomized chat IDs and synthetic PIN; isolated from production family chats and PINs. No real messages were accessed, edited or deleted.
- OP7 signed in-place Embedded installation using authenticated ADB succeeded (`install -r --no-streaming`). Device reports **versionCode=39**, **versionName=0.3.22-inline-voice-chat-lock-embedded**, original `firstInstallTime=2026-10-20 01:49:03` unchanged, new process PID 15025.
- S25 delivery, updated direct-chat UI acceptance, microphone record/play/send QA, actual remote SOS delivery, and code39 self-updater release are **not yet verified**. Do not describe them as passed.
- No changes to production Embedded code33 update feed, Admin app, family directory server or original messages.

## Follow-on checks
1. On unlocked OP7, inspect Contacts long press, chat lock create/unlock/relock, and inline audio composer.
2. With the phone owner present, record non-sensitive short QA audio, stop/pause/scrub, send to an updated family device, then delete test recording.
3. Verify S25 code39 installation, then two-way help/Ping/SOS behavior and per-chat locks on each device.
4. Separately design/validate interactive map and explicit live location opt-in with Android location foreground service; do not ship until privacy and manual stop/auto-expiry tests pass.
5. Implement KDE support as an explicit, revocable help session; no unattended remote commands or silent APK install.

## Final privacy patch — v0.3.23 (code 40)

- Built and signed Embedded + QA packages after masking notifications for conversations protected by a separate chat PIN.
- Incoming messages from locked direct conversations now generate a generic notification title/body; notification content is marked VISIBILITY_SECRET. Ordinary unlocked chat alerts and call alerts retain previous behavior.
- The selected-but-locked conversation still triggers a notification, rather than being incorrectly suppressed because its screen was open.
- Signed Embedded APK size **147,033,715 bytes**, SHA-256 **9E5A050C6AA31594CD7BBCFAD1B755539FD916D391A13F371F06CFFB72D52FEA**; signing certificate unchanged.
- **58/58 JVM tests passed** on build 40. The earlier code39 isolated OP7 chat-PIN instrumentation PASS remains valid (PIN module unchanged by privacy patch). No actual microphone recording or third-party family messages were used for QA.
- OP7 in-place `adb install -r --no-streaming` code39 -> code40 succeeded. OP7 reports `versionCode=40`, `versionName=0.3.23-chat-lock-privacy-embedded`; first-install time preserved and app running PID 27833. No app data was cleared.
- S25 KDE Connect file share of code40 succeeded, but installation on S25 remains unverified due unavailable remote device access.
- Signed code40 APK copied to user's private Google Drive recovery folder; checksum verification captured separately.
- The production Embedded update manifest still advertises code33. Nothing was published to production release feed.
- **Known acceptance limitation:** OP7 was at its Android lock screen during visual inspection; inspect inline recorder, contact menu, chat PIN and notification body manually when the user unlocks it. Conversation-list preview masking has not been independently verified; do not assume full protection in every surface.
- **Deferred:** map pin chooser and timed consent-based live sharing; full KDE-based remote control, device navigation, file browsing and APK installation. Assistance in code40 is only an opt-in chat help request.
