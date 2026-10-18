# Family Touch groups, media and consensual recording — proposed design

Status: approved by owner in chat, 2026-10-18. Existing one-to-one calls, messages, private VPN, identities and history must be preserved. No destructive data migration.

## User outcome

Create named family group chats and start audio/video calls from them. View images and videos inside Family Touch. Send reliable locations and voice notes. Request call recording with explicit consent from everyone; automatically finish the local recording when the call disconnects. Browse, play and explicitly share completed recordings inside the app. Deliver signed APK updates to verified S25/OP7, publish a direct HTTPS download, and submit a compatible AAB to Google Play internal testing with the requested tester emails.

## Proposed scope and limits

Group chats support the selected registered family devices. Initial group calls support four simultaneous devices including the initiator, using a full mesh of peer connections; no hosted media mixer. This cap needs owner confirmation before implementation. Group members can decline individual calls without leaving the chat. Show participants, joining/connected/failed state, mute, speaker, camera and hangup controls. Preserve one-to-one operation and legacy protocol compatibility; unsupported features produce an explicit upgrade prompt.

## Groups and delivery

Persist group ID, title, creator, membership revision and stable device IDs locally. Authenticate every incoming group event using the existing sender/peer authentication, and check membership separately. Serialize membership updates through the creator; allow members to leave. Reject unauthorized membership changes, duplicate IDs, replayed revisions and messages from nonmembers. Keep sender identity, ordering timestamps and per-recipient delivery status. Fan out text and attachments directly to each recipient; interrupted or offline recipients stay pending with bounded retries. Do not falsely label a group message delivered after only one acknowledgment. Existing chat history remains readable.

## Media, location and voice notes

Open transferred files only after existing size and SHA256 checks finish. Use app-private stored files and an internal viewer: proportional image zoom/pan and video/audio playback with seek, duration and errors. Retain explicit Android share/open externally actions via FileProvider. Never interpret untrusted attachment names as paths.

For location, request runtime permission in response to the location action, support approximate permission, acquire a fresh fix with timeout and cancellation, and show a preview with accuracy and timestamp before sending. Never silently send an old last-known location. Validate finite coordinates/ranges on reception. Keep Maps as an explicit action; avoid remote map requests until the user chooses them.

Voice notes use microphone permission, start/stop/cancel, duration indicator, a proposed five-minute limit and on-device encoded audio. Extend authenticated attachment MIME validation to supported audio formats. Release the microphone on cancellation, background interruption and call start; do not compete with a live WebRTC microphone. Keep files private and transfer only after Send.

## Calls and recording

Refactor the single PeerConnection into a call-session coordinator with one connection and ICE queue per participant and shared local camera/microphone tracks. Negotiate and authenticate session ID, participant set and each connection before accepting SDP/ICE. Surface missing participants and partial connections; end and release resources deterministically on hangup, network loss and process/service teardown.

Recording request identifies a fresh recording ID, session, requester, participants and recording type. Each participant sees Accept/Decline; no timeout or silence counts as acceptance. Require unanimous authenticated consent for exactly the current participants before producing a recording. Prevent duplicate/replayed acknowledgments. Show a persistent recording indicator to everyone. Any withdrawal stops capture. Joining participants immediately stops capture and requires a new request/consent round before resuming. Persist an auditable local consent summary with the recording; never transmit media before permission.

Capture actual call media, not speaker sound with a second microphone. Use WebRTC's microphone sample callback, authenticated recording-media transport for participant-owned audio where necessary, and video-track sinks. Mix timestamped participant audio and compose video frames into Android MediaCodec/MediaMuxer output on the recording device, with bounded queues and disk-space checks. This requires a feasibility checkpoint against the pinned WebRTC SDK before claiming recording support. Android screen capture is not a substitute for remote call audio. Do not ship a Record control that produces microphone-only or silent recordings.

Finalize playable local files on normal call end, withdrawal and network disconnect; retain and recover interrupted temporary recordings where possible. Abrupt process death cannot guarantee finalization and needs an explicit recovery path. App-private storage and backup exclusion apply to messages, attachments, voice notes, recordings and temporary media. A recording library provides playback, share-to-chat, Android share and user-initiated deletion. No automatic upload, analytics media, cloud backup or server recording storage.

## Verification and release gates

Test membership authorization/replay protection, mixed client versions, retry/delivery, location timeout/cancellation/permission states, attachment validation, unanimous consent/decline/withdrawal/member joins and recording interruption recovery. Build signed APK/AAB and run Android checks. Verify physical device identity, charging, signing continuity, package/version/hash and data-preserving installation. Exercise two-device texts, image/video playback, voice notes, locations, audio/video calls and reconnect on S25/OP7; separately test three/four-participant calls and unanimous recording. Two phones alone cannot establish group-call correctness.

Do not claim playable recording without verifying every participant is audible, video layout plays correctly, consent works and finalized output can be replayed/shared. Record manual/runtime limitations precisely.

Play package/signing compatibility and existing app entry must be inspected first; internal testing must use the established package and accepted upload certificate. Preserve existing testers and add majidmohsinmaijd@gmail.com, peachymira2013@gmail.com, k.e.h.m94@gmail.com, Fantest94@gmail.com. Confirm actual list membership and internal-track processing/release state. Credentials, key recovery or Google review barriers may require owner action; never substitute the standalone embedded package for an existing Play app identity without checking compatibility.
