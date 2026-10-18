# Family Touch handoff — embedded06 update

2026-10-18 update: embedded06/version11 requests Android VPN consent automatically on first network-gate display after nickname setup. Declining leaves manual Connect available; subsequent launches do not repeatedly prompt. Existing service registration loop retries every30seconds while enabled. Signed assembleEmbedded, testEmbeddedUnitTest and lintVitalEmbedded passed. Signature matches existing internal certificate. Commit f6099d7 pushed on feature/embedded-tailscale.

Broker had stopped: public enrollment returned503. Restarted the existing executable with protected state as the same Windows user; public unsigned enrollment now returns400 (reachable, rejected invalid request). Registered FamilyTouchEnrollmentBroker at current-user logon with restart-on-failure. OAuth was generated and saved previously; protected registry exists. Admission remains owner approval. Successful authenticated enrollment, family discovery and calls remain unverified; do not describe this as fully verified working connectivity.

Published https://fantest.win/downloads/Family-Touch-Embedded06.apk and refreshed final alias. HTTP downloaded APK matches SHA256 1d5321860b75c93e38ac880f1f955b88593452734b5004e7696cecf6907a3120; size144365896. Drive ZIP verified size54309916: https://drive.google.com/file/d/1re0VhhOVSGAd34k_BRfz-kKVbu8zjclo/view . No device launch during this update. Older notes below are historical and superseded where inconsistent.

Worktree C:/Khalil/family-touch/embedded-tailscale; branch feature/embedded-tailscale. Original source directory untouched. Read docs/RELEASE_EMBEDDED05_CANDIDATE_20261017.md and docs/superpowers/plans/2026-10-17-family-enrollment.md first.

Signed candidate builds/tests/lint pass; automatic enrollment and directory code is implemented in Android and broker/**. Broker is not deployed/configured. Candidate is intentionally not called a working authenticated release. Normal UI removes provider branding, file setup and invitations; requires VPN before app use. First-launch optional nickname remains.

27 Android unit tests pass. Broker tests/vet/build pass. Emulator verified gate -> Android permission -> unavailable registration -> Disconnect -> stopped Connect. No authenticated routing/call/media pass. Emulator data preserved and emulator stopped after Home.

Pending human inputs: scoped OAuth generation + app-tag isolation confirmation, and private approved admission versus public APK auto-admission. Chrome draft https://console.tailscale.com/admin/settings/trust-credentials/add has Auth Keys write selected with tag:family-touch; no credential generated. Preserve secrets outside chat/Git/APK. Browser action-time policy requires confirmation for the persistent credential. Local admin can receive it through password fields and DPAPI protection.

Deployment automatic approval rejected server startup and API proxy action ('blocked by policy', no specific reason), so no listener or Apache route deployed. Do not retry through a disguised command. C:/ProgramData/FamilyTouchBroker was created empty with restricted ACL; no credentials/state. broker/apache-family-touch.conf is only a template. Deployment must expose /family-touch/api/ ->127.0.0.1:8797/api/, never /admin; public-proxy header required. Conservative rate limit shares120 requests/min across local proxy. Broker needs same Windows user at restart for DPAPI.

Candidate: https://fantest.win/downloads/Family-Touch-embedded05-candidate.apk
Size144365900; SHA256907370916c37c1f73e0862abe248173c766193d2feb6852e42d47d5f2a9b1a51; cert8b028e6a167011f2d070c333000e23cda90dbc6d6948cc71d83894da4a33ee87. Original Alpha05/final alias preserved. Full authenticated verification required before final promotion.

S25 latest verified read-only identity: local ADB100.99.16.99:5555, SM-S938B, ro.kernel.qemu0; installed embedded04version9 cert matches candidate. Never confuse remote physical S25 emulator-5554 with actual local emulators. Owner authorizes install update preserving data, but no automatic launch. No uninstall/data clear allowed. Original base package different signing key remains alongside.

Build environment/signing outsideGit documented in EMBEDDED_TAILSCALE_PLAN.md. Java21 with JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/Temp/nonexistent-family-touch and quoted -Pkotlin.compiler.execution.strategy=in-process. Private signing inputs remain C:/Khalil/Signing/FamilyTouch; never print password. Latest logs outsiderepo embedded05-final-build.log and directory-green.log. Existing calls/messages/attachments use TCP48321 and per-pair tokens; broker sync proves signing identity on that port. Automatic roster applies on Main dispatcher, removes revoked managed contacts while preserving history.

Delivery update: candidate HTTPS download matched SHA256; local authorized S25 ADB update-in-place succeeded, dumpsys version10/embedded05 confirmed. No app launch command. Original app preserved.

S25 Download/Family-Touch-embedded05-candidate.apk also verified SHA256 matching the candidate; deployment process exited0. Implementation pushed as3f270cdb0f4897b6d0a9f64a8faa34ba86f05e16. Android and Windows broker CI queued at that commit; no claim of CI completion.
