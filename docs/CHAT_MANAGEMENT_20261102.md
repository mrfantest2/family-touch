# Family Touch — chat management / attachment UX / quiet foreground connection

Branch: `feature/family-touch-chat-management-20261102` on code 36 background-updater checkpoint. New Embedded build: `v0.3.20`, code `37`.

## Product changes
- Persistent PeerService foreground notification minimized and shortened to `Family Touch — Private connection active`, with silent priority-minimal service category, no timestamp, and deferred foreground display where supported. Existing Android service channel `familytouch_status_silent_v3` is preserved so user notification preferences remain intact. **Android requires an active foreground-service indication; hiding the notification completely is not promised.**
- Settings -> `Connection notification settings` opens the exact Android channel to let the user choose the OS-supported presentation. Independent heads-up message channel and incoming call channel are unchanged.
- Chat composer paperclip now opens a compact attachment sheet: Photos, Videos, Documents/files, one-time Location with consent and runtime permission, Camera. Icon slots harmonized at 40dp and microphone/send button at 48dp. Document attachments transfer with authenticated tailnet transport, 50MiB limit, strict MIME pattern, and executable extension/MIME rejection. Documents open via Android's normal read-only file chooser.
- Chat header avatar/name opens a contact profile with avatar, live presence and group/device type, plus shared media action.
- Long-press message actions: Reply, reactions, **Edit** for delivered outgoing one-to-one text within 15 minutes, **Delete for me** (local-only), and **Delete for everyone** (delivered outgoing direct messages only). Clearing the current conversation is available via the 3-dot menu with confirmation and is local-only.
- Message edit/delete-everyone sync uses an authenticated `message_mutation` message and a persisted retry queue. Incoming mutation verifies sender identity matches original message peer, validates monotonic revision, prevents edits of deleted messages, and retains tombstone/edited metadata across process restart. Presence protocol advertised as `5`; old clients cannot synchronize remote edits/retractions until updated. Offline recipients, backups, screenshots, and externally saved copies cannot be remotely erased.
- Settings adds encrypted, merge-only `ChatBackupPanel` export/import via Android Storage Access Framework, including Google Drive as a selectable document provider. AES-256-GCM, PBKDF2-HMAC-SHA256/210k iterations, 16-byte salt, 12-byte nonce and size limits. Passphrase minimum 8 characters. Up to 5000 chat messages are included with timestamps/edits/deletion markers; **media binary files, pairing secrets, PIN, contacts credentials, and recordings are intentionally excluded**. Import does not overwrite existing messages or device enrollment. Backup must be manually saved to Google Drive; no direct-API automatic sync is implied.
- Existing Admin package, VPN mode, recording pipeline, production update manifest, and website are unchanged.

## Verification on MASTER-PC and OP7
- Kotlin compilation PASS and all 56 QA JVM tests PASS after updating attachment policy test and using cached `org.json:json:20240303` for cryptographic round-trip tests.
- Signed Embedded and QA build PASS, existing upload signer unchanged: certificate SHA256 `8b028e6a167011f2d070c333000e23cda90dbc6d6948cc71d83894da4a33ee87`.
- Embedded APK size `146951787`, SHA256 `7D7DDC91A1D559CDE00C942CF370C912BBF7A1C7FC37CFD6CD4BBED994298B2C`.
- On OP7, the **separate QA package** was installed and `ChatManagementDeviceTest.authorizedEditDeleteAndForgedSenderBlocked` PASSED, with synthetic messages only; it removed its test data.
- OP7 main embedded app installed in place (adb install -r --no-streaming) PASS, versionCode=37, versionName=0.3.20-chat-management-embedded, `firstInstallTime=2026-10-20 01:49:03` unchanged, app process PID 15010.
- OP7 actual notification service verified by `dumpsys notification`: channel importance 2/LOW, no sound, no vibration, silent service, content text `Private connection active`, foreground service still running. Message heads-up channel remains separate HIGH importance.
- OP7 physical screen was OS-locked during visual inspection, so live UI taps for profile/attachment sheet were NOT verified. Source and compilation checks passed.
- S25 connected to MASTER-PC through KDE Connect; signed code37 APK sent with exit 0. The receiving device's package version or user installation confirmation was NOT verified. S25 RDC offline; ADB unavailable at the latest checkpoint.
- Production Embedded self-update feed still advertises code33; code37 not published to feed. Preserve server and Admin updates.
- Pending: S25 installation acceptance; two-device remote message mutation propagation (both clients must be at protocol 5); secure backup via Google Drive SAF UI and media transfer acceptance; OP7 unlocked UI visual acceptance.

## Rollback and QA
No uninstall or clear-data actions occurred on installed familytouch.embedded. Existing source and signed code36 APK preserved in adjacent worktree. The code37 build is staged on MASTER-PC; do not conflate transfer with installation on S25.
