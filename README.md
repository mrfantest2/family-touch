# Family Touch

Native Android family messaging and audio/video calling across devices in the **same Tailscale tailnet**, without an application backend, cloud relay, authentication server or hosted database.

## Status
Clean-room implementation. Android/Kotlin/Compose; local device identity and nickname; pairing via one-time invitation; local message history; foreground peer listener; voice/video call signaling and WebRTC media. This software is an alpha and **not yet validated for release**. Any demo/reference screenshot is a visual reference, **not evidence of passing tests**.

## Design and non-negotiable constraints
- **Connection**: install and sign in to the official Tailscale Android client on each phone. Family Touch uses the existing Tailscale VPN tunnel; it **does not embed Tailscale, store tailnet keys or create another VPN**.
- **Registration**: an app-local random identity is created automatically on first launch, shown under a nickname derived from device model. No email/password/login screen.
- **Discovery**: Tailscale does not provide unprivileged Android apps with an automatic tailnet member roster, and multicast discovery is not provided. Share an invitation code once. Only invited peers that satisfy tailnet ACLs can communicate.
- **Networking**: TCP port 48321 for invitations, chat and WebRTC signaling; WebRTC UDP media uses Tailscale VPN paths. No public STUN or TURN.
- **Storage**: contact profiles and messages remain on each device. A message sent while a recipient is offline cannot be delivered until both devices connect; no hosted offline mailbox.
- **Security**: peer invitation secret authorizes contact pairing; all traffic rides over encrypted Tailscale connections, but this is not a separately audited app-level E2EE protocol. Do not publish invitation codes.
- **Background**: persistent Android foreground notification for incoming peer messages/calls. Android battery, OS and VPN lifecycle restrictions can still delay or prevent delivery.
- **Testing**: GitHub Actions static/compile checks and Master PC emulator only. **Never launch on S25, OP7 or any physical phone to debug or test.** Deploy new APK to S25 silently, without launching, only once verified and authorized by CI.
- **Publishing**: internal testing only, never the Play production or open-testing track. An internal-testing release still requires Play Console availability, enrolled testers, accepted permissions and valid upload signing keys.

## Build
Requires Android SDK 35, Java 17, Gradle 8.10.2.
Run: \`gradle :app:assembleDebug\`. The GitHub Actions workflow also produces a downloadable APK artifact.

## Connection
1. Enable official Tailscale on the S25 and any other family device, with tailnet ACLs allowing TCP 48321 and peer-to-peer UDP.
2. Launch Family Touch once to create local identity. In the Settings tab copy the peer invitation.
3. On the second device, open Contacts → Add Peer and paste the invitation.
4. Pairing registers both devices. You can then exchange messages and initiate calls.
5. Edit your nickname under Settings. Updates propagate when you message peers.

## Future release gating
Release checks: CI green, media and background call behavior validated on emulator, verified APK signing, no secrets in public repo, and explicit **internal** release-target configuration. S25 installation must use ADB install only; no am start, monkey launch, screen taps, screenshots or runtime tests on S25.
