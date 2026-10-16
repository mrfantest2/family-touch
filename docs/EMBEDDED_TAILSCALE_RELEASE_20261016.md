# Embedded Tailscale release evidence — 2026-10-16

Implementation commit: 857bad639abe3c777c894ce8986e51837d0173e0
Branch: feature/embedded-tailscale
Version: 0.1.0-embedded02 (7)
Package: win.fantest.familytouch
Signed APK: Family-Touch-Embedded-Tailscale.apk
Size: 144365880 bytes
SHA256: bf61bd2cbd75c32bb9c05faad5c12e84ee6f97ace48b9d68af4f7e69d9f05533
Release certificate SHA256: 8b028e6a167011f2d070c333000e23cda90dbc6d6948cc71d83894da4a33ee87
Direct URL: https://fantest.win/downloads/Family-Touch-Embedded-Tailscale.apk
Versioned URL: https://fantest.win/downloads/Family-Touch-Embedded-Tailscale-embedded02.apk

## Build evidence

Native Go AAR built successfully (arm64-v8a/x86_64).
Gradle testReleaseUnitTest assembleRelease lintRelease: BUILD SUCCESSFUL, 2m17s.
15 tests, 0 failures/errors. Lint: 0 errors, 9 existing warnings.
Gradle assembleQa: BUILD SUCCESSFUL, 1m38s.
apksigner verify succeeded, signer matches existing Family Touch release keystore.
HTTP download returned exact APK SHA256 above.
APK embeds upstream and native dependency licenses.
Alpha05 hash remains 5c00eb96acda089323b10de30e8641aae65a004e7fb3b9018486c5ce0dc7122c.

## S25 delivery

Authorized Remote Desktop Commander S25 host / ADB emulator-5554 reports SM-S938B, samsung, ro.kernel.qemu=0.
APK downloaded to private host staging and pushed to /sdcard/Download/Family-Touch-Embedded-Tailscale.apk.
Device-side sha256sum matches the public download above.
Original app Alpha01 has certificate 73450d8860871edb6a924e0e16d9e9967407908d3c19e3e587ea99e93ab9782a.
The current release signer differs; no update/uninstall/launch was attempted on S25.
Owner was asked whether to install a separate package alongside Alpha01; no response yet.
Do not erase data or pretend delivery is installation.

## Runtime limitations

QA package installed successfully on verified x86_64 emulator-5556 and am start accepted.
After dismissing an emulator System UI ANR, the app home and Settings rendered. Android VPN authorization dialog appeared; after accepting it, the native engine reported NeedsLogin through the local API (shown as Import a device provisioning file in Settings). dumpsys confirmed FamilyVpnService isForeground=true, foregroundId=2401, systemExempted type 0x00000400. No app AndroidRuntime crash observed. Authenticated tunnel routing remains unverified.
No tailnet-admin provisioning credential was supplied. Host Tailscale node is connected to the requested account, but this cannot enroll another identity.
Authenticated enrollment, tunnel routing, restart persistence and two-client messaging/media remain unverified.
The APK is an initial integration build; the full acceptance checklist is not complete.

GitHub Android validation succeeded: https://github.com/mrfantest2/family-touch/actions/runs/38933674142


## Owner-approved alongside build — 2026-10-16

Owner explicitly approved alongside installation. Signed build type embedded:
- Package win.fantest.familytouch.embedded
- Label Family Touch Embedded
- Version 0.1.0-embedded02-standalone (7)
- Filename Family-Touch-Embedded-Tailscale-embedded02-standalone.apk
- Size 144365900 bytes
- SHA256 57b8192247871f6120002e68c0e6ad57b8f2b62b3c5a55017a609d8ccb76b6ab
- Same release certificate 8b028e6a167011f2d070c333000e23cda90dbc6d6948cc71d83894da4a33ee87
- testEmbeddedUnitTest, assembleEmbedded, lintEmbedded: BUILD SUCCESSFUL in 1m51s, 15 tests pass.
- aapt confirms package and launcher label; apksigner verification passed.
- Stable requested URL now serves this alongside build; original-package embedded02 remains at its versioned URL.

Enrollment still requires a valid one-time tailnet provisioning credential. Owner approval does not constitute an authentication credential. No Tailscale account password, reusable key, host identity or preauthenticated identity has been embedded. Standalone Tailscale app is unnecessary. Android VPN consent is still required on first activation. S25 must remain unlaunched after installation.

Deployment confirmed: S25 ADB install --user 0 -r returned Success. Package
win.fantest.familytouch.embedded reports versionCode7, versionName
0.1.0-embedded02-standalone, installed=true, stopped=true, notLaunched=true.
Original win.fantest.familytouch remains installed. No launch or data reset performed.
Device download SHA256 and APK signer matched expected values before installation.

The unversioned URL may return an older Cloudflare-cached build. Use the immutable
versioned URL for this installation:
https://fantest.win/downloads/Family-Touch-Embedded-Tailscale-embedded02-standalone.apk

## embedded03 — single toggle and truthful connection status

Owner requested one Connect/Disconnect button, clear actual network status, and an eventual install-once family flow without provisioning files. The UI/status portion is implemented; automatic enrollment remains unfinished. See docs/FAMILY_CONNECTION_UX.md for the boundary and required owner authorization.

Package win.fantest.familytouch.embedded; versionCode8; versionName0.1.0-embedded03-standalone.
APK144365900 bytes. SHA25679562ee2f05ea83b8ef67120f5f298ff3be63ebe29b9f7bafbb4cfa1744540d1.
URL: https://fantest.win/downloads/Family-Touch-Embedded-Tailscale-embedded03-standalone.apk
Signing certificate unchanged:8b028e6a167011f2d070c333000e23cda90dbc6d6948cc71d83894da4a33ee87.
HTTPS downloaded bytes matched APK SHA256.

Validation:
- Added status regression tests: initial red run produced2 expected failures; final22 tests all pass (including7 status tests).
- Final testEmbeddedUnitTest, assembleEmbedded, lintEmbedded and assembleQa: BUILD SUCCESSFUL1m39s.
- Lint0 errors,9 existing warnings. apksigner passed.
- Static review found and corrected startup/shutdown ordering and invitation recomposition; follow-up review found no important/critical issues.
- Emulator QA update installed. Connect changes to a single Disconnect button; header and Settings show Family setup needed (native NeedsLogin), not Connected.
- Default Settings contains0 import/export/refresh controls; owner recovery controls are collapsed.
- Disconnect restores Connect and Disconnected; dumpsys confirms FamilyVpnService stopped. Reconnect returns to the correct unenrolled state, then disconnect stops it again. No app AndroidRuntime crash observed.
- Race ordering is statically reviewed; a deterministic cancellation-during-JNI instrumentation test was not run. No authenticated tunnel, calls or messages pass is claimed.
- Verified SM-S938B/qemu0 before deployment. Existing alongside and new APK signing certificates matched; update-in-place returned Success. Device reports versionCode8/versionNameembedded03. Device Download checksum matched.
- No S25 launch command was issued. notLaunched=false is historical because owner already opened the app; do not claim never launched.
- Original package and Alpha05 preserved. No uninstall/data wipe performed.

Automatic enrollment is not enabled: no owner-authorized tailnet enrollment backend/API credential exists. User was asked to sign into Tailscale admin on MASTER-PC once; response pending. Owner setup files remain a recovery path, not a solution to the requested install-once flow. Do not claim the full user objective is complete.
