# Family Touch — OP7-first media UI and resumable update checkpoint

## Source and scope
- Feature branch: `feature/family-touch-media-ux-updater-20261102`, based on dark chat PR #8.
- Android Embedded variant only. Preserve Android app data, device identity, existing VPN and Admin app.
- OP7 is the QA device. S25 acceptance is deferred.

## UI changes
- Actual local image thumbnails in chat bubbles, image thumbnail alongside reply composer, single-line ellipsized full-screen filenames.
- Original schematic location preview; tap still opens the existing Maps link (not a fetched map tile).
- Compact rounded reaction bottom sheet, create-group floating action, refined call-row spacing, directory alert pill and unread badge.
- Optional six-digit PIN unlock with six indicators, custom keypad and contrasting primary action. Default remains disabled.

## Updater fix
- Signed updater APK code 34 adds bounded HTTP Range chunks (2 MiB), retry after reset, persistent partial storage identified by version and SHA-256, Content-Range verification, and original full SHA-256 / certificate / package / version checks.
- Range-only Python origin source: `scripts/updates_server_range.py`; allowlisted read-only GET and HEAD.
- MASTER-PC scheduled task `FamilyTouchUpdatesServer` restarted after a backup of previous server at `C:\ProgramData\FamilyTouchUpdates\server.py.backup-20261102-175212`. Health and Admin manifest 200; local range 206, including last byte and 416 rejection.
- Public Cloudflare URL purged individually for Embedded APK; public range requests for beginning, middle and final byte were observed to succeed, but occasional intermittent timeouts occurred.
- OP7 code-32 in-app update detection for code 33 PASS; original full download failed at 11% with Connection reset. New resumable download requires full physical acceptance before declaring updater fixed.

## Verified / pending
- v34 MASTER-PC Gradle compile, QA unit tests, signed assemble and certificate verification PASS.
- OP7 has established ADB via Tailscale 100.85.113.35:5555; code32 actual dark chat, voice durations, schematic location and reply bubbles visually inspected.
- OP7 in-place code34 deployment and code34→later signed update acceptance pending.
- Remaining OP7 physical QA: media thumbnails (when attachment available), bottom sheet, keypad without setting PIN, outgoing calls and audio/voice note actions; do not involve S25 until OP7 gate completed.
