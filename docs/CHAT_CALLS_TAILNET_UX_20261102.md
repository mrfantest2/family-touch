# Family Touch — Chat, alerts, calls and tailnet UX checkpoint (2026-11-02)

## Implemented in feature branch
- Message channel moved to a new high-importance Android notification channel with default notification sound and vibration. Tap a message alert to open the matching conversation. Android 13+ still requires notification permission; user/system settings can suppress banners.
- Chat composer IME Send and button share the same outbox action; accepted sends clear the composer, hide keyboard, release focus and play a short local send-confirmation tone.
- Existing voice recorder stays local until sent; the primary recording action is now **Stop & send**. Failed transfers keep the note for retry; cancel deletes it.
- Incoming/outgoing audio attachments have in-chat Play/Pause and seeking without leaving the conversation.
- Call permissions now complete the originally requested start/answer action on grant, including microphone and camera authorization.
- Embedded Family Touch VPN no longer restricts Android route usage to the Family Touch application; other Android apps can use the tailnet routes provided by the embedded VPN. This does NOT bypass the Tailscale tailnet ACLs or activate an exit node. Android supports only one VPN service simultaneously.

## Known limitations / acceptance gates
- Public HTTPS preview does **not** have live audio/video calling, voice-note/attachment relay, TURN services, production broker routing or independent per-device end-to-end keys. Those functions stay disabled/unavailable in that variant until implemented and tested.
- WebRTC private calling still needs a genuine two-device audio/video end-to-end test, including ring/answer/hangup, speaker/earpiece, camera and ICE across different networks.
- Notification pop-up presentation is controlled by Android channel/user settings and must be measured on actual devices outside app, while dozing and with DND.
- No production update, no live broker/tunnel change, no Admin change and no automatic install onto S25 without successful acceptance.
- Verify on OP7 before S25; preserve installed packages/data; use Android signed update with signature/version checks.
