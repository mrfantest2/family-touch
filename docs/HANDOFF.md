# Family Touch handoff — embedded04 nickname onboarding

Worktree:C:/Khalil/family-touch/embedded-tailscale. Branch:feature/embedded-tailscale. Original source directory untouched.

Current installed S25 build:win.fantest.familytouch.embedded versionCode9,0.1.0-embedded04-standalone. Signed, verified, updated in place; no S25 launch command. Original app stays installed. Owner already launched the previous build, so do not claim notLaunched=true now. ModelSM-S938B,qemu0 verified on remote S25 ADB emulator-5554 (not local Windows emulator-5554).

Download:https://fantest.win/downloads/Family-Touch-Embedded-Tailscale-embedded04-standalone.apk
SHA256:eb75ec1f4965067c6ee00cac380c8b395971f13778ef125c0e2d6ed90cc14573
Size144382288. Use immutable versioned link; stable alias can be Cloudflare cached.

Read docs/FAMILY_CONNECTION_UX.md and latest section of docs/EMBEDDED_TAILSCALE_RELEASE_20261016.md. Single toggle, shared header/Settings/notification status, actual native+Android VPN IPv4 match and validated physical-network requirement are implemented. Owner file tools collapsed. Cancellation/shutdown waits for native startup before final stop.22 tests, build, lint, signing/HTTPS checks pass. Emulator normal connect/unenrolled/disconnect/reconnect flow passes. No authenticated routing/call/message pass.

CRITICAL REMAINING USER GOAL:family installs once without import/export or extra apps. This is NOT implemented merely by hiding recovery controls. Need owner-authorized enrollment service with secure family-device admission and per-device short-lived credentials. No admin/API credential exists. Asked owner via pending async question to sign into Tailscale admin on MASTER-PC once; do not request secrets in chat, embed reusable keys, copy host node identity, or auto-enroll anonymous public APK downloaders. Existing app reaches NeedsLogin, not a connected VPN.

Build/signing environment and Java socket workaround are documented in EMBEDDED_TAILSCALE_PLAN.md. Existing secrets stay outside Git. Logs/toggle UI dumps reside outside repo under C:/Khalil/family-touch. Local emulator-5556 is FamilyConnectVPNQA_API36; QA package updated and left at Home with VPN stopped. S25 remains install-only unless owner explicitly requests runtime control.

Nickname onboarding added: fresh installs get Save/Skip once, existing identities skip it, chosen/default name and completion persist atomically. Notification consent follows that choice. Fresh Save/restart and existing-upgrade UI paths verified on emulator; see docs/RELEASE_EMBEDDED04_20261017.md. Both emulator QA and alongside test processes are stopped, preserving their data.

