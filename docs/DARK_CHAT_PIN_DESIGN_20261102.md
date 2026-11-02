# Family Touch — Dark Chat / Portrait / Optional PIN

Android/Jetpack Compose implementation, based on user-provided visual reference. Original wallpaper glyphs were drawn in Compose; no WhatsApp image or source asset is copied.

## Design
- Dark palette: conversation background #0B141A, header and incoming bubbles #202C33, outgoing bubbles #005C4B, readable text #E9EDEF and accent #00A884.
- Conversation header: back, peer avatar, presence, video, audio, three-dot menu for search/media/security.
- Wallpaper: original low-contrast geometric motifs.
- Messages: content-sized top-corner directional tails, grouped chat labels, in-bubble reply previews, inline HH:MM metadata and blue double-checks when read. Chat opens on latest messages and tracks new messages only when the user is near the bottom. Existing wire protocol and receipts preserved.
- Media: inline play/pause and scrubbable original-pattern waveform UI, small avatar and microphone badge; duration is pre-read from original audio metadata and playback remains local. Waveform heights are decorative/deterministic, not a decoded spectrogram.
- Composer: 50dp rounded graphite field, emoji chooser, attachments, camera capture via FileProvider and green action button switching between mic and send; IME Send hides keyboard and queues the message.
- Portrait-only MainActivity declared in AndroidManifest, no auto-rotation in the app.
- Optional six-digit PIN under Settings; salt plus PBKDF2-SHA256, encrypted app preferences, attempt cooldown, gate on resume and lock after leaving the app; no PIN enabled by default. App data is preserved for in-place installs.
- Android native Compose components. No HTML/CSS duplicate or public no-VPN relay dependency.

## Code entry points
- `app/src/main/java/win/fantest/familytouch/MainActivity.kt`: activity, chat header, messages, reply block, composer, settings
- `app/src/main/java/win/fantest/familytouch/DarkChatDecor.kt`: original wallpaper, tails, read receipts, security note
- `app/src/main/java/win/fantest/familytouch/VoiceMessagePlayer.kt`: voice waveform and media controls
- `app/src/main/java/win/fantest/familytouch/FamilyPin.kt`: local PIN security and optional settings
- `app/src/main/AndroidManifest.xml`: orientation
- `app/src/main/res/xml/family_attachments.xml`: camera FileProvider
- `app/src/test/java/win/fantest/familytouch/FamilyPinPolicyTest.kt`: six-digit PIN policy tests

## Safety and QA
- QA build and Android signed in-place version code 31 are separate deliverables.
- No production update-feed, broker, tunnel, or Admin changes.
- Do not claim fully correct layout, in-chat interaction, camera, or PIN until acceptance on a physical phone; compile/unit tests alone do not establish visual acceptance.
- OnePlus 7 remains the primary device; S25 is visual acceptance only after safe device access.
