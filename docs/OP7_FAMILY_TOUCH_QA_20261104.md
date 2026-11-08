# Family Touch — OP7 physical QA checkpoint (2026-11-04)

Branch: feature/family-touch-map-live-location-20261102
Pre-checkpoint HEAD: 23c862b2e3a0a342713ad3ff2d3ccccec718ad23 (matched GitHub origin).
Source worktree clean before this documentation-only update.

## Verified directly on OnePlus 7

- Recovered authorized ADB through Agentbox through its authorized local ADB bridge (OP7 standalone Tailscale and direct RDC entry were offline). Device GM1903, Android 11, physical 1080x2340.
- Installed win.fantest.familytouch.embedded versionCode 42, versionName 0.3.25-map-live-privacy-embedded. APK SHA256 8461bdeff00f47d82d448ec0c3f0c97314832c505d949ef9eb986edb35763ad5 equals the verified release candidate; original firstInstallTime retained. No reinstall or data clear.
- OP7 Android dumpsys connectivity confirms CONNECTED/VALIDATED embedded VPN on tun1. Tailscale identified this active node as an active Family Touch node. This is distinct from the offline standalone OP7 node the separate standalone Tailscale identity.
- Family Touch MainActivity launched and remained in focus. Process PID 18319 stayed stable during checks. A sampled logcat scan returned no Family Touch fatal exception or ANR markers (not a full crash history).
- Existing conversation: video call, voice call, voice-note playback, attachment, camera and voice composer controls present. No call, voice recording, or message sent.
- Existing shared map pin tap successfully opened Google Maps. This does not validate embedded map tiles or live sharing.
- Attachment menu rendered Photos, Videos, Documents and files, Location: map pin or live, and Camera.
- Selecting Location displayed Android's runtime location-permission dialog. It was dismissed without approval: ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION both remained granted=false. App showed a permission-declined notice; no location sent.
- Chat composer accepted focus; Android reported LatinIME visible with softInputMode ADJUST_RESIZE. No text was entered or sent. Visual overlap not independently evaluated.
- Navigated Calls (74 UI nodes), Contacts (46), Settings (53), Chats (44). Process remained PID 18319. Left OP7 on Family Touch Chats screen.
- OP7 battery was 88%, not charging at the last device check.

## Additional read-only cross-device check

- S25 still has Family Touch embedded build 42 installed, process running, and ChatGPT in foreground. Android reports its active VPN is standalone com.tailscale.ipn with a distinct tailnet address; this check did not prove the S25 embedded Family Touch peer connected.

## Remaining acceptance gates — do not mark passed

- Explicit on-device Android location permission, live street tiles, draggable pin selection, and cancel behavior.
- Sending a one-time pin, with owner consent and a known recipient.
- Opt-in 15-minute, 1-hour and 8-hour live sessions, Stop, expiry, notification privacy, background and disconnect behavior.
- Authenticated two-device message/call and live-location delivery, verified by sender/receiver evidence.
- Full visual keyboard-overlap acceptance and sustained stability.

No feature changes, APK installation, private messages, calls, location sharing, permissions or production release were made in this checkpoint. PR #14 remains draft; no merge or release is authorized by this QA evidence.