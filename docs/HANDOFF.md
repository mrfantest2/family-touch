# Family Touch handoff — embedded Tailscale

Worktree: C:/Khalil/family-touch/embedded-tailscale. Original source directory preserved.
Branch: feature/embedded-tailscale. Implementation pushed as 857bad639abe3c777c894ce8986e51837d0173e0.
Read docs/EMBEDDED_TAILSCALE_PLAN.md and docs/EMBEDDED_TAILSCALE_RELEASE_20261016.md.

Signed embedded02 APK is public at the requested fantest.win URL and delivered to S25 Download with verified hash. Release tests/build/lint passed. Alpha05 unchanged. Owner approved alongside installation. The signed win.fantest.familytouch.embedded package (Family Touch Embedded) is now installed on S25; dumpsys confirms installed=true, stopped=true, notLaunched=true. Original package remains installed. Never uninstall/wipe or automatically launch S25. Use the versioned embedded02-standalone APK link because the unversioned URL can be cached.

No enrollment key/admin API credential provided. Do not copy host Tailscale identity or embed reusable keys. Encrypted device-specific provisioning is implemented; admin tooling is tools/New-DeviceProvisioning.ps1. Full authenticated networking/call acceptance is outstanding.

Emulator-5556 is the existing FamilyConnectVPNQA_API36 emulator. QA package installed; after dismissing System UI ANR, home/Settings and normal VPN consent worked. Native local API reached NeedsLogin and VPN foreground service was confirmed. Further authenticated tests need provisioning. S25 emulator-5554 serial exists only on the remote S25 host, is a physical Samsung, and must never be confused with local Windows emulator-5554.

Build logs are outside repo under C:/Khalil/family-touch/embedded-*.log. Host Java Unix-domain pipe workaround documented in implementation plan. Signing secrets remain local outside Git; AAR is pinned and checked in, with licenses in APK assets. CI workflow runs this feature branch.


