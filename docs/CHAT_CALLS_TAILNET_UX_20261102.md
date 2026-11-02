# Family Touch — Embedded Tailscale UX and calling checkpoint (2026-11-02)

Base: Family Touch v0.3.8 feature branch commit 83dada9. Changes made in a separate worktree; pre-existing work and production remain unchanged.

## Implementation
- High-importance notification channel (new channel ID) for message banners, system notification sound, vibration and chat deep-link. Android permission, Do Not Disturb and user preferences still control presentation.
- Incoming private audio/video calls now show Android CallStyle Answer and Decline actions. Notification action is matched against the live call-session ID before taking action.
- Chat keyboard IME Send and visible Send button use the same outbox operation, clear the message, hide keyboard, release focus and play a quiet confirmation tone after local queue acceptance.
- Audio note dialog starts recording on open, with one prominent Stop & send button; errors retain recording for retry, Cancel discards. Received/outgoing audio attachments have inline Play/Pause and seeking.
- Microphone/camera permission grant now resumes the pending call action.
- Direct WebRTC calling waits for local SDP set success before sending offers/answers, rejects foreign-session answer/ICE and bounds early ICE candidate buffers. Direct ICE still needs tailnet reachability.
- Built-in Tailscale VPN now offers tailnet routes to other Android applications instead of restricting traffic to Family Touch's UID. Tailnet ACLs, VPN consent and single-active-Android-VPN constraints remain enforced.

## Open acceptance gates
- OP7 + S25 genuine message popup/sound/keyboard/audio-note, call ring/answer/hangup, two-way microphone/earpiece/speaker, video rendering and ICE across networks.
- OP7 was found using the separate Tailscale VPN, S25 the Brave VPN. Do not silently replace either active VPN.
- Do not declare call fix complete until audio/video two-device evidence is captured.
- Keep embedded app data and installed Admin untouched. Do not overwrite production update feed before acceptance.

No changes to the public no-VPN relay are part of this branch.
