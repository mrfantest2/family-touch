# Family groups, media and recording implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Execute inline in the existing isolated worktree; no parallel agents required. Track actual results, not attempted commands.

**Goal:** Deliver the owner's approved group communication, on-device media and unanimous-consent recording update, with verified device and internal-test releases.

**Architecture:** Extend the authenticated direct peer protocol with versioned group/session events. Keep local persistence and media playback separate from transfer. Replace the singleton call connection with a bounded four-participant session, then build a recording consent coordinator and real media capture/muxing on top.

**Tech Stack:** Kotlin/Compose, existing pinned WebRTC SDK, Android ImageDecoder/VideoView/MediaPlayer/MediaRecorder/MediaCodec/MediaMuxer, LocationManager, app-private files, existing authenticated TCP/embedded VPN, Gradle/JUnit, ADB and Google Play Console.

**Spec:** docs/design/FAMILY_GROUPS_MEDIA_RECORDING.md (owner approved).

## Global constraints

- Preserve existing identities, messages, contacts, attachments, calls, package/signing continuity and the original source checkout.
- Minimum SDK29; targetSDK36. Four simultaneous group-call devices; five-minute voice-note limit.
- No server storage of conversation content, attachments, voice notes or recordings. Backups disabled. Explicit sharing only.
- Never bypass Android permissions or a device security gate. No uninstall/data clear or automatic launch just for installation.
- Recording requires all current participants' authenticated explicit consent. A new member or withdrawal stops capture immediately.
- A release is not complete without actual runtime evidence; two phones do not establish three/four-party correctness.
- Preserve existing tester memberships and add majidmohsinmaijd@gmail.com, peachymira2013@gmail.com, k.e.h.m94@gmail.com, Fantest94@gmail.com.

## Review focus

- Mixed old/new client versions must preserve one-to-one messaging and reject unsupported group/recording negotiation clearly.
- A receiver must never accept group membership or recording consent merely because the sender is a valid contact.
- Network loss, backgrounding and call interruption must release microphone/camera/player resources and keep recoverable private files.
- Approximate/denied location permissions, stale fixes and no fix must not send misleading coordinates.
- Empty/full storage, malicious attachment metadata and process death during muxing must not publish corrupt media as completed.

## Files and responsibilities

Existing `MainActivity.kt` retains navigation and calls the new UI components; `FamilyStore.kt` retains legacy persistence and adds migration-safe group/recording metadata. `AttachmentTransport.kt` retains authenticated transfer and exposes validated local URIs. `PeerService.kt` dispatches authenticated versioned events. `CallController.kt` becomes the public facade for the session coordinator.

Create focused files: `ChatMediaViewer.kt`, `VoiceNoteController.kt`, `LocationShareController.kt`, `GroupStore.kt`, `GroupProtocol.kt`, `GroupChatScreen.kt`, `CallSession.kt`, `GroupCallScreen.kt`, `RecordingConsent.kt`, `CallRecordingEngine.kt`, `RecordingLibrary.kt`. Add pure policy tests under `app/src/test/java/win/fantest/familytouch/` and instrumented media/location lifecycle checks where Android APIs are essential.

### Task 1: In-app attachments and reliable locations

Interfaces: `AttachmentTransport.localUri(context, info): Uri` returns a URI only after validating ID, size and existence; `ChatMediaViewer(info, onDismiss)` owns playback lifecycle. `LocationShareController.request(onResult)` delivers a fresh timestamped/accurate fix or a bounded error and exposes cancel.

- [ ] Add failing tests for unsupported MIME, unsafe IDs, invalid/nonfinite/out-of-range coordinates, stale fixes and timeout outcomes. Retain legacy location text readability.
- [ ] Run targeted tests to establish failures; implement validated file resolution and internal proportional image/video viewing, seek controls and explicit share/open-external actions.
- [ ] Replace old-location fallback with cancellable fresh acquisition, accuracy/timestamp preview, approximate-permission support, and explicit Send.
- [ ] Pass tests; verify player release on dismiss, no map network request before explicit action, large media and no-location behavior; commit.

### Task 2: Voice notes

Interfaces: `VoiceNoteController` exposes idle/recording/preview state, start/stop/cancel/release and a validated local audio URI. `AttachmentTransport` supports an explicit audio MIME allowlist using the existing authenticated protocol and size limits.

- [ ] Write/run failing tests for MIME validation, 300-second cap, empty recording, cancel, call interruption and invalid metadata.
- [ ] Implement permission-triggered recording, duration, stop/cancel, preview playback and explicit send. Prevent microphone competition with a call; clean abandoned temporary files.
- [ ] Pass policy tests and Android recorder/player lifecycle checks, including permission denial/background interruption; commit.

### Task 3: Group chat membership and reliable fan-out

