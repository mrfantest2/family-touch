# Family Touch — family technical assistance architecture

## Product goal
A paired family member may **request** technical help from a trusted person. The helper may use a connected MASTER-PC running KDE Connect, but no device is remotely controlled merely because it appears in the directory.

## Verified baseline
- The existing KDE Connect app can pair devices, share files/links, and run user-configured commands on supported peers.
- Family Touch has authenticated Tailnet messaging and a contacts directory; its new contact long-press menu can send an explicit help request to a paired peer.
- Generic Android app installation, screen recording, input control, and file access need the Android user to grant the appropriate permissions. Full KDE-based phone remote control is **not** verified; this design does not claim it exists.

## Proposed interoperable protocol, NOT enabled in this build
1. Family member taps **Request technical support**, selects a trusted helper and sees what support entails.
2. Helper sees a support request notification and accepts the request with an expiration.
3. Device owner receives a **local approval** prompt, chooses exactly which capabilities are allowed: view screen, send a file, open a link, browse picked documents. Default: none.
4. Device generates a short-lived session ID, challenge/response keys, role restrictions, expiry <=15 min, and an audit record.
5. For screen view, Android's MediaProjection prompt must be displayed on the device. For simulated taps/navigation, the device owner separately grants the appropriate system-controlled accessibility permission; no secret enabling of Accessibility or debugging.
6. Installing APKs uses Android PackageInstaller and a local confirmation prompt; the helper cannot silently install packages.
7. Session remains visible with persistent foreground notification and always-available **Stop assistance** action. A locked phone, revoked permission, or timeout ends support.
8. Use existing trusted KDE Connect session for file/link transfer when the device owner explicitly approves each action; never expose arbitrary shell execution, ADB pairing secrets, family VPN credentials or persistent unattended commands to messages.
9. Log request, helper identity, approval, actions performed, result and revocation. Never log chat bodies or PINs.
10. Test first with OP7 and MASTER-PC; S25 only after local visual acceptance.

## Build 39 scope
Contact long-press supports Ping, SOS chat message (not emergency dispatch), and a technical-help request. This is **messaging only**. Full KDE Connect embedding, screen navigation, remote commands, direct Android installation and device-side support session are not enabled in build 39.
