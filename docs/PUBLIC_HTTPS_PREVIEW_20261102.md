# Family Touch public HTTPS preview — 2026-11-02

## Scope
An isolated non-Admin Family Touch Android variant (package suffix .public) uses public HTTPS (https://family-api.fantest.win) instead of Android VpnService or Tailscale. Existing .embedded and .admin apps are not replaced by this preview.

- Directory: signed enrollment and roster sync with publicRelay=true; only approved, non-revoked device profiles are returned with synthetic ip=relay.
- Messages: signed HTTPS send/pull of AES-256-GCM ciphertext, per-device recipient mailbox, 7-day retention, 96-envelope per-device cap, 8192 total cap, replay-protected signatures, sender/recipient authorization, explicit acknowledgment.
- Receiver: foreground PeerService polls HTTPS, handles text, delivery receipts, read receipts, reactions and profile updates. Queued and delivered are distinct.
- Admin APIs are never routed via public listener. Never expose the local Admin registry or management endpoint through family API.
- Existing registry and historical chat data are not migrated. DO NOT uninstall original apps or clear their data.

## Security boundary
The broker derives each pair's symmetric credential and can therefore derive the content-encryption key. AES-GCM encrypts queued payloads but is **not** independently verifiable end-to-end encryption against the broker operator. Production requires independently derived device E2EE keys. Protected broker storage remains mandatory.

## Not yet supported
Voice/video calls, WebRTC signaling, TURN, recording, group calls, photo/file attachments, true remote presence, reliable doze/reboot wakeups, FCM, independent audit, and real physical two-device acceptance. Unsupported actions must fail closed. Do not publish as production.

## Gates
1. Go broker unit tests: signatures, replays, unknown/revoked devices, recipient isolation, persistent queue, acknowledgments.
2. Android public compile, QA tests, APK signature, merged manifest has no VpnService/BIND_VPN_SERVICE, no VPN permission prompt.
3. Isolated staging broker and separately scoped Cloudflare path. Authorized physical device enrollment.
4. A-to-B message, B-to-A delivery, replay and recovery, revoke.
5. Admin unchanged and previous embedded release unchanged.
6. Revisit full media/call architecture and production deployment only after acceptance.

Proposed ingress only (NOT deployed): paths /api/v1/enroll, /api/v1/sync, /api/v1/relay/send, /api/v1/relay/pull to loopback broker public listener. Never route generic /api or /admin.
