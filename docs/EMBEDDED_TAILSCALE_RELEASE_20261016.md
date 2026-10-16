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