Interfaces: `Group(id,title,creatorId,members,revision)`, `GroupStore.applyEvent(sender,event)` validates membership/revisions, and `GroupProtocol.send(groupId,messageId,payload)` tracks per-recipient acknowledgment. No contact token is itself group membership authorization.

- [ ] Write/run failing tests for creator-only changes, member leave, replay/revision/order checks, nonmember messages, duplicate delivery, offline members and legacy history migration.
- [ ] Add version/capability negotiation in authenticated peer messages. Implement local metadata and group message persistence, bounded pending delivery and member selection/creation UI.
- [ ] Fan out text, images/video, locations and voice notes with true per-recipient states; never mark all delivered on one acknowledgment.
- [ ] Pass tests and two-device group messaging; separately validate at least three members and unsupported old clients; commit.

### Task 4: Four-party mesh calls

Interfaces: `CallSession(sessionId,participants,isVideo)` owns `Map<peerId,PeerConnection>`, per-peer ICE/SDP and shared local media. `CallController` preserves existing entry points while adding `startGroup(groupId,isVideo)`. All signaling includes a validated session and participant set.

- [ ] Write/run failing state-machine tests for cap4, unauthorized offers, glare/late ICE, partial join/decline, repeated hangup and mixed protocol versions.
- [ ] Refactor one-to-one calling into session-owned resources with per-peer callbacks, bounded queues and deterministic release; preserve existing audio routing and call history.
- [ ] Implement group audio/video UI with participant tiles, mute/speaker/camera controls, joining/failure state and leave behavior.
- [ ] Pass tests; verify one-to-one regression and three/four-way runtime calls, reconnect, background and network failure; commit.

### Task 5: Unanimous recording consent and capture feasibility

Interfaces: `RecordingConsent.request(recordingId,sessionId,participants,type)`, `accept(sender,recordingId,revision)`, `withdraw(sender)` and `membershipChanged()`; capture permission is true only for the exact unanimous current participant set.

- [ ] Write/run failing tests for silence/decline, forged/replayed approval, other session IDs, membership changes, withdrawal, disconnect and concurrent requests.
- [ ] Implement authenticated request/accept/decline/stop events and participant dialogs/indicators. Stop capture before applying membership changes.
- [ ] Probe the pinned WebRTC microphone sample callback and video sinks with timestamps; verify remote participant audio transport and actual mixed output on devices. Do not use speaker/microphone re-recording or assume Android playback capture includes voice calls.
- [ ] Pass consent tests and capture probe. If SDK cannot deliver necessary media, preserve working features and report the exact SDK/native work needed; never ship a false recording control. Commit validated state machine/probe evidence.

### Task 6: Local recorder, finalization and library

Interfaces: `CallRecordingEngine.start(consentedSession,privateDestination)`, `stop(reason)` finalizes an indexed playable file; `recover()` checks interrupted sessions; `RecordingLibrary` indexes only validated complete/recovered files plus consent metadata.

- [ ] Write/run failing tests for timestamp alignment, bounded queues, member audio mixing, storage failure, interrupted file recovery and state transitions.
- [ ] Implement authenticated participant-owned audio transport, local audio mix, video composition/encoding, AAC/video muxing and bounded storage use, gated solely by Task5's consent coordinator.
- [ ] Finalize on disconnect/withdrawal; recovery handles process death honestly. Add local playback, explicit share-to-chat/Android share and user deletion. No automatic media upload.
- [ ] Verify every participant is audible, video output plays and seeks, membership changes stop capture, and finalized files replay/share after the call; commit.

### Task 7: Physical verification and release

- [ ] Run unit tests, appropriate Android tests/lint and signed APK/AAB builds. Inspect accepted Play package/signing identity before selecting the internal bundle variant; do not upload the wrong package.
- [ ] Use authorized RP2/USB/local ADB routes; verify actual SM-S938B and GM1903 plus charging. Verify certificate/hash/version and update in place without wiping data.
- [ ] Complete the full two-phone acceptance run, plus third/fourth-device group call/recording run. Keep evidence and remaining limitations in HANDOFF; do not claim performance from emulators.
- [ ] Publish immutable APK URL, download it over HTTPS and compare SHA256. Preserve Alpha05. Update download alias only to the intended verified artifact.
- [ ] Inspect existing Play internal track and tester lists. Preserve current entries, add the four exact emails, prepare release notes, upload matching signed AAB, and check processing. Complete any required browser action-time confirmation with the concrete release/tester change visible.
- [ ] Verify tester memberships and actual internal release status, then push all implementation/evidence and report commit, versions, checksums, download/test links and deployments. Update docs/HANDOFF.md.

## Execution selection

Recommended: execute inline in this chat, task-by-task, reviewing each verification result before proceeding. Owner approval of this plan selects that execution method. Implementation has not started at the time this plan was written.
