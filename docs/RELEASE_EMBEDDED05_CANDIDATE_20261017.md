# Family Touch embedded05 candidate — 2026-10-17

Status: signed candidate, automatic enrollment activation pending. This is not an end-to-end verified VPN release.

Implemented:
- Required private-connection gate, Android VPN authorization, one Connect/Disconnect toggle, truthful status.
- Optional nickname on first launch retained. Normal app UI has no provider branding, manual invitations, or provisioning files. Required upstream license notices remain packaged.
- Android Keystore signing identity, signed/replay-protected enrollment, per-install RSA-encrypted single-use credentials, private-IP ownership proof, automatic approved-device directory and unique pair credentials.
- Windows enrollment broker with DPAPI-protected state, local-only CSRF-protected administration, bounded pending admission, expiry, revocation, request throttling, and scoped OAuth support.
- Presence/profile replies now retain their actual response payload; previous code discarded it.

Evidence:
- Signed assembleEmbedded, lintEmbedded and testEmbeddedUnitTest passed. 27 Android tests, zero failures. Lint: 0 errors, 9 warnings.
- Broker go test -count=1 ./... passed; go vet ./... passed. Includes native Windows DPAPI, RSA envelope, replay, probe, roster, revocation, admission/capacity/throttle and admin tests.
- Static security review found no remaining important integration issue after bounded-read and admission hardening fixes.
- Emulator candidate installed and launched. Required VPN gate, Android Connection request naming Family Touch, truthful unavailable-registration state, single Disconnect button, and stopped Connect state verified. No AndroidRuntime fatal exception observed. Emulator returned Home and shut down without clearing data.
- Live account enrollment, authenticated VPN routing, directory convergence, messages, attachments, WebRTC calls and restart reconnection remain unverified.

Artifact: Family-Touch-embedded05-candidate.apk
Package: win.fantest.familytouch.embedded
Version: 10 / 0.1.0-embedded05-standalone
Size: 144365900 bytes
SHA256: 907370916c37c1f73e0862abe248173c766193d2feb6852e42d47d5f2a9b1a51
Signing certificate SHA256: 8b028e6a167011f2d070c333000e23cda90dbc6d6948cc71d83894da4a33ee87
Candidate URL: https://fantest.win/downloads/Family-Touch-embedded05-candidate.apk

Activation blocker:
Chrome is authenticated in the intended admin account. A new OAuth credential draft is staged with only Auth Keys write/read and tag:family-touch; Generate has not been clicked. Action-time confirmation is pending because it creates persistent security-sensitive access. Admission choice (owner-approved devices or all APK installers isolated from other devices) is also pending; default remains owner approval. Never embed broker OAuth or reusable keys in the APK.

Automatic approval review rejected the combined enrollment-server startup/API-proxy deployment action with only 'blocked by policy'. It did not run. No listener on8797 or Apache family-touch configuration was installed. broker/apache-family-touch.conf is a reviewable template only. Do not bypass the rejection using another command wrapper.

Next: resolve the pending credential/access confirmation and deployment rejection; isolate app-tag traffic while preserving existing account access; configure DPAPI broker via its local password form, deploy only the API proxy (never /admin), establish same-user persistent startup, validate two real app identities through registration/discovery/messages/media/calls/restart, then promote the verified final alias. Existing Alpha05 and final alias are not overwritten by this candidate.

Delivery verification: HTTPS re-download matched the full SHA256 above. Physical S25 SM-S938B/qemu0 update-in-place returned Success; dumpsys verifies versionCode10. No launch command was issued. The S25 Download-folder copy also matches the full SHA256.
