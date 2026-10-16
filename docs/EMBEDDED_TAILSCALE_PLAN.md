# Embedded Tailscale integration — experimental branch

Status: design only; no Android integration or device validation completed.

## Goal
Let Family Touch connect to an existing Tailscale tailnet without installing the separate Android Tailscale application.

## Architecture
- Investigate embedding Go `tailscale.com/tsnet` via a scoped Android native bridge (gomobile/JNI). Confirm Android compatibility, build size, and lifecycle behavior on MASTER-PC before selecting the bridge.
- Use userspace-only networking for Family Touch connections, without Android VpnService or global device routing.
- Browser-based interactive login and encrypted app-private node state. Never embed shared auth keys, OAuth client secrets, or login URLs in source, logs, CI artifacts, or screenshots.
- Provide sign-in/out, connectivity state, peer discovery constrained by tailnet grants, and reconnect handling.
- Existing Family Touch invitation approval and authenticated peer authorization remain mandatory: tailnet membership alone is not contact consent.
- Bind signaling/data channels and WebRTC transport to the embedded tailnet only after testing compatibility; UDP/ICE may require additional work. No assumptions that ordinary Android sockets automatically use tsnet.
- Preserve a fallback path and existing working functionality. No group chats or hosted Family Touch backend.

## Acceptance gates
- [ ] MASTER-PC dependency and build feasibility check
- [ ] Security review: state encryption, key rotation, logout/revocation, ACLs, no leaked secrets
- [ ] Login/relogin and peer connectivity on emulator
- [ ] Two-device call, presence, messaging, attachment and reconnect QA
- [ ] OP7 foreground-package safety verification before UI interaction
- [ ] Android unit/lint/emulator and GitHub Actions green
- [ ] Privacy and Play data-safety review before internal testing

No feature completion, signed release, or Play submission is implied by this document.
