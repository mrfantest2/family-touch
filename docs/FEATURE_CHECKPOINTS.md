# Family Touch — delivery checkpoints

Canonical source: GitHub mrfantest2/family-touch. Use OnePlus 7 for physical QA, Master PC for builds, and no S25 debugging in this phase. No group chats or hosted backend.

## Alpha05 — source implemented, still pending full device verification

- [x] Persistent call logs: voice/video, direction, timestamps, answered duration, missed/unanswered outcomes
- [x] Live duration timer from established WebRTC connectivity
- [x] Original eight-second Family Touch synthesized ringtone, triggered by incoming calls and stopped on answer/decline
- [x] Silent low-priority foreground connection channel with refresh only on VPN state changes
- [x] Independent message/incoming-call alerts; duplicate-message suppression
- [x] One-time location share with per-message confirmation, without live tracking
- [x] Peer presence: available, away, on call, offline (best-effort)
- [x] Profile photo picker and authenticated peer photo exchange
- [x] Direct authenticated photo/video transfer plumbing: max ten items per batch, 50 MiB per item, SHA-256, private files and FileProvider
- [ ] OP7 ringtone, background and real-notification QA
- [ ] OP7 real call-duration and missed-call QA
- [ ] OP7 location permission and map-sharing QA
- [ ] OP7 profile picker and remote photo update QA
- [ ] End-to-end photo/video transfers including 1/10/11 files, 49/50/51 MiB, corruption/reconnection
- [ ] Scrolling, font scale, animation, color contrast, navigation and accessibility review
- [ ] GitHub Actions CI green; signed internal-testing submission

## Next: consent-based recordings

- [ ] Every active caller can explicitly approve/decline every recording; refusal means no capture
- [ ] Visible recording indicator, audible notice, immediate stop and consent revocation
- [ ] Capture remote plus local streams without bypassing Android MediaProjection restrictions
- [ ] Encrypted private recording storage, timestamps, playback, sharing, deletion and retention
- [ ] Test on OP7 and approved emulator with consent and lifecycle failures

## Next: three-person calling without group chats

- [ ] Contact long-press multi-select callback/voice/video invitations
- [ ] In-call invite a third consenting participant, maximum three total
- [ ] Peer-to-peer WebRTC mesh over Tailscale, participant roster and correct audio/video controls
- [ ] Any active recording pauses until the new participant approves
- [ ] Multi-party call logs list participants; no persistent group chat

## Release gates

Never claim a feature works only because UI exists. Preserve keys and invitations outside public GitHub. Keep Play internal testing until functionality/privacy fully verified; consider production only after Play requirements, QA, privacy declarations and tester feedback.
