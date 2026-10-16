# Embedded Tailscale integration — experimental raw-APK branch

Status: design and account requirements confirmed; no Android integration, authentication, or device validation completed.

## Goal
Let Family Touch connect to the user's **existing Tailscale tailnet**, associated with **k.e.h.m94@gmail.com**, without requiring the separate Android Tailscale application. The email is an account identifier, **not** a credential or proof of tailnet ownership.

## Distribution
- Raw, directly installed Android APK only; **no Google Play or AAB submission** for this branch.
- Keep the existing production application and signing identity intact; verify installed package and signing compatibility before upgrade.
- Publish versioned APK and SHA-256 only after a successful build and security/functional QA.

## Authentication and lifecycle
- Investigate embedding Go `tailscale.com/tsnet` via Android-compatible Go/JNI bridge; prove feasibility on MASTER-PC first.
- On first launch, use the official Tailscale interactive authentication flow to authorize the device against the existing account/tailnet. **Do not claim or simulate a preauthenticated session** from an email address.
- Persist per-install device/node state in app-private storage, encrypt sensitive material with Android Keystore where feasible, and reconnect automatically after successful initial authorization.
- Never package reusable auth keys, passwords, OAuth secrets, session files, or preauthenticated node identity in a distributable APK. Never publish authentication URLs or secrets in logs, source, CI artifacts, or screenshots.
- Provide explicit account/tailnet status, sign-out, reauthentication, revoked-key handling, and reconnection UX.

## Transport and privacy
- Userspace-only Tailscale networking for Family Touch; no global VPN routing or Android VpnService unless explicitly redesigned.
- Keep peer authorization and invitation consent separate from tailnet membership.
- Explicitly integrate app signaling and data transport with the embedded network; normal Android sockets and WebRTC UDP/ICE do not automatically route through tsnet.
- Preserve existing functionality and safe fallback. No groups and no additional hosted Family Touch backend.

## Acceptance gates
- [ ] Isolated MASTER-PC source checkout, toolchain, Android bridge feasibility
- [ ] Authentication with user's existing tailnet using official approval flow
- [ ] Encrypted persistent node state, reconnect, sign-out/revocation, ACL security review
- [ ] Emulator and S25 login, messaging, attachment, call and reconnect QA
- [ ] Android unit/lint/build and GitHub Actions green
- [ ] Versioned signed raw APK and SHA-256 published to authorized download endpoint

No completed implementation, preauthenticated account session, signed APK, or installation is implied by this document.
