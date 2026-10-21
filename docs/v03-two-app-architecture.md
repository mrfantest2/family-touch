# Family Touch v0.3 — two-app architecture and visual redesign

## Approved product requirements
Family Touch is the family-facing app, built-in VPN only. No separate Tailscale installation or external VPN mode in the shipping variant. Family Touch Admin is a **different Android package** for the authorized Samsung Galaxy S25; it must leave Android's VPN slot free and use normal Android DNS/internet plus an HTTPS connection to MASTER-PC. The Admin product must eventually offer the same chats, calls and contacts as Family Touch, plus administrator management.

## Visual specification (approved mockups)
Use a WhatsApp-inspired interaction model without copying WhatsApp branding: light neutral background `#F7FAF8`, white surfaces `#FFFFFF`, emerald primary `#008F56`, dark green-neutral text `#142722`, muted text `#667670`, soft mint `#E4F6EB`, circular avatars, compact rounded rows, accessible typography, consistent floating actions and a persistent navigation bar.
- Family: Chats / Calls / Contacts / Settings; display private VPN health unobtrusively.
- Admin: Login / Chats / Calls / Contacts / Admin; Admin sections for users, devices, approval, revoke, delete (typed confirmation), timestamped audit and diagnostics.
- Maintain legible color contrast and RTL layouts. Use real backend status, not fabricated online status. Never show fake family messages in production.

## Checkpoints and state
- [x] v16 remains the published stable release, untouched.
- [x] Feature branch `feature/family-touch-v03-dual-apps` created from v16.
- [x] Family build migrates prior external-mode choice to built-in and removes mode-selection UI.
- [x] Family visual palette migrated to light emerald. Full screen-by-screen polish still pending.
- [x] Separate `admin` build target/package with fully native Compose login and device management through authenticated MASTER-PC HTTPS APIs, no VPN service.
- [ ] Admin *server-side* S25 device binding using Android Keystore hardware-backed key plus server enrollment / revocation. A client-side model check is only provisional and is **not a security boundary**.
- [x] Remove Admin WebView and expose authenticated native device listing and approve/revoke/delete APIs; passwords are memory-only; writes require explicit confirmation.\r\n- [ ] Move from Basic auth to short-lived, server device-bound sessions and hardware enrollment before production.
- [ ] Admin chats / calls / contacts through an authenticated, encrypted HTTPS messaging and WebRTC signaling relay that bridges to approved private peers. No direct 100.x tailnet route or use of Android VPN APIs from Admin. For media calls, provide secure relay/TURN and proper consent, fail closed if unavailable.
- [ ] Roles, server-verified user accounts and permissions, audit retention policy and admin actions.
- [ ] Test foreground/background listener recovery and calls with offline/reconnecting peers.
- [ ] UI screenshots and accessibility tests on S25 and OP7; audit contact, chat, voice and video flows.
- [x] Deploy Family Touch v0.3 built-in to OP7 (signed APK, installed with versionCode 17).\r\n- [ ] Deploy both signed APKs to S25 after Android installer confirmation; verify native Admin and preserve existing VPN.\r\n- [ ] No Play release or security production claim until hardware binding, full end-to-end calls and relay pass.

## Networking and privacy
A DNS resolver only resolves hostnames; it does not connect the Admin phone to Tailscale private addresses. Therefore the Admin app uses `https://family-admin.fantest.win/admin` for existing management, and a **separately authenticated HTTPS relay** is required before chats/calls can become functional. The family APK uses its embedded VPN engine and existing private broker; use no reusable auth tokens in source control. Android normal DNS settings should not be overwritten.

## Build and validation
Build tasks: `:app:assembleEmbedded`, `:app:assembleAdmin`, `:app:testQaUnitTest`. Inspect the merged Admin manifest and verify that it contains **no** `android.net.VpnService` intent filter, `BIND_VPN_SERVICE` service or implicit VPN startup. App signing secrets remain only on the authorized build host, never in GitHub. Admin credentials remain memory-only in the early preview; avoid recording user passwords in logcat or analytics.
### v0.3.1-connect01 (build 18) follow-up
- Removed obsolete private enrollment attempts to AgentBox, which is offline; enrollment now uses the working MASTER-PC public HTTPS broker.
- After 90 seconds of unchanged NeedsLogin/NoState, the family VPN reports delayed sign-in with automatic retry rather than an indefinite unqualified 'Registering this device' message. This is not proof the embedded engine completed authentication.
- Native Admin uses opt-in Remember my login, encrypted via Android Keystore-backed preferences. Saved credentials are only written after a successful server authentication, revalidated automatically on reopen, and erased on Sign out.
- Refined navigation colors, surfaces, and device cards to match the green-and-white mockup; further layout/UX review pending.
- Broker authenticated native Admin API exposes only last credential issuance/expiry *timestamps* for diagnostics; no token, envelope, or key values.
- Go broker tests, QA unit tests, and both signed APK assembly completed successfully on MASTER-PC.
- End-to-end S25 private VPN login remains an explicit verification checkpoint; do not claim success from credential issuance alone.
