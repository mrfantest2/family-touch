# Embedded Tailscale — implementation and validation

Branch: feature/embedded-tailscale. This supersedes the earlier userspace-only/interactive-login proposal.

## Implemented

One APK, package win.fantest.familytouch, minSdk29/targetSdk36, with upstream Android libtailscale native engine (arm64 and x86_64), Android VpnService, socket protection, network/DNS/gateway change forwarding, foreground lifecycle, explicit start/stop and Android VPN consent. VPN applies to Family Touch only. Android permits one VPN per profile, so enabling it replaces another VPN. Existing TCP signaling, chat, attachments and WebRTC code are preserved. Existing peer invitations remain required; tailnet membership is separate from contact authorization.

The native backend uses Tailscale control-plane discovery and encrypted WireGuard/DERP connectivity. It is not a second APK and does not open the external Tailscale app. Native dependency provenance/rebuild command is in third_party/tailscale/README.md. Native dependency licenses ship in assets/licenses.

## Secure no-login provisioning

No password, reusable key or preauthenticated node identity is included in source or APK. Each installation creates an Android Keystore RSA3072 key. Settings exports only its public key and SHA256 device fingerprint. The administrator creates a **non-reusable, non-ephemeral** Tailscale auth key in the intended tailnet, with short expiry and device approval as appropriate, then runs:

    pwsh tools/New-DeviceProvisioning.ps1 -RequestFile device-request.json -OutputFile device.ftp

The tool prompts privately for the key (not process arguments/history), checks the request fingerprint, encrypts a device-bound envelope with a 10-minute expiry, and writes only ciphertext. Deliver it privately to the intended device; import in Settings, then tap Connect and accept Android VPN authorization. Delete the imported file afterward. No interactive Tailscale login screen is used. The key's actual single-use policy is enforced by Tailscale and must be selected by the administrator; the app cannot infer that policy from the opaque key string. RSA OAEP SHA1 is used for Android API29 Keystore interoperability; it is encryption padding, not a signature.

The native engine persists per-device identity in encrypted SharedPreferences backed by Android Keystore. Android backup is disabled; identity is not copied to another installation. Enabled VPN reconnects on later launches or sticky service restart; Android Always-on VPN can provide boot persistence. Expired/revoked identity needs a fresh provisioning envelope. An email address and the host's existing node state cannot authorize a new node. Fully automatic enrollment needs an authorized tailnet-admin credential/broker; none was supplied or extracted. Do not copy the host node identity.

## Verified / outstanding

- Native AAR compilation succeeded from pinned clean upstream source.
- Initial signed APK build, 15 release unit tests and release lint passed (0 errors, 9 existing warnings).
- HTTPS byte-for-byte download verified; Alpha05 preserved.
- Code review corrected API29 import compatibility and native network-change forwarding.
- S25 actual model SM-S938B, Samsung, ro.kernel.qemu=0 confirmed through its own authorized ADB server, despite emulator-5554 serial.
- Installed S25 Alpha01 certificate: 73450d8860871edb6a924e0e16d9e9967407908d3c19e3e587ea99e93ab9782a.
- Current release certificate: 8b028e6a167011f2d070c333000e23cda90dbc6d6948cc71d83894da4a33ee87.
- These certificates differ. A non-destructive update is impossible without the original signing key. Do not uninstall Alpha01. An alongside package needs the owner's choice.
- Enrollment, authenticated reconnect, calls/messages/attachments over the embedded network remain unverified without a provisioning credential and two authorized running clients. Do not represent unit/build checks as end-to-end acceptance.
- Local emulators list in ADB but shell requests stall; no runtime pass is claimed.

## Host build note

SDK: C:/Users/Administrator/AppData/Local/Android/Sdk. Java21, Gradle8.10.2.
The host Java Unix-domain pipe connect fails before Gradle starts. Set JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/Temp/nonexistent-family-touch (a nonexistent directory), forcing Java's normal TCP loopback fallback. Use -Pkotlin.compiler.execution.strategy=in-process to avoid a competing Kotlin daemon. This is host setup, not APK configuration. Signing uses existing private local environment configuration; no signing secrets are committed.
